// SPDX-License-Identifier: GPL-3.0-or-later
// Baseline adapter uses the vendored public Mongoose header, never copies its implementation.
#include "spike.h"
#include "mongoose.h"
#include <string.h>
#include <stdlib.h>
#include <openssl/pem.h>
struct connection { struct spike_reply reply;size_t sent;bool pending,tracked;uint64_t born; };
struct baseline {struct spike_app *app;struct mg_str cert,key;};
static void send_body(struct mg_connection *c,struct connection *state) {
    if(!state->pending || c->send.len>SPIKE_CHUNK)return;
    size_t n=state->reply.length-state->sent;if(n>SPIKE_CHUNK)n=SPIKE_CHUNK;
    if(n)mg_send(c,state->reply.body+state->sent,n);
    state->sent+=n;if(state->sent==state->reply.length){state->pending=false;c->is_resp=0;}
}
static void callback(struct mg_connection *c,int event,void *data) {
    struct baseline *base=c->fn_data;struct spike_app *app=base->app;struct connection *state;
    memcpy(&state,c->data,sizeof(state));
    if(event==MG_EV_ACCEPT) {
        if(app->active>=SPIKE_CONNECTIONS){app->rejected++;c->is_closing=1;return;}
        state=calloc(1,sizeof(*state));if(!state){c->is_closing=1;return;}
        memcpy(c->data,&state,sizeof(state));state->tracked=true;state->born=spike_now();app->active++;
        if(app->active>app->peak)app->peak=app->active;
        if(c->is_tls) {
            struct mg_tls_opts options={0};options.cert=base->cert;options.key=base->key;mg_tls_init(c,&options);
            struct mg_tls *tls=c->tls;
            if(!tls || !spike_configure_tls(app,tls->ctx))c->is_closing=1;
            else spike_configure_ssl(app,tls->ssl);
        }
    }else if(event==MG_EV_HTTP_MSG && state) {
        struct mg_http_message *message=data;
        if(mg_match(message->uri,mg_str("/ws"),NULL)){mg_ws_upgrade(c,message,"Sec-WebSocket-Protocol: spike\r\n");return;}
        char path[256];if(message->uri.len>=sizeof(path)){c->is_closing=1;return;}
        memcpy(path,message->uri.buf,message->uri.len);path[message->uri.len]=0;
        struct mg_str *etag=mg_http_get_header(message,"If-None-Match");
        bool conditional=etag && mg_strcmp(*etag,mg_str("\"spike-v1\""))==0;
        spike_reply(app,path,conditional,&state->reply);state->sent=0;state->pending=state->reply.length>0;state->born=spike_now();
        mg_printf(c,"HTTP/1.1 %u Fixture\r\nContent-Type: %s\r\nContent-Length: %zu\r\n%s\r\n",state->reply.status,state->reply.type,state->reply.length,
            !strcmp(path,"/image")?"ETag: \"spike-v1\"\r\n":"");
        send_body(c,state);if(!state->pending)c->is_resp=0;
    }else if(event==MG_EV_WS_MSG) {
        struct mg_ws_message *message=data;if(message->data.len>SPIKE_CHUNK){c->is_closing=1;return;}
        app->ws_messages++;mg_ws_send(c,message->data.buf,message->data.len,WEBSOCKET_OP_TEXT);
    }else if((event==MG_EV_POLL||event==MG_EV_WRITE) && state) {
        send_body(c,state);
        if(spike_now()-state->born>2000)c->is_draining=1;
    }else if(event==MG_EV_CLOSE && state) {
        if(state->tracked && app->active)app->active--;
        free(state);state=NULL;memcpy(c->data,&state,sizeof(state));
    }
}
static bool listen_on(struct mg_mgr *mgr,struct baseline *base,const char *address,unsigned port,bool tls) {
    if(!address)return true;
    char url[160];snprintf(url,sizeof(url),"%s://%s%s%s:%u",tls?"https":"http",strchr(address,':')?"[":"",address,strchr(address,':')?"]":"",port);
    return mg_http_listen(mgr,url,callback,base)!=NULL;
}
int spike_backend(struct spike_app *app) {
    BIO *cert=BIO_new(BIO_s_mem()),*key=BIO_new(BIO_s_mem());if(!cert||!key){BIO_free(cert);BIO_free(key);return 4;}
    if(!PEM_write_bio_X509(cert,app->certificates.fallback)||!PEM_write_bio_PrivateKey(key,app->certificates.leaf_key,NULL,NULL,0,NULL,NULL)){BIO_free(cert);BIO_free(key);return 4;}
    char *cert_data,*key_data;long cert_size=BIO_get_mem_data(cert,&cert_data),key_size=BIO_get_mem_data(key,&key_data);
    struct baseline base={app,mg_str_n(cert_data,(size_t)cert_size),mg_str_n(key_data,(size_t)key_size)};
    struct mg_mgr mgr;mg_mgr_init(&mgr);mg_log_set(MG_LL_ERROR);
    bool ready=listen_on(&mgr,&base,app->options.ipv4,app->options.http,false)&&listen_on(&mgr,&base,app->options.ipv6,app->options.http,false)&&
        listen_on(&mgr,&base,app->options.ipv4,app->options.https,true)&&listen_on(&mgr,&base,app->options.ipv6,app->options.https,true);
    int result=4;
    if(ready){puts("READY mongoose");fflush(stdout);uint64_t started=spike_now();while(!app->stop&&spike_now()-started<(uint64_t)app->options.lifetime*1000)mg_mgr_poll(&mgr,1);result=0;}
    mg_mgr_free(&mgr);BIO_free(cert);BIO_free(key);return app->active?5:result;
}
