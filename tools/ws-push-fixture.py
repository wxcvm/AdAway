"""Compile the actual generated WebSocket scheduling functions with small stubs."""
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index("static struct mg_connection *ws_clients[WS_PUSH_MAX]")
end = source.index(";", source.index("static uint64_t s_ws_last_push_ms", start)) + 1
state = source[start:end]
start = source.index("static void ws_push_broadcast(")
end = source.index("/*\n * Build the /internal-stats JSON", start)
functions = source[start:end]
prefix = r'''
#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <assert.h>
#define WS_PUSH_MAX 16
#define STATS_JSON_BUF 128
#define WEBSOCKET_OP_TEXT 1
struct settings { bool init; };
struct mg_connection { bool is_closing, is_draining; struct { size_t len; } send; };
static unsigned built, sent;
static size_t forced_result = (size_t)-1;
static int build_stats_json(struct settings *s, char *out, size_t cap) {
    (void)s; (void)out; (void)cap; ++built; return 8;
}
static size_t mg_ws_send(struct mg_connection *c, const void *p, size_t n, int op) {
    (void)p; (void)op; ++sent;
    size_t result = forced_result == (size_t)-1 ? n + 2 : forced_result;
    c->send.len += result; return result;
}
'''
suffix = r'''
int main(void) {
    struct settings s = {true};
    struct mg_connection fast = {0}, slow = {0};
    ws_push_broadcast(&s); ws_push_flush(&s, 100);
    assert(built == 0 && sent == 0);
    ws_clients[0] = &fast; ws_clients[1] = &slow; slow.send.len = 8;
    for (int i = 0; i < 500; ++i) ws_push_broadcast(&s);
    ws_push_flush(&s, 100);
    assert(built == 1 && sent == 1 && s_ws_pending == 2);
    ws_push_broadcast(&s);
    fast.send.len = 0;
    ws_push_flush(&s, 1099);
    assert(built == 1 && sent == 1);
    ws_push_flush(&s, 1100);
    assert(built == 2 && sent == 2 && s_ws_pending == 2 && slow.send.len == 8);
    ws_push_flush(&s, 2100);
    assert(built == 2 && sent == 2);
    slow.send.len = 0;
    ws_push_flush(&s, 2101);
    assert(built == 3 && sent == 3 && s_ws_pending == 0);
    ws_push_flush(&s, 4000);
    assert(built == 3 && sent == 3);
    fast.send.len = 0; ws_clients[1] = NULL;
    ws_push_broadcast(&s);
    forced_result = 0;
    ws_push_flush(&s, 4100);
    assert(!fast.is_closing && s_ws_pending == 1);
    forced_result = 2;
    ws_push_flush(&s, 5100);
    assert(fast.is_closing);
    struct mg_connection initial = {0};
    forced_result = 10;
    assert(ws_send_snapshot(&initial, "snapshot", 8) && !initial.is_closing);
    forced_result = 2;
    assert(!ws_send_snapshot(&initial, "snapshot", 8) && initial.is_closing);
    initial.is_closing = false;
    forced_result = 0;
    assert(!ws_send_snapshot(&initial, "snapshot", 8) && !initial.is_closing);
    puts("PASS: coalescing, slow client, trailing delivery and partial frame handling");
    return 0;
}
'''
pathlib.Path(sys.argv[2]).write_text(prefix + state + "\n" + functions + suffix, encoding="utf-8")
