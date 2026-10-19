#ifndef ADAWAY_BLOCK_RULE_SNAPSHOT_H
#define ADAWAY_BLOCK_RULE_SNAPSHOT_H
#include "typed_rule_record.h"

/* Production blocked-host table: full names avoid hash collisions. Pool and sorted index share one generation. */
#define BS_MAX_ENTRIES 500000u
#define BS_MAX_POOL (32u * 1024u * 1024u)
struct bs_rule {
    const char *domain; bool allow, suffix;
    enum dr_kind kind; enum tr_scope scope;
    uint32_t source; uint64_t order; int family; uint8_t address[16]; bool legacy;
};
struct bs_anchor { char name[4]; size_t rule; };
struct bs_snapshot { struct bs_rule *rules; char *pool; size_t count; uint64_t generation; struct bs_anchor *anchors; size_t anchor_count; };
struct bs_store { struct dr_store lock; struct bs_snapshot current; uint64_t last_requested; };
#define BS_STORE_INITIALIZER { DR_STORE_INITIALIZER, {NULL, NULL, 0, 0, NULL, 0}, 0 }
struct bs_builder {
    struct bs_snapshot next;
    uint32_t *offsets;
    size_t capacity, pool_capacity, used;
    uint32_t source; uint64_t order; bool finished, failed;
};
static inline bool bs_append(const struct dr_entry *entry, void *context) {
    struct bs_builder *builder = (struct bs_builder *)context;
    if (builder->finished) return false;
    if (builder->next.count == BS_MAX_ENTRIES) return false;
    const char *end = (const char *)memchr(entry->domain, 0, sizeof(entry->domain));
    if (!end || end == entry->domain || builder->used > BS_MAX_POOL) return false;
    size_t length = (size_t)(end - entry->domain) + 1;
    if (length > BS_MAX_POOL - builder->used) return false;
    if (builder->next.count == builder->capacity) {
        size_t capacity = builder->capacity ? builder->capacity * 2 : 64;
        if (capacity > BS_MAX_ENTRIES) capacity = BS_MAX_ENTRIES;
        void *rules = realloc(builder->next.rules, capacity * sizeof(struct bs_rule));
        if (!rules) return false;
        builder->next.rules = (struct bs_rule *)rules;
        void *offsets = realloc(builder->offsets, capacity * sizeof(uint32_t));
        if (!offsets) return false;
        builder->offsets = (uint32_t *)offsets; builder->capacity = capacity;
    }
    if (builder->used + length > builder->pool_capacity) {
        size_t capacity = builder->pool_capacity ? builder->pool_capacity * 2 : 4096;
        while (capacity < builder->used + length) capacity *= 2;
        if (capacity > BS_MAX_POOL) capacity = BS_MAX_POOL;
        void *pool = realloc(builder->next.pool, capacity);
        if (!pool) return false;
        builder->next.pool = (char *)pool; builder->pool_capacity = capacity;
    }
    size_t index = builder->next.count++;
    builder->offsets[index] = (uint32_t)builder->used;
    memset(&builder->next.rules[index], 0, sizeof(struct bs_rule));
    builder->next.rules[index].kind = entry->kind;
    builder->next.rules[index].source = builder->source ? builder->source : 2;
    builder->next.rules[index].order = ++builder->order;
    builder->next.rules[index].legacy = true;
    builder->next.rules[index].scope = entry->suffix ? TR_SUFFIX : strpbrk(entry->domain, "*?") ? TR_WILDCARD : TR_EXACT;
    builder->next.rules[index].family = entry->family;
    memcpy(builder->next.rules[index].address, entry->address, 16);
    builder->next.rules[index].allow = entry->kind == DR_ALLOW;
    builder->next.rules[index].suffix = entry->suffix;
    memcpy(builder->next.pool + builder->used, entry->domain, length); builder->used += length;
    return true;
}
static inline int bs_compare(const void *first, const void *second) {
    const struct bs_rule *a = (const struct bs_rule *)first, *b = (const struct bs_rule *)second;
    int order = strcmp(a->domain, b->domain);
    if (order) return order;
    return (int)a->scope - (int)b->scope;
}
static inline void bs_free_builder(struct bs_builder *builder) {
    free(builder->next.rules); free(builder->next.pool); free(builder->next.anchors); free(builder->offsets); memset(builder, 0, sizeof(*builder));
}
static inline bool bs_consume(const char *line, struct bs_builder *builder) {
    if (strncmp(line, "@adaway", 7) == 0) {
        struct tr_record record;
        if (!tr_parse(line, &record) || !bs_append(&record.entry, builder)) return false;
        struct bs_rule *rule = &builder->next.rules[builder->next.count - 1];
        rule->source = record.source; rule->order = record.order; rule->scope = record.scope; rule->legacy = false;
        return true;
    }
    return dr_parse_line(line, false, true, bs_append, builder) != DR_INVALID;
}
static inline bool bs_parse_file(FILE *file, struct bs_builder *builder) {
    char line[DR_MAX_LINE + 1]; size_t length = 0, bytes = 0; int c;
    while ((c = fgetc(file)) != EOF) {
        if (++bytes > DR_MAX_BYTES || !c) goto invalid;
        if (c == '\n') {
            line[length] = 0;
            if (!bs_consume(line, builder)) goto invalid;
            length = 0;
        } else {
            if (length == DR_MAX_LINE) goto invalid;
            line[length++] = (char)c;
        }
    }
    line[length] = 0;
    if (ferror(file) || !bs_consume(line, builder)) goto invalid;
    return true;
invalid:
    builder->failed = true; return false;
}
static inline int bs_rank(enum dr_kind kind) { return kind == DR_ALLOW ? 3 : kind == DR_REPLACE ? 2 : 1; }
static inline bool bs_better(const struct bs_rule *a, const struct bs_rule *b) {
    if (!b) return true;
    if ((a->source == 1) != (b->source == 1)) return a->source == 1;
    if (a->source != 1 && a->kind != b->kind) return bs_rank(a->kind) > bs_rank(b->kind);
    if (a->scope != b->scope) return a->scope < b->scope;
    size_t al = strlen(a->domain), bl = strlen(b->domain);
    if (al != bl) return al > bl;
    if (a->source != b->source) return a->source < b->source;
    if (a->order != b->order) return a->source == 1 ? a->order > b->order : a->order < b->order;
    int name = strcmp(a->domain, b->domain);
    if (name) return name < 0;
    if (a->kind != b->kind) return bs_rank(a->kind) > bs_rank(b->kind);
    if (a->family != b->family) return a->family < b->family;
    return memcmp(a->address, b->address, 16) < 0;
}
static inline int bs_anchor_compare(const void *a, const void *b) {
    return strcmp(((const struct bs_anchor *)a)->name, ((const struct bs_anchor *)b)->name);
}
static inline unsigned bs_anchor_hash(const char *p, size_t n) {
    unsigned hash = 0; for (size_t i = 0; i < n; i++) hash = hash * 31u + (unsigned char)p[i];
    return hash % 65536u;
}
static inline size_t bs_anchor_width(const char *p) {
    size_t run = 0, best = 0;
    for (; *p; p++) { run = *p == '*' || *p == '?' ? 0 : run + 1; if (run > best) best = run; }
    return best > 3 ? 3 : best;
}
static inline bool bs_build_anchors(struct bs_builder *b) {
    size_t count = b->next.count, wildcards = 0;
    for (size_t i = 0; i < count; i++) if (b->next.rules[i].scope == TR_WILDCARD) wildcards++;
    if (!wildcards) return true;
    unsigned *frequencies = (unsigned *)calloc(65536u, sizeof(unsigned));
    b->next.anchors = (struct bs_anchor *)calloc(wildcards, sizeof(struct bs_anchor));
    if (!frequencies || !b->next.anchors) { free(frequencies); return false; }
    for (size_t i = 0; i < count; i++) {
        if (b->next.rules[i].scope != TR_WILDCARD) continue;
        const char *p = b->next.rules[i].domain; size_t width = bs_anchor_width(p), run = 0;
        for (size_t j = 0; width && p[j]; j++) {
            run = p[j] == '*' || p[j] == '?' ? 0 : run + 1;
            if (run >= width) frequencies[bs_anchor_hash(p + j + 1 - width, width)]++;
        }
    }
    for (size_t i = 0; i < count; i++) {
        if (b->next.rules[i].scope != TR_WILDCARD) continue;
        const char *p = b->next.rules[i].domain; size_t width = bs_anchor_width(p), run = 0;
        struct bs_anchor *anchor = &b->next.anchors[b->next.anchor_count++]; anchor->rule = i;
        unsigned best = UINT32_MAX;
        for (size_t j = 0; width && p[j]; j++) {
            run = p[j] == '*' || p[j] == '?' ? 0 : run + 1;
            if (run >= width) {
                const char *start = p + j + 1 - width; unsigned frequency = frequencies[bs_anchor_hash(start, width)];
                if (frequency < best || (frequency == best && strncmp(start, anchor->name, width) < 0)) {
                    memcpy(anchor->name, start, width); best = frequency;
                }
            }
        }
    }
    free(frequencies);
    qsort(b->next.anchors, b->next.anchor_count, sizeof(struct bs_anchor), bs_anchor_compare);
    return true;
}
static inline bool bs_finish(struct bs_builder *builder) {
    if (builder->failed || builder->finished) return false;
    for (size_t i = 0; i < builder->next.count; i++) builder->next.rules[i].domain = builder->next.pool + builder->offsets[i];
    if (builder->next.count) qsort(builder->next.rules, builder->next.count, sizeof(struct bs_rule), bs_compare);
    // Duplicate rules are coalesced; allow wins for otherwise identical scope.
    size_t count = 0;
    for (size_t i = 0; i < builder->next.count; i++) {
        struct bs_rule rule = builder->next.rules[i];
        if (count && strcmp(builder->next.rules[count - 1].domain, rule.domain) == 0
                && builder->next.rules[count - 1].scope == rule.scope) {
            if (bs_better(&rule, &builder->next.rules[count - 1])) builder->next.rules[count - 1] = rule;
        }
        else builder->next.rules[count++] = rule;
    }
    builder->next.count = count;
    if (!bs_build_anchors(builder)) { builder->failed = true; return false; }
    builder->finished = true; return true;
}
static inline bool bs_publish(struct bs_store *store, struct bs_builder *builder, uint64_t generation) {
    if (!generation || generation > INT64_MAX || !builder->finished || builder->failed) return false;
    dr_lock(&store->lock);
    if (generation <= store->current.generation) { dr_unlock(&store->lock); return false; }
    struct bs_snapshot old = store->current;
    builder->next.generation = generation; store->current = builder->next;
    memset(&builder->next, 0, sizeof(builder->next));
    dr_unlock(&store->lock);
    free(old.rules); free(old.pool); free(old.anchors); free(builder->offsets); builder->offsets = NULL; return true;
}
static inline const struct bs_rule *bs_match(const struct bs_snapshot *s, const char *domain,
        bool parent, const struct bs_rule *best, size_t *examined) {
    size_t begin = 0, end = s->count;
    while (begin < end) {
        size_t middle = begin + (end - begin) / 2;
        if (strcmp(s->rules[middle].domain, domain) < 0) begin = middle + 1; else end = middle;
    }
    while (begin < s->count && strcmp(s->rules[begin].domain, domain) == 0) {
        const struct bs_rule *r = &s->rules[begin++]; (*examined)++;
        if (r->scope == TR_WILDCARD || (parent && r->scope != TR_SUFFIX)) continue;
        if (bs_better(r, best)) best = r;
    }
    return best;
}
static inline const struct bs_rule *bs_wildcards(const struct bs_snapshot *s, const char *anchor,
        const char *host, const struct bs_rule *best, size_t *examined) {
    size_t begin = 0, end = s->anchor_count;
    while (begin < end) {
        size_t middle = begin + (end - begin) / 2;
        if (strcmp(s->anchors[middle].name, anchor) < 0) begin = middle + 1; else end = middle;
    }
    while (begin < s->anchor_count && strcmp(s->anchors[begin].name, anchor) == 0) {
        const struct bs_rule *rule = &s->rules[s->anchors[begin++].rule]; (*examined)++;
        if (dr_wildcard(rule->domain, host) && bs_better(rule, best)) best = rule;
    }
    return best;
}
struct bs_result {
    struct core_decision decision; uint64_t generation; size_t count;
    uint32_t source; uint64_t order; enum tr_scope scope; bool legacy;
    int family; uint8_t address[16]; size_t candidates;
};
static inline struct bs_result bs_decide(struct bs_store *store, const char *raw, int type, int qclass) {
    char host[254];
    struct core_rule_input input = {type, qclass, raw && dr_domain(raw, host, false), true, 0, false, false, 0, 0};
    struct bs_result result; memset(&result, 0, sizeof(result));
    dr_lock(&store->lock); const struct bs_snapshot *s = &store->current; const struct bs_rule *best = NULL;
    if (input.question_valid) {
        best = bs_match(s, host, false, best, &result.candidates);
        const char *parent = strchr(host, '.');
        while (parent) { parent++; best = bs_match(s, parent, true, best, &result.candidates); parent = strchr(parent, '.'); }
        best = bs_wildcards(s, "", host, best, &result.candidates);
        size_t length = strlen(host);
        for (size_t width = 1; width <= 3 && width <= length; width++) {
            for (size_t at = 0; at + width <= length; at++) {
                char anchor[4] = {0}; memcpy(anchor, host + at, width);
                best = bs_wildcards(s, anchor, host, best, &result.candidates);
            }
        }
    }
    result.generation = s->generation; result.count = s->count;
    if (best) {
        result.source = best->source; result.order = best->order; result.scope = best->scope; result.legacy = best->legacy;
        input.allow = best->kind == DR_ALLOW; input.block = best->kind == DR_BLOCK;
        input.replacement_family = best->family;
        if (best->source == 1 && !best->family) input.user_override = best->kind == DR_ALLOW ? 1 : -1;
    }
    result.decision = core_decide(&input);
    if (best && result.decision.action == CORE_REPLACE) {
        result.family = best->family; memcpy(result.address, best->address, 16);
        if (best->source == 1) result.decision.reason = CORE_USER_OVERRIDE;
    }
    dr_unlock(&store->lock); return result;
}
static inline struct bs_result bs_query(struct bs_store *store, const char *raw) { return bs_decide(store, raw, 1, 1); }
/* Paired source publication. Missing optional files are absent sources; present but invalid sources reject the entire update. */
static inline bool bs_reload_paths(struct bs_store *store, const char *first, const char *second, bool optional) {
    dr_lock(&store->lock);
    uint64_t requested = store->last_requested > store->current.generation ? store->last_requested : store->current.generation;
    if (requested == INT64_MAX) { dr_unlock(&store->lock); return false; }
    store->last_requested = ++requested;
    dr_unlock(&store->lock);
    struct bs_builder builder; memset(&builder, 0, sizeof(builder));
    const char *paths[2] = {first, second}; bool valid = true, opened = false;
    for (unsigned i = 0; valid && i < 2; i++) {
        if (!paths[i]) continue;
        errno = 0; FILE *file = fopen(paths[i], "rb");
        if (!file) { if (!optional || errno != ENOENT) valid = false; continue; }
        builder.source = 2 + i; builder.order = 0;
        opened = true; valid = bs_parse_file(file, &builder);
        if (fclose(file) != 0) valid = false;
    }
    if (!opened) valid = false;
    valid = valid && bs_finish(&builder);
    if (valid) {
        dr_lock(&store->lock);
        if (requested != store->last_requested || requested <= store->current.generation) valid = false;
        else {
            struct bs_snapshot old = store->current;
            builder.next.generation = requested; store->current = builder.next;
            memset(&builder.next, 0, sizeof(builder.next));
            dr_unlock(&store->lock); free(old.rules); free(old.pool); free(old.anchors); bs_free_builder(&builder); return true;
        }
        dr_unlock(&store->lock);
    }
    bs_free_builder(&builder); return false;
}
static inline bool bs_reload(struct bs_store *store, const char *path) { return bs_reload_paths(store, path, NULL, false); }
static inline void bs_dispose(struct bs_store *store) {
    dr_lock(&store->lock); free(store->current.rules); free(store->current.pool); free(store->current.anchors);
    memset(&store->current, 0, sizeof(store->current)); dr_unlock(&store->lock);
}
#endif
