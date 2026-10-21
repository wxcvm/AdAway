#ifndef ADAWAY_REQUEST_HOST_H
#define ADAWAY_REQUEST_HOST_H
#include <stdbool.h>
#include <stddef.h>
#include <string.h>

/* Observed HTTP authority only: no URI fallback, IDN conversion or resolution.
 * An unrepresentable/malformed name stays unknown instead of becoming a
 * truncated but different hostname. Brackets and numeric ports are removed. */
static inline void request_host_unknown(char *out, size_t capacity) {
    static const char unknown[] = "(unknown host)";
    if (!out || !capacity) return;
    size_t length = sizeof(unknown) - 1;
    if (length >= capacity) length = capacity - 1;
    memcpy(out, unknown, length);
    out[length] = 0;
}

static inline unsigned request_host_utf8_tail(unsigned char lead, unsigned *code) {
    if (lead >= 0xc2 && lead <= 0xdf) { *code = lead & 0x1fu; return 1; }
    if (lead >= 0xe0 && lead <= 0xef) { *code = lead & 0x0fu; return 2; }
    if (lead >= 0xf0 && lead <= 0xf4) { *code = lead & 7u; return 3; }
    return 0;
}

static inline bool request_host_scalar_valid(unsigned code, unsigned tail) {
    static const unsigned minimum[] = {0, 0x80, 0x800, 0x10000};
    return code >= minimum[tail] && code <= 0x10ffff &&
        (code < 0x80 || code > 0x9f) && (code < 0xd800 || code > 0xdfff);
}

/* Preserve valid UTF-8 without applying platform-specific IDN normalization. */
static inline size_t request_host_utf8_width(const char *value, size_t length) {
    if (!length) return 0;
    unsigned code = 0;
    unsigned tail = request_host_utf8_tail((unsigned char)value[0], &code);
    if (!tail || length - 1 < tail) return 0;
    for (unsigned next = 1; next <= tail; next++) {
        unsigned char part = (unsigned char)value[next];
        if (part < 0x80 || part > 0xbf) return 0;
        code = (code << 6) | (part & 0x3fu);
    }
    return request_host_scalar_valid(code, tail) ? (size_t)tail + 1 : 0;
}

static inline bool request_host_ascii_valid(unsigned char ch) {
    return ch > 32 && ch != 127 && strchr("/\\@?#\"'", ch) == NULL;
}

static inline bool request_host_text_valid(const char *value, size_t length) {
    size_t offset = 0;
    while (offset < length) {
        unsigned char ch = (unsigned char)value[offset];
        if (ch < 128 && !request_host_ascii_valid(ch)) return false;
        size_t width = ch < 128 ? 1 : request_host_utf8_width(value + offset, length - offset);
        if (!width) return false;
        offset += width;
    }
    return true;
}

struct request_host_parts { size_t start, end, port; };

static inline bool request_host_bracketed(const char *value, size_t length,
                                          struct request_host_parts *parts) {
    const char *close = memchr(value + 1, ']', length - 1);
    if (!close) return false;
    parts->start = 1;
    parts->end = (size_t)(close - value);
    size_t inner = parts->end - parts->start;
    if (!inner || !memchr(value + 1, ':', inner) || memchr(value + 1, '[', inner)) return false;
    parts->port = parts->end + 1;
    if (parts->port == length) return true;
    if (value[parts->port] != ':') return false;
    parts->port++;
    return parts->port < length;
}

static inline bool request_host_unbracketed(const char *value, size_t length,
                                            struct request_host_parts *parts) {
    if (memchr(value, '[', length) || memchr(value, ']', length)) return false;
    parts->start = 0;
    parts->end = parts->port = length;
    const char *colon = memchr(value, ':', length);
    if (!colon) return true;
    parts->end = (size_t)(colon - value);
    parts->port = parts->end + 1;
    /* A second colon is an unbracketed IPv6 authority, not a numeric port. */
    return parts->end > 0 && parts->port < length &&
        !memchr(value + parts->port, ':', length - parts->port);
}

static inline bool request_host_port_valid(const char *value, size_t length, size_t port) {
    if (port == length) return true;
    unsigned number = 0;
    for (size_t i = port; i < length; i++) {
        if (value[i] < '0' || value[i] > '9') return false;
        number = number * 10 + (unsigned)(value[i] - '0');
        if (number > 65535) return false;
    }
    return number != 0;
}

static inline bool request_host_blank(char ch) { return ch == ' ' || ch == '\t'; }

static inline bool request_host_parse(const char *value, size_t length,
                                      char *out, size_t capacity) {
    request_host_unknown(out, capacity);
    if (!out || !capacity || !value) return false;
    while (length && request_host_blank(*value)) { value++; length--; }
    while (length && request_host_blank(value[length-1])) length--;
    if (!length || (length > capacity && length - capacity > 8)) return false;
    if (!request_host_text_valid(value, length)) return false;
    struct request_host_parts parts;
    bool valid = value[0] == '[' ? request_host_bracketed(value, length, &parts) :
        request_host_unbracketed(value, length, &parts);
    if (!valid || !request_host_port_valid(value, length, parts.port)) return false;
    size_t name_length = parts.end - parts.start;
    if (name_length >= capacity) return false;
    memcpy(out, value + parts.start, name_length);
    out[name_length] = 0;
    return true;
}
#endif
