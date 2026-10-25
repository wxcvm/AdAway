// SPDX-License-Identifier: GPL-3.0-or-later
#include "spike.h"
#include <libwebsockets.h>
#include <string.h>
struct session {
    struct spike_reply reply;
    size_t sent, ws_length;
    unsigned char websocket[SPIKE_CHUNK];
};
static int callback(struct lws *wsi,enum lws_callback_reasons reason,void *user,void *in,size_t length) {
    struct spike_app *app=lws_context_user(lws_get_context(wsi));
    struct session *session=user;
    switch(reason) {
    case LWS_CALLBACK_OPENSSL_LOAD_EXTRA_SERVER_VERIFY_CERTS:
        return spike_configure_tls(app,(SSL_CTX *)user)?0:1;
    case LWS_CALLBACK_FILTER_NETWORK_CONNECTION:
        if(app->active>=SPIKE_CONNECTIONS){app->rejected++;return 1;}return 0;
    case LWS_CALLBACK_SERVER_NEW_CLIENT_INSTANTIATED:
        lws_set_opaque_user_data(wsi,app);app->active++;
        if(app->active>app->peak)app->peak=app->active;
        lws_set_timeout(wsi,PENDING_TIMEOUT_ESTABLISH_WITH_SERVER,2);return 0;
    case LWS_CALLBACK_WSI_DESTROY:
        if(lws_get_opaque_user_data(wsi)==app){if(app->active)app->active--;lws_set_opaque_user_data(wsi,NULL);}return 0;
    case LWS_CALLBACK_HTTP: {
        if(!session)return -1;
        char etag[64]={0}; lws_hdr_copy(wsi,etag,sizeof(etag),WSI_TOKEN_HTTP_IF_NONE_MATCH);
        spike_reply(app,(const char *)in,!strcmp(etag,"\"spike-v1\""),&session->reply);session->sent=0;
        unsigned char buffer[LWS_PRE+1024],*start=buffer+LWS_PRE,*p=start,*end=buffer+sizeof(buffer);
        if(lws_add_http_common_headers(wsi,session->reply.status,session->reply.type,(lws_filepos_t)session->reply.length,&p,end))return -1;
        if(!strcmp((const char *)in,"/image") && lws_add_http_header_by_name(wsi,(const unsigned char *)"etag:",(const unsigned char *)"\"spike-v1\"",10,&p,end))return -1;
        if(lws_finalize_write_http_header(wsi,start,&p,end))return -1;
        if(!session->reply.length)return lws_http_transaction_completed(wsi)?-1:0;
        lws_callback_on_writable(wsi);return 0;
    }
    case LWS_CALLBACK_HTTP_WRITEABLE: {
        if(!session || session->sent>=session->reply.length)return 0;
        unsigned char buffer[LWS_PRE+SPIKE_CHUNK];
        size_t remaining=session->reply.length-session->sent,n=remaining>SPIKE_CHUNK?SPIKE_CHUNK:remaining;
        memcpy(buffer+LWS_PRE,session->reply.body+session->sent,n);
        int sent=lws_write(wsi,buffer+LWS_PRE,n,n==remaining?LWS_WRITE_HTTP_FINAL:LWS_WRITE_HTTP);
        if(sent!=(int)n)return -1;
        session->sent+=n;
        if(session->sent==session->reply.length)return lws_http_transaction_completed(wsi)?-1:0;
        lws_callback_on_writable(wsi);return 0;
    }
    case LWS_CALLBACK_RECEIVE:
        if(!session || length>SPIKE_CHUNK-session->ws_length)return -1;
        memcpy(session->websocket+session->ws_length,in,length);session->ws_length+=length;
        if(lws_is_final_fragment(wsi)&&!lws_remaining_packet_payload(wsi)){app->ws_messages++;lws_callback_on_writable(wsi);}return 0;
    case LWS_CALLBACK_SERVER_WRITEABLE: {
        if(!session)return -1;
        unsigned char buffer[LWS_PRE+SPIKE_CHUNK];memcpy(buffer+LWS_PRE,session->websocket,session->ws_length);
        int n=lws_write(wsi,buffer+LWS_PRE,session->ws_length,LWS_WRITE_TEXT);
        if(n!=(int)session->ws_length)return -1;
        session->ws_length=0;return 0;
    }
    default:return 0;
    }
}
static const struct lws_protocols protocols[]={
    {"http",callback,sizeof(struct session),SPIKE_CHUNK,0,NULL,0},
    {"spike",callback,sizeof(struct session),SPIKE_CHUNK,0,NULL,0},
    LWS_PROTOCOL_LIST_TERM
};
struct ticker { lws_sorted_usec_list_t sul; struct lws_context *context; };
static void tick(lws_sorted_usec_list_t *sul){struct ticker *t=(struct ticker *)sul;lws_sul_schedule(t->context,0,&t->sul,tick,100000);}
static bool listen_on(struct lws_context *context,struct spike_app *app,const char *address,bool ipv6,bool tls) {
    if(!address)return true;
    struct lws_context_creation_info info;memset(&info,0,sizeof(info));
    info.port=(int)(tls?app->options.https:app->options.http);info.iface=address;info.protocols=protocols;
    info.vhost_name=ipv6?(tls?"tls6.spike.invalid":"http6.spike.invalid"):(tls?"tls4.spike.invalid":"http4.spike.invalid");
    info.options=LWS_SERVER_OPTION_DO_SSL_GLOBAL_INIT;
    if(ipv6)info.options|=LWS_SERVER_OPTION_IPV6_V6ONLY_MODIFY|LWS_SERVER_OPTION_IPV6_V6ONLY_VALUE;
    else info.options|=LWS_SERVER_OPTION_DISABLE_IPV6;
    if(tls)info.options|=LWS_SERVER_OPTION_CREATE_VHOST_SSL_CTX;
    info.timeout_secs=2;info.timeout_secs_ah_idle=2;info.keepalive_timeout=2;
    return lws_create_vhost(context,&info)!=NULL;
}
int spike_backend(struct spike_app *app) {
    lws_set_log_level(LLL_ERR|LLL_WARN,NULL);
    struct lws_context_creation_info info;memset(&info,0,sizeof(info));
    info.port=CONTEXT_PORT_NO_LISTEN;info.protocols=protocols;info.user=app;
    info.options=LWS_SERVER_OPTION_EXPLICIT_VHOSTS|LWS_SERVER_OPTION_DO_SSL_GLOBAL_INIT;
    info.fd_limit_per_thread=SPIKE_CONNECTIONS+16;info.max_http_header_data2=SPIKE_BUFFER;
    struct lws_context *context=lws_create_context(&info);if(!context)return 4;
    bool ready=listen_on(context,app,app->options.ipv4,false,false)&&listen_on(context,app,app->options.ipv6,true,false)&&
        listen_on(context,app,app->options.ipv4,false,true)&&listen_on(context,app,app->options.ipv6,true,true);
    if(!ready){lws_context_destroy(context);return 4;}
    struct ticker ticker;memset(&ticker,0,sizeof(ticker));ticker.context=context;lws_sul_schedule(context,0,&ticker.sul,tick,100000);
    puts("READY lws");fflush(stdout);uint64_t started=spike_now();
    while(!app->stop&&spike_now()-started<(uint64_t)app->options.lifetime*1000)if(lws_service(context,0)<0)break;
    lws_sul_cancel(&ticker.sul);lws_context_destroy(context);
    return app->active?5:0;
}
