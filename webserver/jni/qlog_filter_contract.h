/* Versioned, bounded history filtering; not part of the request hot path. */
#ifndef ADAWAY_QLOG_FILTER_CONTRACT_H
#define ADAWAY_QLOG_FILTER_CONTRACT_H
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define QLF_QUERY_CAP 192
struct qlog_filter {
    bool has_action, has_family, has_type, has_uid;
    int action, family, type, uid;
    char query[QLF_QUERY_CAP];
    char key[65];
};

static inline int qlf_hex(unsigned char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

/* Unlike form helpers this rejects embedded NUL, malformed escapes and any
 * overflow, never accepting a decoded prefix as the complete condition. */
static inline bool qlf_decode(const char *input, size_t size, char *out, size_t cap) {
    size_t n = 0;
    for (size_t i = 0; i < size; i++) {
        unsigned char c = (unsigned char)input[i];
        if (c == '%') {
            if (size - i < 3) return false;
            int hi = qlf_hex((unsigned char)input[i+1]), lo = qlf_hex((unsigned char)input[i+2]);
            if (hi < 0 || lo < 0) return false;
            c = (unsigned char)(hi * 16 + lo); i += 2;
        } else if (c == '+') c = ' ';
        if (!c || n + 1 >= cap) return false;
        out[n++] = (char)c;
    }
    if (!cap) return false;
    out[n] = 0;
    return true;
}

/* 0 absent, 1 present, -1 duplicate/invalid. Names are decoded as well, so
 * action=1&%61ction=2 cannot acquire platform-dependent interpretations. */
static inline int qlf_parameter(struct mg_str query, const char *name, char *out, size_t cap) {
    int found = 0;
    size_t start = 0;
    if (cap) out[0] = 0;
    while (start < query.len) {
        size_t end = start, equal;
        while (end < query.len && query.buf[end] != '&') end++;
        equal = start;
        while (equal < end && query.buf[equal] != '=') equal++;
        char key[32];
        if (qlf_decode(query.buf + start, equal - start, key, sizeof(key)) && !strcmp(key, name)) {
            if (found || equal == end || !qlf_decode(query.buf + equal + 1, end - equal - 1, out, cap)) return -1;
            found = 1;
        }
        start = end + 1;
    }
    return found;
}

static inline bool qlf_integer(const char *s, int maximum, bool unknown, int *value) {
    if (unknown && !strcmp(s, "-1")) { *value = -1; return true; }
    if (!*s) return false;
    unsigned n = 0;
    for (size_t i = 0; s[i]; i++) {
        if (s[i] < '0' || s[i] > '9') return false;
        unsigned digit = (unsigned)(s[i] - '0');
        if (n > (unsigned)maximum / 10 || (n == (unsigned)maximum / 10 && digit > (unsigned)maximum % 10)) return false;
        n = n * 10 + digit;
    }
    *value = (int)n;
    return true;
}

static inline unsigned char qlf_fold(unsigned char c) {
    return c >= 'A' && c <= 'Z' ? (unsigned char)(c + ('a' - 'A')) : c;
}

static inline bool qlf_contains(const char *text, const char *needle) {
    size_t size = strlen(text), length = strlen(needle);
    if (length > size) return false;
    for (size_t start = 0; start <= size - length; start++) {
        size_t i = 0;
        while (i < length && qlf_fold((unsigned char)text[start+i]) == (unsigned char)needle[i]) i++;
        if (i == length) return true;
    }
    return false;
}

static inline bool qlf_lower_hex(const char *s, size_t length) {
    if (strlen(s) != length) return false;
    for (size_t i = 0; i < length; i++) if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return false;
    return true;
}

static inline void qlf_key(struct qlog_filter *f) {
    char action[16] = "*", family[16] = "*", type[16] = "*", uid[16] = "*", canonical[384];
    if (f->has_action) snprintf(action, sizeof(action), "%d", f->action);
    if (f->has_family) snprintf(family, sizeof(family), "%d", f->family);
    if (f->has_type) snprintf(type, sizeof(type), "%d", f->type);
    if (f->has_uid) snprintf(uid, sizeof(uid), "%d", f->uid);
    int length = snprintf(canonical, sizeof(canonical), "qlog-filter-v1\naction=%s\nfamily=%s\ntype=%s\nuid=%s\nq=%s", action, family, type, uid, f->query);
    unsigned char digest[32];
    mg_sha256_ctx ctx;
    mg_sha256_init(&ctx);
    mg_sha256_update(&ctx, (const unsigned char *)canonical, (size_t)length);
    mg_sha256_final(digest, &ctx);
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < sizeof(digest); i++) { f->key[2*i] = hex[digest[i] >> 4]; f->key[2*i+1] = hex[digest[i] & 15]; }
    f->key[64] = 0;
}

/* 0 legacy, 1 v1, -1 invalid. New conditions never silently become an
 * unfiltered legacy request when filterVersion is missing or unsupported. */
static inline int qlf_parse(struct mg_str query, struct qlog_filter *f) {
    static const char *const names[] = {"action", "family", "type", "uid", "q"};
    char text[QLF_QUERY_CAP];
    memset(f, 0, sizeof(*f));
    int version = qlf_parameter(query, "filterVersion", text, sizeof(text));
    if (version < 0 || (version && strcmp(text, "1"))) return -1;
    for (size_t i = 0; i < sizeof(names)/sizeof(names[0]); i++) {
        int present = qlf_parameter(query, names[i], text, sizeof(text));
        if (present < 0 || (present && !version)) return -1;
        if (!present) continue;
        if (i == 0) { f->has_action = true; if (!qlf_integer(text, 3, false, &f->action)) return -1; }
        if (i == 1) { f->has_family = true; if (!qlf_integer(text, 6, false, &f->family) || (f->family != 0 && f->family != 4 && f->family != 6)) return -1; }
        if (i == 2) { f->has_type = true; if (!qlf_integer(text, 65534, true, &f->type)) return -1; }
        if (i == 3) { f->has_uid = true; if (!qlf_integer(text, INT_MAX, true, &f->uid)) return -1; }
        if (i == 4) {
            size_t length = strlen(text);
            for (size_t k = 0; k < length;) {
                size_t width = response_log_utf8((const unsigned char *)text+k, length-k);
                if (!width) return -1;
                k += width;
            }
            for (size_t k = 0; k <= length; k++) f->query[k] = (char)qlf_fold((unsigned char)text[k]);
        }
    }
    if (version) {
        static const char *const page_names[] = {"offset", "limit", "generation", "anchor", "queryKey"};
        for (size_t i = 0; i < sizeof(page_names)/sizeof(page_names[0]); i++)
            if (qlf_parameter(query, page_names[i], text, sizeof(text)) < 0) return -1;
        qlf_key(f);
    } else if (qlf_parameter(query, "queryKey", text, sizeof(text))) return -1;
    return version;
}
#endif
