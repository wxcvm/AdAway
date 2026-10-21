"""Compile the actual production array-rendering blocks with bounded fixtures.

Only process/network/global inputs are fixtures. Rendering comes verbatim from
the supplied final Android source or Windows count-checked generated core.
"""
import argparse
from pathlib import Path


def block(source, marker):
    start = source.index(marker)
    if source.count(marker) != 1:
        raise ValueError("Ambiguous source marker: " + marker)
    cursor = source.index("{", start)
    depth = 0
    while cursor < len(source):
        char = source[cursor]
        if source.startswith("/*", cursor):
            cursor = source.index("*/", cursor + 2) + 2
            continue
        if source.startswith("//", cursor):
            cursor = source.index("\n", cursor + 2)
            continue
        if char in "\"'":
            quote = char
            cursor += 1
            while source[cursor] != quote:
                cursor += 2 if source[cursor] == "\\" else 1
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if not depth:
                return source[start:cursor+1]
        cursor += 1
    raise ValueError("Unclosed source block")


PREAMBLE = r'''
#include "mongoose/mongoose.h"
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); exit(1); } } while(0)
#define APP_HOSTS_MAX 3
#define TOP_HOSTS_MAX 32
#define RECENT_TLS_MAX 64
struct app_record {
    uint32_t uid, tps, top_host_counts[APP_HOSTS_MAX];
    uint64_t connections, requests, blocked, tls_hosts, tls_ok, tls_fail;
    char top_hosts[APP_HOSTS_MAX][64];
};
struct top_host { char host[160]; unsigned long count; };
struct tls_host_rec { uint32_t uid; char host[192]; };
struct listener { char name[32], addr[96]; bool bound, ipv6, loopback; };
static struct app_record s_apps[64];
static struct top_host s_top_hosts[TOP_HOSTS_MAX];
static struct tls_host_rec s_recent_tls[RECENT_TLS_MAX];
static struct listener s_listeners[14];
static int s_app_count, s_top_count, s_tls_count, s_tls_pos, s_listener_count;
static bool fixture_special_metadata;
static bool pin_uid_bypassed(uint32_t uid) { return uid % 2 == 0; }
#ifdef _WIN32
static void win32_process_name(DWORD pid, char *out, size_t cap) {
    (void)pid;
    memset(out,'p',cap-1); out[cap-1]=0;
}
#endif
'''

TAIL = r'''
static void fill(char *out, size_t cap, unsigned row) {
    memset(out, 'a' + (int)(row % 26), cap - 1); out[cap - 1] = 0;
    /* Actual request metadata may contain quotes/control/backslash. */
    if (fixture_special_metadata) { out[3]='"'; out[6]='\\'; out[9]=1; }
}
int main(void) {
    mg_log_set(MG_LL_NONE);
    for (unsigned metadata=0; metadata<2; metadata++) {
    fixture_special_metadata=metadata!=0;
    for (unsigned population=0; population<=64; population++) {
        s_app_count=(int)population;
        s_top_count=population<TOP_HOSTS_MAX ? (int)population : TOP_HOSTS_MAX;
        s_tls_count=population<RECENT_TLS_MAX ? (int)population : RECENT_TLS_MAX;
        s_tls_pos=s_tls_count%RECENT_TLS_MAX;
        s_listener_count=population<14 ? (int)population : 14;
        for (unsigned i=0;i<population;i++) {
            s_apps[i].uid=i;
            s_apps[i].connections=s_apps[i].requests=s_apps[i].blocked=UINT64_MAX;
            s_apps[i].tls_hosts=s_apps[i].tls_ok=s_apps[i].tls_fail=UINT64_MAX;
            s_apps[i].tps=UINT32_MAX;
            for (unsigned h=0;h<APP_HOSTS_MAX;h++) {
                fill(s_apps[i].top_hosts[h],sizeof(s_apps[i].top_hosts[h]),i+h);
                s_apps[i].top_host_counts[h]=UINT32_MAX;
            }
        }
        for (int i=0;i<s_top_count;i++) { fill(s_top_hosts[i].host,sizeof(s_top_hosts[i].host),(unsigned)i); s_top_hosts[i].count=ULONG_MAX; }
        for (int i=0;i<s_tls_count;i++) { fill(s_recent_tls[i].host,sizeof(s_recent_tls[i].host),(unsigned)i); s_recent_tls[i].uid=UINT32_MAX; }
        for (int i=0;i<s_listener_count;i++) {
            /* Listener names/addresses are normalized by the existing listener configuration. */
            memset(s_listeners[i].name,'n',sizeof(s_listeners[i].name)-1);
            memset(s_listeners[i].addr,'a',sizeof(s_listeners[i].addr)-1);
        }
        render();
    }
    }
    puts("PASS: 130 production stats-array scenarios, long metadata, escaping and fixed byte limits");
    return 0;
}
'''


def generate(source):
    function = block(source, "static int build_stats_json(struct settings *s, char *out, size_t out_sz) {")
    apps = block(function, "for (int i = 0; i < s_app_count && off < (int)sizeof(apps_json) - 400; i++) {")
    top = block(function, 'char top_json[2048] = "";')
    tls = function[function.index('char tls_json[2048] = "";'):function.index('char hist_json[4096] = "";')]
    listeners = block(function, 'char listeners_json[1024] = "";') if "char listeners_json[" in function else 'char listeners_json[1024] = "";'
    if "char listeners_json[" not in function:
        # Android's stats document has no listener array at this point.
        listeners += "\n(void)s_listeners; (void)s_listener_count;"
    render = '''
static void render(void) {
    char apps_json[4096] = "";
    int off=0;
''' + apps + "\n" + top + "\n" + tls + "\n" + listeners + r'''
    char out[16384];
    int n=snprintf(out,sizeof(out),"{\"apps\":[%s],\"top\":[%s],\"tls\":[%s],\"listeners\":[%s]}",apps_json,top_json,tls_json,listeners_json);
    CHECK(n>0 && (size_t)n<sizeof(out));
    struct mg_str json=mg_str(out);
    int length=0;
    CHECK(mg_json_get(json,"$",&length)==0 && length==n);
    struct mg_str apps_array=mg_json_get_tok(json,"$.apps"), key, row;
    size_t offset=0; unsigned rows=0;
    while ((offset=mg_json_next(apps_array,offset,&key,&row))!=0) {
        CHECK(mg_json_get_tok(row,"$.pin_refused").len>0);
        struct mg_str hosts=mg_json_get_tok(row,"$.hosts");
        size_t host_offset=0; unsigned host_count=0;
        while ((host_offset=mg_json_next(hosts,host_offset,&key,&row))!=0) host_count++;
        CHECK(host_count==APP_HOSTS_MAX);
        rows++;
    }
    CHECK(rows<=(unsigned)s_app_count && (!s_app_count || rows>0));
}
'''
    return PREAMBLE + block(source, "static void json_safe_copy(") + render + TAIL


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.write_text(generate(args.source.read_text(encoding="utf-8")), encoding="utf-8", newline="\n")
