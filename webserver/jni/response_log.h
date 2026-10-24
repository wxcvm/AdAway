/* Bounded, privacy-preserving metadata for one locally observed HTTP request. */
#ifndef ADAWAY_RESPONSE_LOG_H
#define ADAWAY_RESPONSE_LOG_H
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* 0 means no local response was observed (for example an asynchronous proxy). */
static inline uint16_t response_log_status(const void *buffer, size_t length, size_t offset) {
    if (!buffer || offset > length || length - offset < 13) return 0;
    const unsigned char *line = (const unsigned char *)buffer + offset;
    if (memcmp(line, "HTTP/1.", 7) || (line[7] != '0' && line[7] != '1') || line[8] != ' ' ||
        line[9] < '1' || line[9] > '5' || line[10] < '0' || line[10] > '9' ||
        line[11] < '0' || line[11] > '9' || line[12] != ' ') return 0;
    return (uint16_t)((line[9] - '0') * 100 + (line[10] - '0') * 10 + line[11] - '0');
}

static inline size_t response_log_utf8_width(unsigned first) {
    if (first >= 0xc2 && first <= 0xdf) return 2;
    if (first >= 0xe0 && first <= 0xef) return 3;
    if (first >= 0xf0 && first <= 0xf4) return 4;
    return 0;
}

/* Returns zero for controls, overlong sequences, surrogates and invalid UTF-8. */
static inline size_t response_log_utf8(const unsigned char *s, size_t length) {
    if (!length) return 0;
    unsigned first = s[0];
    if (first < 0x80) return first >= 0x20 && first != 0x7f ? 1 : 0;
    size_t width = response_log_utf8_width(first);
    if (!width || width > length) return 0;
    for (size_t i = 1; i < width; i++) if ((s[i] & 0xc0) != 0x80) return 0;
    if (first == 0xc2 && s[1] < 0xa0) return 0; /* Unicode C1 controls. */
    if ((first == 0xe0 && s[1] < 0xa0) || (first == 0xed && s[1] >= 0xa0) ||
        (first == 0xf0 && s[1] < 0x90) || (first == 0xf4 && s[1] >= 0x90)) return 0;
    return width;
}

#define RESPONSE_LOG_PATH_CAP 160
#define RESPONSE_LOG_TRUNCATED 1u
struct response_log_metadata {
    char path[RESPONSE_LOG_PATH_CAP];
    uint16_t status;
    uint8_t flags;
    uint8_t reserved;
};
_Static_assert(sizeof(struct response_log_metadata) == 164, "Stable QLG3 metadata ABI");

/* Store only origin-form paths. Query and fragment are never retained. Invalid
   bytes make the entire path unknown, including invalid bytes beyond the cap. */
static inline void response_log_path(struct response_log_metadata *out, const char *uri, size_t length) {
    size_t written = 0;
    out->path[0] = 0;
    out->flags = 0;
    if (!uri || !length || uri[0] != '/') return;
    for (size_t i = 0; i < length && uri[i] != '?' && uri[i] != '#';) {
        size_t width = response_log_utf8((const unsigned char *)uri + i, length - i);
        if (!width) { out->path[0] = 0; out->flags = 0; return; }
        if (!(out->flags & RESPONSE_LOG_TRUNCATED) && written + width < sizeof(out->path)) {
            memcpy(out->path + written, uri + i, width);
            written += width;
        } else out->flags |= RESPONSE_LOG_TRUNCATED;
        i += width;
    }
    out->path[written] = 0;
}
#endif
