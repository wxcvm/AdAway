#ifndef ADAWAY_DOMAIN_RULE_SNAPSHOT_H
#define ADAWAY_DOMAIN_RULE_SNAPSHOT_H
/* Domain-only parsing and owned snapshots. Queries return values, never borrowed pointers. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#else
#include <arpa/inet.h>
#include <pthread.h>
#endif
#include "traffic_core_contract.h"

#define DR_MAX_LINE 4096u
#define DR_MAX_BYTES (64u * 1024u * 1024u)
#define DR_MAX_ENTRIES 100000u
enum dr_kind { DR_BLOCK, DR_ALLOW, DR_REPLACE };
enum dr_parse_status { DR_OK, DR_UNSUPPORTED, DR_COSMETIC, DR_INVALID };
struct dr_entry {
    char domain[254];
    enum dr_kind kind;
    bool suffix, user;
    int family;
    uint8_t address[16];
};
struct dr_snapshot { uint64_t generation; size_t count; struct dr_entry *entries; };
struct dr_store {
#ifdef _WIN32
    SRWLOCK lock;
#else
    pthread_mutex_t lock;
#endif
    struct dr_snapshot current;
};
#ifdef _WIN32
#define DR_STORE_INITIALIZER { SRWLOCK_INIT, {0, 0, NULL} }
static inline void dr_lock(struct dr_store *s) { AcquireSRWLockExclusive(&s->lock); }
static inline void dr_unlock(struct dr_store *s) { ReleaseSRWLockExclusive(&s->lock); }
#else
#define DR_STORE_INITIALIZER { PTHREAD_MUTEX_INITIALIZER, {0, 0, NULL} }
static inline void dr_lock(struct dr_store *s) { (void)pthread_mutex_lock(&s->lock); }
static inline void dr_unlock(struct dr_store *s) { (void)pthread_mutex_unlock(&s->lock); }
#endif
static inline bool dr_domain(const char *raw, char out[254], bool wildcard) {
    if (!raw || !out) return false;
    size_t n = strlen(raw), label = 0;
    if (n && raw[n - 1] == '.') n--;
    if (!n || n > 253) return false;
    for (size_t i = 0; i < n; i++) {
        char c = raw[i];
        if (c >= 'A' && c <= 'Z') c = (char)(c + 'a' - 'A');
        if (c == '.') { if (!label || label > 63) return false; label = 0; }
        else {
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    || (wildcard && (c == '*' || c == '?')))) return false;
            label++;
        }
        out[i] = c;
    }
    if (!label || label > 63) return false;
    out[n] = 0; return true;
}
typedef bool (*dr_sink)(const struct dr_entry *, void *);
static inline bool dr_hostname_valid(const char *raw) {
    char name[254]; if (!dr_domain(raw, name, false)) return false;
    const char *label = name; unsigned labels = 0;
    for (;;) {
        const char *dot = strchr(label, '.'); size_t n = dot ? (size_t)(dot - label) : strlen(label);
        if (++labels > 127 || label[0] == '_' || label[0] == '-' || label[n - 1] == '_' || label[n - 1] == '-') return false;
        if (!dot) return !(label[0] >= '0' && label[0] <= '9');
        label = dot + 1;
    }
}
static inline bool dr_import_domain(const char *raw, char out[254], bool wildcard) {
    if (!dr_domain(raw, out, wildcard)) return false;
    if (!wildcard) return dr_hostname_valid(out);
    char cleared[254], replaced[254]; size_t length = strlen(out), written = 0;
    for (size_t i = 0; i < length; i++) {
        bool wild = out[i] == '*' || out[i] == '?';
        replaced[i] = wild ? 'a' : out[i];
        if (!wild) cleared[written++] = out[i];
    }
    cleared[written] = 0; replaced[length] = 0;
    return dr_hostname_valid(cleared) || dr_hostname_valid(replaced);
}
static inline bool dr_ip(const char *text, int family, uint8_t address[16]) {
#ifdef _WIN32
    return InetPtonA(family == 4 ? AF_INET : AF_INET6, text, address) == 1;
#else
    return inet_pton(family == 4 ? AF_INET : AF_INET6, text, address) == 1;
#endif
}
static inline enum dr_parse_status dr_parse_line(const char *raw, bool allow, bool redirects,
                                                dr_sink sink, void *context) {
    if (!raw || !sink) return DR_INVALID;
    char buf[DR_MAX_LINE + 1];
    size_t n = strlen(raw);
    if (n > DR_MAX_LINE) return DR_INVALID;
    memcpy(buf, raw, n + 1);
    char *p = buf;
    while (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n') p++;
    n = strlen(p);
    while (n && (p[n - 1] == ' ' || p[n - 1] == '\t' || p[n - 1] == '\r' || p[n - 1] == '\n')) p[--n] = 0;
    if (!*p || *p == '!' || strncmp(p, "[Adblock", 8) == 0) return DR_OK;
    char first[64]; size_t first_length = strcspn(p, " \t\r\n"); uint8_t first_ip[16];
    bool hosts = false;
    if (first_length < sizeof(first)) {
        memcpy(first, p, first_length); first[first_length] = 0;
        hosts = dr_ip(first, 4, first_ip) || dr_ip(first, 6, first_ip);
    }
    if (!hosts && (strstr(p, "##") || strstr(p, "#@#") || strstr(p, "#$#"))) return DR_COSMETIC;
    if (*p == '#') return DR_OK;
    struct dr_entry entry;
    memset(&entry, 0, sizeof(entry));
    if (strncmp(p, "||", 2) == 0 || strncmp(p, "@@", 2) == 0) {
        bool exception = strncmp(p, "@@", 2) == 0;
        if (exception) p += 2;
        if (strncmp(p, "||", 2) != 0) return DR_UNSUPPORTED;
        p += 2; n = strlen(p);
        if (n && p[n - 1] == '^') p[--n] = 0;
        if (strpbrk(p, "^/$*?|#:")) return DR_UNSUPPORTED;
        if (!dr_import_domain(p, entry.domain, false) || !strchr(entry.domain, '.')) return DR_INVALID;
        entry.kind = exception || allow ? DR_ALLOW : DR_BLOCK;
        entry.suffix = true;
        return sink(&entry, context) ? DR_OK : DR_INVALID;
    }
    char *comment = strchr(p, '#');
    if (comment) *comment = 0;
    n = strlen(p);
    while (n && (p[n - 1] == ' ' || p[n - 1] == '\t')) p[--n] = 0;
    if (!*p) return DR_OK;
    char *space = strpbrk(p, " \t");
    if (!space) {
        if (!allow) {
            if (!dr_import_domain(p, entry.domain, false) || !strchr(entry.domain, '.') || strlen(entry.domain) < 4) return DR_UNSUPPORTED;
            entry.kind = DR_BLOCK; return sink(&entry, context) ? DR_OK : DR_INVALID;
        }
        if (!dr_import_domain(p, entry.domain, true)) return DR_INVALID;
        entry.kind = DR_ALLOW;
        return sink(&entry, context) ? DR_OK : DR_INVALID;
    }
    *space++ = 0;
    entry.family = dr_ip(p, 4, entry.address) ? 4 : dr_ip(p, 6, entry.address) ? 6 : 0;
    if (!entry.family) return DR_INVALID;
    uint8_t empty[16] = {0};
    bool zero = memcmp(entry.address, empty, entry.family == 4 ? 4u : 16u) == 0
            || strcmp(p, "127.0.0.1") == 0 || strcmp(p, "::1") == 0;
    entry.kind = allow ? DR_ALLOW : zero ? DR_BLOCK : DR_REPLACE;
    if (entry.kind != DR_REPLACE) { entry.family = 0; memset(entry.address, 0, sizeof(entry.address)); }
    while (*space) {
        while (*space == ' ' || *space == '\t') space++;
        if (!*space) break;
        char *end = strpbrk(space, " \t");
        if (end) *end = 0;
        if (!dr_import_domain(space, entry.domain, entry.kind == DR_REPLACE)) return DR_INVALID;
        if (strcmp(entry.domain, "localhost") != 0 && (entry.kind != DR_REPLACE || redirects)) {
            if (!sink(&entry, context)) return DR_INVALID;
        }
        if (!end) break;
        space = end + 1;
    }
    return DR_OK;
}
struct dr_builder { struct dr_snapshot next; size_t capacity; bool user; };
static inline bool dr_append(const struct dr_entry *entry, void *context) {
    struct dr_builder *b = (struct dr_builder *)context;
    if (b->next.count >= DR_MAX_ENTRIES) return false;
    if (b->next.count == b->capacity) {
        size_t capacity = b->capacity ? b->capacity * 2 : 64;
        if (capacity > DR_MAX_ENTRIES) capacity = DR_MAX_ENTRIES;
        void *owned = realloc(b->next.entries, capacity * sizeof(*entry));
        if (!owned) return false;
        b->next.entries = (struct dr_entry *)owned; b->capacity = capacity;
    }
    b->next.entries[b->next.count] = *entry;
    b->next.entries[b->next.count++].user = b->user;
    return true;
}
static inline bool dr_parse_file(FILE *file, bool allow, bool redirects, struct dr_builder *builder) {
    char line[DR_MAX_LINE + 1]; size_t length = 0, bytes = 0; int c;
    while ((c = fgetc(file)) != EOF) {
        if (++bytes > DR_MAX_BYTES || c == 0) return false;
        if (c == '\n') {
            line[length] = 0;
            if (dr_parse_line(line, allow, redirects, dr_append, builder) == DR_INVALID) return false;
            length = 0;
        } else {
            if (length == DR_MAX_LINE) return false;
            line[length++] = (char)c;
        }
    }
    if (ferror(file)) return false;
    line[length] = 0;
    return dr_parse_line(line, allow, redirects, dr_append, builder) != DR_INVALID;
}
/* Ownership transfers only on successful publication; stale updates keep both old state and caller ownership. */
static inline bool dr_publish(struct dr_store *store, struct dr_builder *builder, uint64_t generation) {
    if (!generation || generation > INT64_MAX) return false;
    dr_lock(store);
    if (generation <= store->current.generation) { dr_unlock(store); return false; }
    struct dr_entry *old = store->current.entries;
    builder->next.generation = generation;
    store->current = builder->next;
    memset(&builder->next, 0, sizeof(builder->next)); builder->capacity = 0;
    dr_unlock(store);
    free(old); return true;
}
struct dr_result { struct core_decision decision; uint64_t generation; int family; uint8_t address[16]; };
static inline bool dr_wildcard(const char *pattern, const char *host) {
    const char *star = NULL, *retry = NULL;
    while (*host) {
        if (*pattern == '?' || *pattern == *host) { pattern++; host++; }
        else if (*pattern == '*') { star = pattern++; retry = host; }
        else if (star) { pattern = star + 1; host = ++retry; }
        else return false;
    }
    while (*pattern == '*') pattern++;
    return !*pattern;
}
static inline struct dr_result dr_query(struct dr_store *store, const char *raw, int type, int qclass,
                                        int block_mode, uint64_t network_generation) {
    struct dr_result result;
    memset(&result, 0, sizeof(result));
    char host[254];
    struct core_rule_input input = {type, qclass, raw && dr_domain(raw, host, false), true, 0, false, false, 0, block_mode};
    (void)network_generation; /* No response cache: callers must key any cache on BOTH generations. */
    dr_lock(store);
    result.generation = store->current.generation;
    bool user_replacement = false;
    size_t hl = input.question_valid ? strlen(host) : 0;
    for (size_t i = 0; input.question_valid && i < store->current.count; i++) {
        const struct dr_entry *e = &store->current.entries[i];
        size_t rl = strlen(e->domain);
        bool match = dr_wildcard(e->domain, host) || (e->suffix && hl > rl && host[hl - rl - 1] == '.'
                && strcmp(e->domain, host + hl - rl) == 0);
        if (!match) continue;
        if (e->user && e->kind != DR_REPLACE) {
            input.user_override = e->kind == DR_ALLOW ? 1 : -1; user_replacement = false;
        }
        else if (e->kind == DR_ALLOW) input.allow = true;
        else if (e->kind == DR_BLOCK) input.block = true;
        else {
            if (user_replacement && !e->user) continue;
            if (e->user) { input.user_override = 0; user_replacement = true; }
            input.replacement_family = e->family; result.family = e->family;
            memcpy(result.address, e->address, sizeof(result.address));
        }
    }
    if (user_replacement) { input.allow = false; input.block = false; }
    result.decision = core_decide(&input);
    if (result.decision.action != CORE_REPLACE) { result.family = 0; memset(result.address, 0, sizeof(result.address)); }
    dr_unlock(store); return result;
}
static inline void dr_dispose(struct dr_store *store) {
    dr_lock(store); free(store->current.entries); memset(&store->current, 0, sizeof(store->current)); dr_unlock(store);
}
static inline bool dr_same_rules(struct dr_store *store, const struct dr_builder *builder) {
    dr_lock(store);
    bool same = store->current.count == builder->next.count && (!builder->next.count
            || memcmp(store->current.entries, builder->next.entries,
                    builder->next.count * sizeof(struct dr_entry)) == 0);
    dr_unlock(store); return same;
}
#endif
