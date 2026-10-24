"""Extract production response functions for real private dual-stack HTTP tests.

UID attribution and bookkeeping are fixtures. The protected-path regression
also supplies a non-loopback policy identity over private loopback sockets.
The classifier, config reader, fast-fail paths, image selection and Mongoose
file response are production code, not a rewritten response implementation.
"""
import argparse
import importlib.util
import shutil
from pathlib import Path

spec = importlib.util.spec_from_file_location('blocks', Path(__file__).with_name('stats-json-bounds-fixture.py'))
blocks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(blocks)

PREAMBLE = r'''
#include "mongoose/mongoose.h"
#include "lt_map.h"
#include "f5_guard.h"
#include "request_host.h"
#include "rule_norm.h"
#include "listen_address.h"
#include "response_log.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <errno.h>
#ifdef _WIN32
typedef unsigned int uid_t;
#define strcasecmp _stricmp
#define strncasecmp _strnicmp
#endif
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); exit(1); } } while (0)
#define LOG_WARN(...) ((void)0)
#define CORS_HDR "Access-Control-Allow-Origin: *\r\n"
struct settings { const char *resource_dir; int block_image_count; char block_images[1][128]; bool proxy_filter; };
static const char *s_pin_resource_dir;
static uid_t conn_load_uid(struct mg_connection *c) { (void)c; return 42; }
enum { HIST_BLOCKED };
static void hist_add(int kind) { (void)kind; }
struct appstat { uint64_t blocked; };
static struct appstat fixture_app;
static struct appstat *app_find_or_add(uid_t uid) { (void)uid; return &fixture_app; }
static void top_host_add(const char *host) { (void)host; }
static void app_note_host(struct appstat *app, const char *host) { (void)app; (void)host; }
static void ws_push_broadcast(struct settings *s) { (void)s; }
#define QLOG_MAX 10000
#define QLOG_PERSIST_MAX 4096
#define QLOG_RENDER_MAX 400
#define QLOG_JSON_MAX 49152
static FILE *open_atomic_write(const char *path,char *tmp,size_t cap) {
    int length=snprintf(tmp,cap,"%s.tmp",path); if(length<0 || (size_t)length>=cap) return NULL;
    return fopen(tmp,"wb");
}
static void finish_atomic_write(FILE *file,const char *path,const char *tmp) {
    CHECK(!fclose(file));
#ifdef _WIN32
    remove(path); /* Disposable fixture only; production uses its atomic helper. */
#endif
    CHECK(!rename(tmp,path));
}
static bool fixture_remote_peer;
static int fixture_loopback_peer(const uint8_t *ip,int ipv6) {
    /* Identity injection tests the production LAN refusal while all test
       traffic remains on ephemeral loopback sockets, with no host changes. */
    return !fixture_remote_peer && la_loopback_peer(ip,ipv6);
}
'''

TAIL = r'''
static struct settings fixture_settings;
static unsigned server_family;
static void fixture_server(struct mg_connection *c,int event,void *data) {
    if (event==MG_EV_HTTP_MSG) {
        struct mg_http_message *hm=(struct mg_http_message *)data;
        struct settings *s=&fixture_settings;
        size_t response_offset=c->send.len;
        PROTECTED_PATH_BODY
#ifdef FIXTURE_CONFIG_GENERATION
        if (mg_match(hm->uri, mg_str("/control"), NULL)) {
            if (load_block_cfg(s->resource_dir)) reply_block_config_ack(c);
            else mg_http_reply(c,503,"Content-Type: text/plain\r\n","Block config reload failed");
            return;
        }
#endif
        uint64_t tick=mg_millis(); memcpy(c->data,&tick,sizeof(tick));
        server_family=c->rem.is_ip6 ? 6u : 4u;
        if (!reply_blocked_by_type(c,hm)) {
            PLACEHOLDER_BODY
        } else {
            qlog_add_status(conn_load_uid(c),QLOG_BLOCK,hm,c,
                            response_log_status(c->send.buf,c->send.len,response_offset));
        }
    }
}
static size_t expected_image_length;
static unsigned char expected_image_hash[32];
static void bytes_hash(const unsigned char *bytes,size_t length,unsigned char hash[32]) {
    mg_sha256_ctx context;
    mg_sha256_init(&context); mg_sha256_update(&context,bytes,length); mg_sha256_final(hash,&context);
}
struct receipt { int done,status; char request[512],type[128],etag[128],generation[40],text[64],cache[128]; unsigned char prefix[12],hash[32]; size_t length; };
static void fixture_client(struct mg_connection *c,int event,void *data) {
    struct receipt *result=(struct receipt *)c->fn_data;
    if (event==MG_EV_CONNECT) mg_send(c,result->request,strlen(result->request));
    if (event==MG_EV_ERROR) result->done=-1;
    if (event==MG_EV_HTTP_MSG) {
        struct mg_http_message *hm=(struct mg_http_message *)data;
        struct mg_str *type=mg_http_get_header(hm,"Content-Type"), *etag=mg_http_get_header(hm,"Etag");
        result->status=mg_http_status(hm);
        if(type) snprintf(result->type,sizeof(result->type),"%.*s",(int)type->len,type->buf);
        if(etag) snprintf(result->etag,sizeof(result->etag),"%.*s",(int)etag->len,etag->buf);
        struct mg_str *cache=mg_http_get_header(hm,"Cache-Control");
        if(cache) snprintf(result->cache,sizeof(result->cache),"%.*s",(int)cache->len,cache->buf);
        struct mg_str *generation=mg_http_get_header(hm,"X-AdAway-Config-Generation");
        if(generation) snprintf(result->generation,sizeof(result->generation),"%.*s",(int)generation->len,generation->buf);
        if(hm->body.len<sizeof(result->text)) snprintf(result->text,sizeof(result->text),"%.*s",(int)hm->body.len,hm->body.buf);
        CHECK(hm->body.len<=128u*1024u);
        size_t prefix=hm->body.len<sizeof(result->prefix) ? hm->body.len : sizeof(result->prefix);
        memcpy(result->prefix,hm->body.buf,prefix); result->length=hm->body.len;
        bytes_hash((const unsigned char *)hm->body.buf,hm->body.len,result->hash);
        result->done=1; c->is_closing=1;
    }
}
static struct receipt request(struct mg_mgr *manager,const char *url,const char *path,const char *host,const char *headers) {
    struct receipt result; memset(&result,0,sizeof(result));
    snprintf(result.request,sizeof(result.request),"GET %s HTTP/1.1\r\nHost: %s\r\n%sConnection: close\r\n\r\n",path,host,headers);
    CHECK(mg_http_connect(manager,url,fixture_client,&result));
    uint64_t deadline=mg_millis()+3000;
    while(!result.done && mg_millis()<deadline) mg_mgr_poll(manager,1);
    CHECK(result.done==1);
    mg_mgr_poll(manager,0); // Deliver close while this callback's result is alive.
    return result;
}
static void check_pipeline(unsigned family) {
    qlog_clear(); fixture_remote_peer=true;
    struct mg_connection connection; memset(&connection,0,sizeof(connection));
    connection.fn_data=&fixture_settings; connection.rem.is_ip6=family==6;
    connection.rem.addr.ip[0]=family==6 ? 0xfd : 192;
    const char *requests[]={
        "GET /internal-test?token=secret HTTP/1.1\r\nHost: pipeline.fixture.invalid\r\n\r\n",
        "GET /asset.js?password=secret HTTP/1.1\r\nHost: pipeline.fixture.invalid\r\n\r\n"
    };
    size_t second_offset=0;
    for(unsigned index=0;index<2;index++) {
        struct mg_http_message hm; CHECK(mg_http_parse(requests[index],strlen(requests[index]),&hm)>0);
        if(index) second_offset=connection.send.len;
        fixture_server(&connection,MG_EV_HTTP_MSG,&hm);
    }
    CHECK(response_log_status(connection.send.buf,connection.send.len,0)==403);
    CHECK(response_log_status(connection.send.buf,connection.send.len,second_offset)==200);
    CHECK(s_qlog_count==2 && s_qlog_metadata[0].status==403 && s_qlog_metadata[1].status==200);
    CHECK(!strcmp(s_qlog_metadata[0].path,"/internal-test") && !strcmp(s_qlog_metadata[1].path,"/asset.js"));
    CHECK(s_qlog[0].action==QLOG_DENY && s_qlog[1].action==QLOG_BLOCK);
    fixture_remote_peer=false; mg_iobuf_free(&connection.send);
    printf("family%u production handler queued pipeline statuses=403,200 independent metadata=ok\n",family);
}
static void configure(const char *directory,int cache,int dedup,int images) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/block_config.json",directory);
    FILE *file=fopen(path,"w"); CHECK(file);
    CHECK(fprintf(file,"{\"cache_reuse_enabled\":%d,\"dedup_enabled\":%d,\"reply_images\":%d}",cache,dedup,images)>0);
    CHECK(fclose(file)==0);
    load_block_cfg(directory);
}
static void image_is_complete(const struct receipt *result) {
    CHECK(result->status==200 && !strcmp(result->type,"image/webp"));
    CHECK(result->length==expected_image_length && !memcmp(result->hash,expected_image_hash,sizeof(result->hash)));
    CHECK(!memcmp(result->prefix,"RIFF",4) && !memcmp(result->prefix+8,"WEBP",4));
}
static void write_policy(const char *directory,const char *key,int enabled) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/block_config.json",directory);
    FILE *file=fopen(path,"w"); CHECK(file);
    CHECK(fprintf(file,"{\"%s\":%d}",key,enabled)>0); CHECK(fclose(file)==0);
    load_block_cfg(directory);
}
static void check_categories(struct mg_mgr *manager,const char *url,const char *directory,unsigned family) {
    const struct { const char *key,*path,*headers; int status,type; } cases[]={
        {"reply_images","/category.png","",200,LT_IMAGES},
        {"reply_scripts","/category.js","",200,LT_SCRIPTS},
        {"reply_styles","/category.css","",200,LT_STYLES},
        {"reply_fonts","/category.woff","",204,LT_FONTS},
        {"reply_media","/category.mp4","",204,LT_MEDIA},
        {"reply_structures","/category.xml","",200,LT_STRUCT},
        {"reply_api","/category.json","",200,LT_API},
        {"reply_telemetry","/event","",204,LT_TELEMETRY},
        {"reply_config","/config","",200,LT_CONFIG},
        {"reply_ws_sse","/stream","Accept: text/event-stream\r\n",204,LT_WS}
    };
    for(size_t index=0;index<sizeof(cases)/sizeof(cases[0]);index++) {
        write_policy(directory,cases[index].key,1);
        struct receipt enabled=request(manager,url,cases[index].path,"categories.fixture.invalid",cases[index].headers);
        CHECK(enabled.status==cases[index].status && s_last_type==cases[index].type && s_last_mode==RB_REPLY);
        if(index==0) image_is_complete(&enabled);
        write_policy(directory,cases[index].key,0);
        struct receipt disabled=request(manager,url,cases[index].path,"categories.fixture.invalid",cases[index].headers);
        CHECK(disabled.status==204 && disabled.length==0 && !strcmp(disabled.cache,"no-store"));
        CHECK(s_last_type==cases[index].type && s_last_mode==RB_DENY);
    }
    printf("family%u policy categories=10 on/off verified\n",family);
}
static void check_domain_responses(struct mg_mgr *manager,const char *url,const char *directory,unsigned family) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/rule_overrides.txt",directory);
    FILE *file=fopen(path,"w"); CHECK(file);
    CHECK(fputs("example.com = 404\nsub.example.com = 200\n",file)>=0); CHECK(fclose(file)==0);
    write_policy(directory,"rule_override_enabled",1); s_override_loaded_ms=0;
    struct receipt first=request(manager,url,"/domain.png","sub.example.com","");
    CHECK(first.status==404 && !strcmp(first.text,"blocked by rule override\n"));
    struct receipt boundary=request(manager,url,"/domain.png","badexample.com",""); image_is_complete(&boundary);
    const int statuses[]={204,403,404,410,503,200};
    for(size_t index=0;index<sizeof(statuses)/sizeof(statuses[0]);index++) {
        file=fopen(path,"w"); CHECK(file);
        CHECK(fprintf(file,"example.com = %d\n",statuses[index])>0); CHECK(fclose(file)==0);
        s_override_loaded_ms=0;
        struct receipt result=request(manager,url,"/domain.png","child.example.com","");
        CHECK(result.status==statuses[index]);
        CHECK(s_last_type==-1 && s_last_mode==RB_DOMAIN_RESPONSE);
        if(statuses[index]==204) CHECK(result.length==0);
        else CHECK(!strcmp(result.text,"blocked by rule override\n") && !strcmp(result.type,"text/plain"));
    }
    write_policy(directory,"rule_override_enabled",0);
    struct receipt disabled=request(manager,url,"/domain.png","example.com",""); image_is_complete(&disabled);
    CHECK(remove(path)==0);
    printf("family%u domain responses=6 first-match/label-boundary/disabled verified\n",family);
}
#ifdef FIXTURE_CONFIG_GENERATION
static void check_generation_ack(struct mg_mgr *manager,const char *url,const char *directory) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/block_config.json",directory);
    FILE *file=fopen(path,"w"); CHECK(file);
    const char *token="0123456789abcdef0123456789abcdef";
    CHECK(fprintf(file,"{\"generation\":\"%s\",\"reply_images\":0}",token)>0); CHECK(fclose(file)==0);
    struct receipt result=request(manager,url,"/control","fixture.invalid","");
    CHECK(result.status==200 && !strcmp(result.generation,token) && !strcmp(result.text,"OK: block config reloaded"));
    CHECK(!cfg_images);
    file=fopen(path,"w"); CHECK(file);
    CHECK(fputs("{\"reply_images\":2}",file)>=0); CHECK(fclose(file)==0);
    result=request(manager,url,"/control","fixture.invalid","");
    CHECK(result.status==503 && !cfg_images && !result.generation[0]);
    file=fopen(path,"w"); CHECK(file);
    CHECK(fputs("{\"reply_images\":1",file)>=0); CHECK(fclose(file)==0);
    result=request(manager,url,"/control","fixture.invalid","");
    CHECK(result.status==503 && !cfg_images);
    file=fopen(path,"w"); CHECK(file);
    CHECK(fputs("{\"nested\":{\"reply_images\":0}}",file)>=0); CHECK(fclose(file)==0);
    result=request(manager,url,"/control","fixture.invalid","");
    CHECK(result.status==200 && cfg_images && !result.generation[0]);
    configure(directory,0,0,1);
    result=request(manager,url,"/control","fixture.invalid","");
    CHECK(result.status==200 && !result.generation[0] && !strcmp(result.text,"OK: block config reloaded"));
    puts("generation ACK / legacy body / invalid configuration preservation verified");
}
#endif
static void run_family(const char *directory,const char *binding,unsigned family,int baseline) {
    struct mg_mgr manager; mg_mgr_init(&manager);
    struct mg_connection *listener=mg_http_listen(&manager,binding,fixture_server,NULL); CHECK(listener);
    char url[128]; mg_snprintf(url,sizeof(url),"%s:%c%c%M","http",'/','/',mg_print_ip_port,&listener->loc);
    char host[96]; snprintf(host,sizeof(host),"repeat-%u.fixture.invalid",family);
#ifdef FIXTURE_CONFIG_GENERATION
    if(!baseline) check_generation_ack(&manager,url,directory);
#endif
    configure(directory,1,0,1);
    if(!baseline) {
        qlog_clear();
        fixture_remote_peer=true;
        uint64_t blocked_before=fixture_app.blocked;
        struct receipt forbidden=request(&manager,url,"/internal-test?token=secret",host,"");
        CHECK(forbidden.status==403 && fixture_app.blocked==blocked_before);
        struct receipt favicon=request(&manager,url,"/favicon.ico?credential=secret",host,"");
        image_is_complete(&favicon);
        fixture_remote_peer=false;
        CHECK(s_qlog_count==2 && s_qlog[0].action==QLOG_DENY && s_qlog[1].action==QLOG_BLOCK);
        CHECK(s_qlog_metadata[0].status==403 && !strcmp(s_qlog_metadata[0].path,"/internal-test"));
        CHECK(s_qlog_metadata[1].status==200 && !strcmp(s_qlog_metadata[1].path,"/favicon.ico"));
        CHECK(s_qlog_family[0]==family && s_qlog_family[1]==family);
        qlog_save(directory); qlog_clear(); qlog_load(directory);
        CHECK(s_qlog_count==2 && s_qlog_metadata[0].status==403 && s_qlog_metadata[1].status==200);
        char summary[QLOG_JSON_MAX]; qlog_render(summary,sizeof(summary));
        CHECK(strstr(summary,"/internal-test") && strstr(summary,"/favicon.ico") && !strstr(summary,"secret"));
        printf("family%u protected-path /internal-test=403 action=3 /favicon.ico=200 action=1 QLG3=ok peer-identity=fixture\n",family);
        /* Exercise the public unknown-status proxy helper and bounded page. */
        struct mg_http_message hm; memset(&hm,0,sizeof(hm)); hm.uri=mg_str("/proxy");
        qlog_add(42,QLOG_PROXY,&hm,NULL); CHECK(s_qlog_metadata[2].status==0);
        struct mg_connection page; memset(&page,0,sizeof(page)); qlog_reply(&page,&hm); CHECK(page.send.len>0); mg_iobuf_free(&page.send);
        check_pipeline(family);
    }
    struct receipt first=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    struct receipt second=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    image_is_complete(&first); CHECK(server_family==family);
    if(baseline) CHECK(second.status==204 && second.length==0);
    else { image_is_complete(&second); CHECK(first.length==second.length && !memcmp(first.hash,second.hash,sizeof(first.hash))); }
    printf("family%u legacy_cache same_request=%d,%d body=%zu,%zu\n",family,first.status,second.status,first.length,second.length);
    if(!baseline) {
        struct receipt script=request(&manager,url,"/asset.js",host,"Accept: application/javascript\r\n");
        struct receipt style=request(&manager,url,"/asset.css",host,"Accept: text/css\r\n");
        struct receipt image=request(&manager,url,"/extensionless",host,"Sec-Fetch-Dest: image\r\n");
        CHECK(script.status==200 && !strcmp(script.type,"application/javascript"));
        CHECK(style.status==200 && !strcmp(style.type,"text/css")); image_is_complete(&image);
        struct receipt again=request(&manager,url,"/extensionless",host,"Sec-Fetch-Dest: image\r\n"); image_is_complete(&again);
        char conditional[192]; snprintf(conditional,sizeof(conditional),"Accept: image/png\r\nIf-None-Match: %s\r\n",first.etag);
        struct receipt unchanged=request(&manager,url,"/same.png",host,conditional);
        CHECK(first.etag[0] && unchanged.status==304 && unchanged.length==0);
        CHECK(s_qlog_metadata[(s_qlog_pos+QLOG_MAX-1)%QLOG_MAX].status==304);
        struct receipt ranged=request(&manager,url,"/range.png",host,"Range: bytes=0-3\r\n");
        CHECK(ranged.status==206 && ranged.length==4 && s_qlog_metadata[(s_qlog_pos+QLOG_MAX-1)%QLOG_MAX].status==206);
        snprintf(fixture_settings.block_images[0],sizeof(fixture_settings.block_images[0]),"missing-image.webp");
        struct receipt missing=request(&manager,url,"/",host,"");
        CHECK(missing.status==404 && s_qlog_metadata[(s_qlog_pos+QLOG_MAX-1)%QLOG_MAX].status==404);
        snprintf(fixture_settings.block_images[0],sizeof(fixture_settings.block_images[0]),"response-fixture.webp");
    }
    configure(directory,0,1,1);
    snprintf(host,sizeof(host),"explicit-dedup-%u.fixture.invalid",family);
    struct receipt protected_first=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    struct receipt protected_second=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    image_is_complete(&protected_first); CHECK(protected_second.status==204 && protected_second.length==0);
    configure(directory,1,0,0);
    snprintf(host,sizeof(host),"explicit-policy-%u.fixture.invalid",family);
    struct receipt denied=request(&manager,url,"/same.png",host,"Accept: image/png\r\n"); CHECK(denied.status==204);
    if(!baseline) {
        check_categories(&manager,url,directory,family);
        check_domain_responses(&manager,url,directory,family);
    }
    mg_mgr_free(&manager);
}
int main(int argc,char **argv) {
    CHECK(argc==2 || argc==3); mg_log_set(MG_LL_NONE);
    fixture_settings.resource_dir=argv[1]; fixture_settings.block_image_count=1;
    snprintf(fixture_settings.block_images[0],sizeof(fixture_settings.block_images[0]),"response-fixture.webp");
    char image_path[PATH_MAX]; snprintf(image_path,sizeof(image_path),"%s/response-fixture.webp",argv[1]);
    FILE *image=fopen(image_path,"rb"); CHECK(image);
    unsigned char *image_bytes=(unsigned char *)malloc(128u*1024u); CHECK(image_bytes);
    expected_image_length=fread(image_bytes,1,128u*1024u,image); CHECK(feof(image) && expected_image_length>12);
    bytes_hash(image_bytes,expected_image_length,expected_image_hash); free(image_bytes); CHECK(fclose(image)==0);
    printf("image=%zu sha256=",expected_image_length);
    for(unsigned index=0;index<sizeof(expected_image_hash);index++) printf("%02x",(unsigned)expected_image_hash[index]);
    puts("");
    s_pin_resource_dir=argv[1];
    int baseline=argc==3 && !strcmp(argv[2],"--expect-baseline-bug");
    run_family(argv[1],"http://127.0.0.1:0",4,baseline);
    run_family(argv[1],"http://[::1]:0",6,baseline);
    if(!baseline) CHECK(s_f5_cache_hits==0);
    printf("PASS: production policy/file response real IPv4+IPv6, baseline=%d, cache_hits=%llu\n",baseline,(unsigned long long)s_f5_cache_hits);
    return 0;
}
'''


def generate(source):
    pre = source[source.index('struct webstats {'):source.index('/* Verbose (per-connection/request)')]
    gates_start = 'static char s_block_cfg_generation' if 'static char s_block_cfg_generation' in source else 'static bool cfg_images'
    gates = source[source.index(gates_start):source.index('/* Sum of every blocked_*')]
    f5 = source[source.index('#define F5_DEDUP_WINDOW_MS'):source.index('/* ============ F4')]
    helper = '\n'.join(blocks.block(source, marker) for marker in (
        'static bool uri_ends_with_ci(', 'static bool uri_contains_ci(', 'static bool jbool(',
        'static bool load_block_cfg(' if 'static bool load_block_cfg(' in source else 'static void load_block_cfg(', 'static bool deny_quick(',
        'static bool deny_status(', 'static bool deny_fast('))
    helper += '\n' + source[source.index('#define RULE_OVERRIDE_FILE'):source.index('/* 按覆盖策略回答')]
    if 'static struct f5_cache_entry *f5_cache_slot(' in source:
        helper += '\n' + blocks.block(source, 'static struct f5_cache_entry *f5_cache_slot(')
    if 'static void block_cfg_generation(' in source:
        helper = '#define FIXTURE_CONFIG_GENERATION 1\n' + blocks.block(source, 'static void block_cfg_generation(') + '\n' + blocks.block(source, 'static bool block_cfg_flags_valid(') + '\n' + helper
        helper += '\n' + blocks.block(source, 'static void reply_block_config_ack(')
    start = source.index('    uint64_t t; memcpy(&t, c->data, sizeof(t));', source.index('/* Random block image'))
    end = source.index('\n}', start)
    guard_start = source.index('    bool local_peer =', source.index('static void fn('))
    guard_end = source.index('    /* The management port', guard_start)
    guard = source[guard_start:guard_end].replace('la_loopback_peer(', 'fixture_loopback_peer(')
    tail = TAIL.replace('PLACEHOLDER_BODY', source[start:end]).replace('PROTECTED_PATH_BODY', guard)
    return PREAMBLE + pre + gates + f5 + helper + '\n' + blocks.block(source,
        'static void req_host_of(struct mg_http_message *hm, char *out, size_t cap) {') + '\n' + blocks.block(source,
        'static bool reply_blocked_by_type(') + '\n' + blocks.block(source, 'static void json_safe_copy(') + '\n#include "query_log_store.inc"\n' + tail


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(args.source.read_text(encoding='utf-8')), encoding='utf-8')
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'app/src/main/assets/img_01.webp',
                    args.output.with_name('response-fixture.webp'))
