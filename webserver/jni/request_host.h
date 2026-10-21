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

static inline bool request_host_parse(const char *value, size_t length,
                                      char *out, size_t capacity) {
    request_host_unknown(out, capacity);
    if (!out || !capacity || !value) return false;
    while (length && (*value == ' ' || *value == '\t')) { value++; length--; }
    while (length && (value[length-1] == ' ' || value[length-1] == '\t')) length--;
    if (!length || (length > capacity && length - capacity > 8)) return false;
    for (size_t i = 0; i < length; i++) {
        unsigned char ch = (unsigned char)value[i];
        if (ch <= 32 || ch == 127 || ch == '/' || ch == '\\' || ch == '@' ||
                ch == '?' || ch == '#' || ch == '"' || ch == '\'') return false;
        if (ch >= 128) {
            /* Preserve already valid UTF-8 without applying platform-specific
             * IDN normalization; arbitrary bytes must not invalidate log JSON. */
            unsigned remaining, code;
            if (ch >= 0xc2 && ch <= 0xdf) { remaining = 1; code = ch & 0x1fu; }
            else if (ch >= 0xe0 && ch <= 0xef) { remaining = 2; code = ch & 0x0fu; }
            else if (ch >= 0xf0 && ch <= 0xf4) { remaining = 3; code = ch & 7u; }
            else return false;
            if (length - i - 1 < remaining) return false;
            for (unsigned next = 1; next <= remaining; next++) {
                unsigned char part = (unsigned char)value[i + next];
                if (part < 0x80 || part > 0xbf) return false;
                code = (code << 6) | (part & 0x3fu);
            }
            if ((remaining == 2 && code < 0x800) || (remaining == 3 && code < 0x10000) ||
                    (code >= 0x80 && code <= 0x9f) ||
                    (code >= 0xd800 && code <= 0xdfff) || code > 0x10ffff) return false;
            i += remaining;
        }
    }
    size_t start = 0, end = length, port = length;
    if (value[0] == '[') {
        start = 1;
        end = start;
        bool colon = false;
        while (end < length && value[end] != ']') {
            if (value[end] == ':' ) colon = true;
            if (value[end] == '[') return false;
            end++;
        }
        if (!colon || end == start || end == length) return false;
        port = end + 1;
        if (port < length) {
            if (value[port] != ':') return false;
            port++;
            if (port == length) return false;
        }
    } else {
        for (size_t i = 0; i < length; i++) {
            if (value[i] == '[' || value[i] == ']') return false;
            if (value[i] == ':') {
                if (end != length) return false; /* IPv6 requires brackets. */
                end = i;
                port = i + 1;
                if (port == length) return false;
            }
        }
    }
    if (end <= start || end - start >= capacity) return false;
    if (port < length) {
        unsigned number = 0;
        for (size_t i = port; i < length; i++) {
            if (value[i] < '0' || value[i] > '9') return false;
            number = number * 10 + (unsigned)(value[i] - '0');
            if (number > 65535) return false;
        }
        if (!number) return false;
    }
    memcpy(out, value + start, end - start);
    out[end - start] = 0;
    return true;
}
#endif
