#ifndef ADAWAY_DIAGNOSTIC_DNS_REPLY_H
#define ADAWAY_DIAGNOSTIC_DNS_REPLY_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* Fixed example.com IN A diagnostic only. No allocation, sockets, routes or cache. */
#define DDNS_MAX 512u
#define DDNS_QUERY_SIZE 29u
enum ddns_reply { DDNS_REJECT = 0, DDNS_ACCEPT = 1, DDNS_RETRY_TCP = 2 };
static const uint8_t ddns_question[] = {7,'e','x','a','m','p','l','e',3,'c','o','m',0,0,1,0,1};
static inline uint16_t ddns_u16(const uint8_t *b, size_t at) {
    return (uint16_t)(((uint16_t)b[at] << 8) | b[at + 1]);
}
static inline void ddns_query(uint8_t out[DDNS_QUERY_SIZE], uint16_t id) {
    memset(out, 0, DDNS_QUERY_SIZE); out[0] = (uint8_t)(id >> 8); out[1] = (uint8_t)id;
    out[2] = 1; out[5] = 1; memcpy(out + 12, ddns_question, sizeof(ddns_question));
}
static inline int ddns_allowed(const uint8_t *q, size_t n) {
    if (!q || n != DDNS_QUERY_SIZE || q[2] != 1 || q[3] || q[4] || q[5] != 1) return 0;
    for (size_t i = 6; i < 12; i++) if (q[i]) return 0;
    return memcmp(q + 12, ddns_question, sizeof(ddns_question)) == 0;
}
static inline size_t ddns_name_end(const uint8_t *b, size_t n, size_t at) {
    uint8_t seen[DDNS_MAX] = {0}; size_t end = 0, expanded = 1;
    while (at < n) {
        if (seen[at]) return 0;
        seen[at] = 1;
        unsigned len = b[at];
        if (!len) return end ? end : at + 1;
        if ((len & 192u) == 192u) {
            if (at + 1 >= n) return 0;
            if (!end) end = at + 2;
            at = ((len & 63u) << 8) | b[at + 1];
        } else {
            if ((len & 192u) || len + 1 >= n - at) return 0;
            expanded += len + 1;
            if (expanded > 255) return 0;
            at += len + 1;
        }
    }
    return 0;
}
static inline int ddns_records(const uint8_t *b, size_t n) {
    size_t at = DDNS_QUERY_SIZE;
    unsigned count = (unsigned)ddns_u16(b, 6) + ddns_u16(b, 8) + ddns_u16(b, 10);
    for (unsigned i = 0; i < count; i++) {
        at = ddns_name_end(b, n, at);
        if (!at || n - at < 10) return 0;
        size_t length = ddns_u16(b, at + 8);
        at += 10;
        if (length > n - at) return 0;
        at += length;
    }
    return at == n;
}
static inline enum ddns_reply ddns_classify(const uint8_t *q, size_t qn, const uint8_t *r, size_t rn) {
    if (!ddns_allowed(q, qn) || !r || rn < DDNS_QUERY_SIZE || rn > DDNS_MAX
            || q[0] != r[0] || q[1] != r[1] || !(r[2] & 128u) || (r[2] & 120u)
            || r[4] || r[5] != 1 || memcmp(r + 12, ddns_question, sizeof(ddns_question))) return DDNS_REJECT;
    /* TC may mean incomplete RR bytes: validate identity/question, retry, never return this body. */
    if (r[2] & 2u) return DDNS_RETRY_TCP;
    return ddns_records(r, rn) ? DDNS_ACCEPT : DDNS_REJECT;
}
#endif
