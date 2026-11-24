#include <stdio.h>
#include "mongoose/mongoose.h"
#include "listener_activity.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main(void) {
    struct mg_connection c = {0};
    c.data[sizeof(uint64_t)] = 1;
    listener_touch(&c, 100);
    CHECK(mg_iobuf_init(&c.send, 65536, 512));
    CHECK(mg_iobuf_init(&c.recv, 131072, 512));
    CHECK(mg_iobuf_init(&c.rtls, 65536, 512));
    listener_trim_idle(&c, 5099);
    CHECK(c.send.size == 65536 && c.recv.size == 131072);
    c.send.len = 1;
    listener_trim_idle(&c, 5100);
    CHECK(c.send.size == 65536 && c.recv.size == 131072);
    c.send.len = 0; c.recv.len = 1;
    listener_trim_idle(&c, 5100);
    CHECK(c.recv.size == 131072);
    c.recv.len = 0; c.rtls.len = 1;
    listener_trim_idle(&c, 5100);
    CHECK(c.send.size == 65536);
    c.rtls.len = 0; c.is_tls_hs = 1;
    listener_trim_idle(&c, 5100);
    CHECK(c.send.size == 65536);
    c.is_tls_hs = 0;
    listener_trim_idle(&c, 5100);
    CHECK(c.send.size == 0 && c.recv.size == 0 && c.rtls.size == 65536);
    CHECK(mg_iobuf_add(&c.send, 0, "ok", 2) == 2);
    CHECK(c.send.len == 2 && memcmp(c.send.buf, "ok", 2) == 0);
    c.send.len = 0;
    listener_trim_idle(&c, 10000);
    CHECK(c.send.size == 512);
    mg_iobuf_free(&c.send); mg_iobuf_free(&c.recv); mg_iobuf_free(&c.rtls);
    puts("PASS: idle large buffers released, pending data and TLS preserved, buffers reusable");
    return 0;
}
