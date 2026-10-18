#ifndef ADAWAY_TYPED_RULE_RECORD_H
#define ADAWAY_TYPED_RULE_RECORD_H
#include "domain_rule_snapshot.h"

enum tr_scope { TR_EXACT, TR_SUFFIX, TR_WILDCARD };
struct tr_record {
    struct dr_entry entry;
    uint32_t source;
    uint64_t order;
    enum tr_scope scope;
    bool legacy;
};
/* Local, versioned UTF-8/ASCII lines, never raw padded C memory or URLs.
 * @adaway-rule-v1 TAB source TAB order TAB kind TAB scope TAB name TAB address-or--
 * Source 1 is reserved for explicit local user rules; subscriptions start at 2. */
static inline bool tr_number(const char *text, uint64_t maximum, uint64_t *out) {
    if (!text || !*text) return false;
    uint64_t n = 0;
    for (const char *p = text; *p; p++) {
        if (*p < '0' || *p > '9' || n > (maximum - (unsigned)(*p - '0')) / 10) return false;
        n = n * 10 + (unsigned)(*p - '0');
        if (n > maximum) return false;
    }
    *out = n; return true;
}
static inline bool tr_parse(const char *line, struct tr_record *record) {
    char buf[512]; size_t length = strlen(line);
    if (length >= sizeof(buf)) return false;
    memcpy(buf, line, length + 1);
    if (length && buf[length - 1] == '\r') buf[--length] = 0;
    char *fields[7]; fields[0] = buf;
    for (unsigned i = 1; i < 7; i++) {
        char *tab = strchr(fields[i - 1], '\t');
        if (!tab) return false;
        *tab = 0; fields[i] = tab + 1;
    }
    if (strchr(fields[6], '\t') || strcmp(fields[0], "@adaway-rule-v1") != 0) return false;
    uint64_t source, order, kind, scope;
    if (!tr_number(fields[1], INT32_MAX, &source) || !source || !tr_number(fields[2], INT64_MAX, &order) || !order
            || !tr_number(fields[3], 2, &kind) || !tr_number(fields[4], 2, &scope)) return false;
    memset(record, 0, sizeof(*record));
    record->source = (uint32_t)source; record->order = order;
    record->entry.kind = (enum dr_kind)kind; record->scope = (enum tr_scope)scope;
    record->entry.user = source == 1; record->entry.suffix = scope == TR_SUFFIX;
    if (!dr_import_domain(fields[5], record->entry.domain, scope == TR_WILDCARD)) return false;
    bool wildcard = strpbrk(record->entry.domain, "*?") != NULL;
    if (wildcard != (scope == TR_WILDCARD)) return false;
    if (kind == DR_REPLACE) {
        record->entry.family = dr_ip(fields[6], 4, record->entry.address) ? 4
                : dr_ip(fields[6], 6, record->entry.address) ? 6 : 0;
        if (!record->entry.family) return false;
    } else if (strcmp(fields[6], "-") != 0) return false;
    return true;
}
static inline int tr_format(char *out, size_t capacity, const struct tr_record *record) {
    char address[64] = "-";
    if (record->entry.kind == DR_REPLACE) {
#ifdef _WIN32
        if (!InetNtopA(record->entry.family == 4 ? AF_INET : AF_INET6,
                (void *)record->entry.address, address, sizeof(address))) return -1;
#else
        if (!inet_ntop(record->entry.family == 4 ? AF_INET : AF_INET6,
                record->entry.address, address, sizeof(address))) return -1;
#endif
    }
    return snprintf(out, capacity, "@adaway-rule-v1\t%u\t%llu\t%d\t%d\t%s\t%s\n",
            record->source, (unsigned long long)record->order, (int)record->entry.kind,
            (int)record->scope, record->entry.domain, address);
}
#endif
