"""Extract production response functions for real private dual-stack HTTP tests.

Only UID attribution, bookkeeping and disabled domain overrides are fixtures.
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
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#ifdef _WIN32
typedef unsigned int uid_t;
#define strcasecmp _stricmp
#endif
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); exit(1); } } while (0)
#define LOG_WARN(...) ((void)0)
#define CORS_HDR "Access-Control-Allow-Origin: *\r\n"
struct settings { const char *resource_dir; int block_image_count; char block_images[1][128]; };
static const char *s_pin_resource_dir;
static uid_t conn_load_uid(struct mg_connection *c) { (void)c; return 42; }
static int rule_override_status(const char *host, const char *dir) { (void)host; (void)dir; return 0; }
enum { HIST_BLOCKED, QLOG_BLOCK };
static void hist_add(int kind) { (void)kind; }
struct appstat { uint64_t blocked; };
static struct appstat fixture_app;
static struct appstat *app_find_or_add(uid_t uid) { (void)uid; return &fixture_app; }
static void top_host_add(const char *host) { (void)host; }
static void app_note_host(struct appstat *app, const char *host) { (void)app; (void)host; }
static void qlog_add(uid_t uid,int action,struct mg_http_message *hm,const struct mg_connection *c) { (void)uid; (void)action; (void)hm; (void)c; }
static void ws_push_broadcast(struct settings *s) { (void)s; }
'''

TAIL = r'''
static struct settings fixture_settings;
static unsigned server_family;
static void fixture_server(struct mg_connection *c,int event,void *data) {
    if (event==MG_EV_HTTP_MSG) {
        struct mg_http_message *hm=(struct mg_http_message *)data;
        struct settings *s=&fixture_settings;
        uint64_t tick=mg_millis(); memcpy(c->data,&tick,sizeof(tick));
        server_family=c->rem.is_ip6 ? 6u : 4u;
        if (!reply_blocked_by_type(c,hm)) {
            PLACEHOLDER_BODY
        }
    }
}
static size_t expected_image_length;
static unsigned char expected_image_hash[32];
static void bytes_hash(const unsigned char *bytes,size_t length,unsigned char hash[32]) {
    mg_sha256_ctx context;
    mg_sha256_init(&context); mg_sha256_update(&context,bytes,length); mg_sha256_final(hash,&context);
}
struct receipt { int done,status; char request[512],type[128],etag[128]; unsigned char prefix[12],hash[32]; size_t length; };
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
static void run_family(const char *directory,const char *binding,unsigned family,int baseline) {
    struct mg_mgr manager; mg_mgr_init(&manager);
    struct mg_connection *listener=mg_http_listen(&manager,binding,fixture_server,NULL); CHECK(listener);
    char url[128]; mg_snprintf(url,sizeof(url),"%s:%c%c%M","http",'/','/',mg_print_ip_port,&listener->loc);
    char host[96]; snprintf(host,sizeof(host),"repeat-%u.fixture.invalid",family);
    configure(directory,1,0,1);
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
    }
    configure(directory,0,1,1);
    snprintf(host,sizeof(host),"explicit-dedup-%u.fixture.invalid",family);
    struct receipt protected_first=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    struct receipt protected_second=request(&manager,url,"/same.png",host,"Accept: image/png\r\n");
    image_is_complete(&protected_first); CHECK(protected_second.status==204 && protected_second.length==0);
    configure(directory,1,0,0);
    snprintf(host,sizeof(host),"explicit-policy-%u.fixture.invalid",family);
    struct receipt denied=request(&manager,url,"/same.png",host,"Accept: image/png\r\n"); CHECK(denied.status==204);
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
    gates = source[source.index('static bool cfg_images'):source.index('/* Sum of every blocked_*')]
    f5 = source[source.index('#define F5_DEDUP_WINDOW_MS'):source.index('/* ============ F4')]
    helper = '\n'.join(blocks.block(source, marker) for marker in (
        'static bool uri_ends_with_ci(', 'static bool uri_contains_ci(', 'static bool jbool(',
        'static void load_block_cfg(', 'static bool deny_quick(',
        'static bool deny_status(', 'static bool deny_fast('))
    if 'static struct f5_cache_entry *f5_cache_slot(' in source:
        helper += '\n' + blocks.block(source, 'static struct f5_cache_entry *f5_cache_slot(')
    start = source.index('    uint64_t t; memcpy(&t, c->data, sizeof(t));', source.index('/* Random block image'))
    end = source.index('    mg_http_serve_file(c, hm, img_path, &o);', start) + len('    mg_http_serve_file(c, hm, img_path, &o);')
    tail = TAIL.replace('PLACEHOLDER_BODY', source[start:end])
    return PREAMBLE + pre + gates + f5 + helper + '\n' + blocks.block(source,
        'static void req_host_of(struct mg_http_message *hm, char *out, size_t cap) {') + '\n' + blocks.block(source,
        'static bool reply_blocked_by_type(') + '\n' + tail


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(args.source.read_text(encoding='utf-8')), encoding='utf-8')
    shutil.copyfile(Path(__file__).resolve().parents[1] / 'app/src/main/assets/img_01.webp',
                    args.output.with_name('response-fixture.webp'))
