"""Compile verbatim production accounting before its early-return branches.

This is a narrow counter/order regression, not a substitute for the packaged
native server, real forwarding, TLS or a device's UID attribution.
"""
import argparse
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location('production_blocks', Path(__file__).with_name('stats-json-bounds-fixture.py'))
blocks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(blocks)

PREAMBLE = r'''
#include "mongoose/mongoose.h"
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
typedef unsigned int uid_t;
#endif
#define CHECK(x) do { if (!(x)) { fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); exit(1); } } while (0)
#define APP_STATS_MAX 2
struct appstat { uid_t uid; uint64_t connections, requests; };
static struct appstat s_apps[APP_STATS_MAX];
static int s_app_count;
static struct { uint64_t total_connections, total_requests; } s_stats;
enum { HIST_CONN, HIST_REQ };
static uint64_t history[2];
static void hist_add(int event) { CHECK(event == HIST_CONN || event == HIST_REQ); history[event]++; }
'''

TAIL = r'''
static void reset(void) {
    memset(s_apps,0,sizeof(s_apps)); s_app_count=0;
    memset(&s_stats,0,sizeof(s_stats)); memset(history,0,sizeof(history));
}
static void record(struct mg_connection *c, const char *uri, uid_t req_uid) {
    struct mg_http_message request; memset(&request,0,sizeof(request)); request.uri=mg_str(uri);
    struct mg_http_message *hm=&request;
ACTUAL_ACCOUNTING
}
int main(void) {
    struct mg_connection c; memset(&c,0,sizeof(c)); reset();
    record(&c,"/one",42); record(&c,"/two",42); record(&c,"/three",42);
    CHECK(s_stats.total_requests==3 && s_stats.total_connections==1);
    CHECK(s_app_count==1 && s_apps[0].requests==3 && s_apps[0].connections==1);
    CHECK(history[HIST_REQ]==3 && history[HIST_CONN]==1);
    record(&c,"/internal-stats",42); record(&c,"/internal-qlog",42); record(&c,"/control",42);
    CHECK(s_stats.total_requests==3 && s_apps[0].requests==3);
    CHECK(history[HIST_REQ]==3 && history[HIST_CONN]==1);
    memset(&c,0,sizeof(c)); reset();
    record(&c,"/internal-stats",42); CHECK(!s_app_count && !s_stats.total_requests && !s_stats.total_connections);
    CHECK(c.data[CONN_REQ_OFFSET]==0 && c.data[REC_OFFSET]==0);
    record(&c,"/user",42); record(&c,"/control",42); record(&c,"/another-user",42);
    CHECK(s_stats.total_requests==2 && s_stats.total_connections==1);
    CHECK(s_apps[0].requests==2 && s_apps[0].connections==1);
    memset(&c,0,sizeof(c)); reset();
    record(&c,"/unknown",(uid_t)-1); record(&c,"/root",0);
    CHECK(s_stats.total_requests==2 && s_stats.total_connections==1 && !s_app_count);
    /* Late successful UID attribution can begin app accounting once; global
       connection observation is still the earlier, already counted event. */
    record(&c,"/known-now",42);
    CHECK(s_stats.total_requests==3 && s_stats.total_connections==1);
    CHECK(s_app_count==1 && s_apps[0].requests==1 && s_apps[0].connections==1);
    memset(&c,0,sizeof(c)); record(&c,"/another-app",43);
    memset(&c,0,sizeof(c)); record(&c,"/table-full",44);
    CHECK(s_app_count==APP_STATS_MAX && s_stats.total_requests==5 && s_stats.total_connections==3);
    CHECK(s_apps[0].requests==1 && s_apps[1].requests==1);
    puts("{\"passed\":true,\"verbatimProductionAccounting\":true,\"knownUidKeepalive\":true,\"internalExcluded\":true,\"unknownRootAndFullAppTable\":true,\"lateAttribution\":true,\"sourceOrderingBeforeEarlyReturns\":true,\"tlsAndDeviceNotExercised\":true}");
    return 0;
}
'''


def generate(source):
    function = blocks.block(source, 'static void fn(')
    start = function.index('    bool internal_path =')
    end = function.index('    /*\n     * TRANSPARENT FILTERING PROXY', start)
    accounting = function[start:end]
    if accounting.count('ra->requests++') != 1 or function.count('ra->requests++') != 1:
        raise ValueError('Expected exactly one app request accounting site')
    if accounting.count('s_stats.total_requests++') != 1 or function.count('s_stats.total_requests++') != 1:
        raise ValueError('Expected exactly one global request accounting site')
    if not function.index('if (ap_reply(c, hm)) return;') < start:
        raise ValueError('Diagnostic receipt must return before accounting')
    if not start < function.index('if (s->proxy_filter)', start) < function.index('if (domain_is_allowed(', start):
        raise ValueError('Accounting must precede proxy and local exemption returns')
    if 'if (!internal_path && c->is_tls && c->tls)' not in function[end:]:
        raise ValueError('Per-app SNI observations must exclude management traffic')
    macros = '\n'.join(line for line in source.splitlines()
                       if line.startswith(('#define UID_OFFSET ', '#define REC_OFFSET ', '#define CONN_REQ_OFFSET ')))
    allocator = blocks.block(source, 'static struct appstat *app_find_or_add(')
    return PREAMBLE + macros + '\n' + allocator + '\n' + TAIL.replace('ACTUAL_ACCOUNTING', accounting)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(generate(args.source.read_text(encoding='utf-8')), encoding='utf-8')
