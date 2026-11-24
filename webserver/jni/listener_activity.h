#ifndef ADAWAY_LISTENER_ACTIVITY_H
#define ADAWAY_LISTENER_ACTIVITY_H
#include <stdbool.h>
#include <stdint.h>
#include <string.h>

/* Include after mongoose.h. The first nine bytes are the existing
 * timestamp and admitted-connection flag; request accounting is separate. */
static inline void listener_touch(struct mg_connection *c, uint64_t now) {
    memcpy(c->data, &now, sizeof(now));
}
static inline bool listener_idle_due(const struct mg_connection *c,
                                     uint64_t now, uint64_t timeout) {
    if (!c->data[sizeof(uint64_t)] || c->is_websocket ||
        c->is_closing || c->is_draining) return false;
    uint64_t last;
    memcpy(&last, c->data, sizeof(last));
    return now >= last && now - last > timeout;
}
/* Release oversized empty buffers after five seconds of inactivity. */
static inline void listener_trim_idle(struct mg_connection *c, uint64_t now) {
    if (!c->data[sizeof(uint64_t)] || c->is_closing || c->is_draining ||
        c->is_tls_hs || c->send.len || c->recv.len || c->rtls.len) return;
    if (c->send.size < 65536 && c->recv.size < 65536) return;
    uint64_t last;
    memcpy(&last, c->data, sizeof(last));
    if (now < last || now - last < 5000) return;
    if (c->send.size >= 65536) mg_iobuf_free(&c->send);
    if (c->recv.size >= 65536) mg_iobuf_free(&c->recv);
}
#endif
