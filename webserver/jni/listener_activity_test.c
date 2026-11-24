#include <stdio.h>
#include "mongoose/mongoose.h"
#include "listener_activity.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
int main(void) {
    struct mg_connection c = {0};
    const uint64_t timeout = 30000;
    listener_touch(&c, 100);
    CHECK(!listener_idle_due(&c, 40000, timeout)); /* not admitted */
    c.data[sizeof(uint64_t)] = 1;
    CHECK(!listener_idle_due(&c, 30100, timeout));
    /* Same scratch byte as the production HTTP request-accounting flag.
       It must not grant a WebSocket idle exemption. */
    c.data[sizeof(uint64_t) + 1 + sizeof(uint32_t) + 1] = 1;
    CHECK(listener_idle_due(&c, 30101, timeout));
    c.is_websocket = 1;
    CHECK(!listener_idle_due(&c, 90000, timeout));
    c.is_websocket = 0;
    listener_touch(&c, 30000); /* complete request or successful write */
    CHECK(!listener_idle_due(&c, 40000, timeout));
    CHECK(listener_idle_due(&c, 60001, timeout));
    CHECK(!listener_idle_due(&c, 20000, timeout)); /* clock reset */
    c.is_draining = 1;
    CHECK(!listener_idle_due(&c, 90000, timeout));
    c.is_draining = 0; c.is_closing = 1;
    CHECK(!listener_idle_due(&c, 90000, timeout));
    puts("PASS: admission, HTTP accounting, WebSocket, activity and idle boundary");
    return 0;
}
