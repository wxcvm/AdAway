#ifndef ADAWAY_BLOCK_RULE_SNAPSHOT_H
#define ADAWAY_BLOCK_RULE_SNAPSHOT_H
#include "domain_rule_snapshot.h"

/* Production blocked-host table: full names avoid hash collisions. Pool and sorted index share one generation. */
#define BS_MAX_ENTRIES 500000u
#define BS_MAX_POOL (32u * 1024u * 1024u)
struct bs_rule { const char *domain; bool allow, suffix; };
struct bs_snapshot { struct bs_rule *rules; char *pool; size_t count; uint64_t generation; };
struct bs_store { struct dr_store lock; struct bs_snapshot current; uint64_t last_requested; };
#define BS_STORE_INITIALIZER { DR_STORE_INITIALIZER, {NULL, NULL, 0, 0}, 0 }
struct bs_builder {
    struct bs_snapshot next;
    uint32_t *offsets;
    size_t capacity, pool_capacity, used;
};
static inline bool bs_append(const struct dr_entry *entry, void *context) {
    struct bs_builder *builder = (struct bs_builder *)context;
    if (entry->kind == DR_REPLACE) return true; /* Address replacements do not mean HTTP blocking. */
    if (builder->next.count == BS_MAX_ENTRIES) return false;
    size_t length = strlen(entry->domain) + 1;
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
    builder->next.rules[index].allow = entry->kind == DR_ALLOW;
    builder->next.rules[index].suffix = entry->suffix;
    memcpy(builder->next.pool + builder->used, entry->domain, length); builder->used += length;
    return true;
}
static inline int bs_compare(const void *first, const void *second) {
    const struct bs_rule *a = (const struct bs_rule *)first, *b = (const struct bs_rule *)second;
    int order = strcmp(a->domain, b->domain);
    if (order) return order;
    if (a->suffix != b->suffix) return a->suffix ? 1 : -1;
    return a->allow == b->allow ? 0 : a->allow ? 1 : -1;
}
static inline void bs_free_builder(struct bs_builder *builder) {
    free(builder->next.rules); free(builder->next.pool); free(builder->offsets); memset(builder, 0, sizeof(*builder));
}
static inline bool bs_parse_file(FILE *file, struct bs_builder *builder) {
    char line[DR_MAX_LINE + 1]; size_t length = 0, bytes = 0; int c;
    while ((c = fgetc(file)) != EOF) {
        if (++bytes > DR_MAX_BYTES || !c) return false;
        if (c == '\n') {
            line[length] = 0;
            if (dr_parse_line(line, false, false, bs_append, builder) == DR_INVALID) return false;
            length = 0;
        } else {
            if (length == DR_MAX_LINE) return false;
            line[length++] = (char)c;
        }
    }
    if (ferror(file)) return false;
    line[length] = 0;
    if (dr_parse_line(line, false, false, bs_append, builder) == DR_INVALID) return false;
    return true;
}
static inline void bs_finish(struct bs_builder *builder) {
    for (size_t i = 0; i < builder->next.count; i++) builder->next.rules[i].domain = builder->next.pool + builder->offsets[i];
    if (builder->next.count) qsort(builder->next.rules, builder->next.count, sizeof(struct bs_rule), bs_compare);
    // Duplicate rules are coalesced; allow wins for otherwise identical scope.
    size_t count = 0;
    for (size_t i = 0; i < builder->next.count; i++) {
        struct bs_rule rule = builder->next.rules[i];
        if (count && strcmp(builder->next.rules[count - 1].domain, rule.domain) == 0
                && builder->next.rules[count - 1].suffix == rule.suffix) builder->next.rules[count - 1].allow |= rule.allow;
        else builder->next.rules[count++] = rule;
    }
    builder->next.count = count;
}
static inline bool bs_publish(struct bs_store *store, struct bs_builder *builder, uint64_t generation) {
    if (!generation || generation > INT64_MAX) return false;
    dr_lock(&store->lock);
    if (generation <= store->current.generation) { dr_unlock(&store->lock); return false; }
    struct bs_snapshot old = store->current;
    builder->next.generation = generation; store->current = builder->next;
    memset(&builder->next, 0, sizeof(builder->next));
    dr_unlock(&store->lock);
    free(old.rules); free(old.pool); free(builder->offsets); builder->offsets = NULL; return true;
}
static inline void bs_match(const struct bs_snapshot *snapshot, const char *domain, bool parent, bool *allow, bool *block) {
    size_t begin = 0, end = snapshot->count;
    while (begin < end) {
        size_t middle = begin + (end - begin) / 2;
        if (strcmp(snapshot->rules[middle].domain, domain) < 0) begin = middle + 1; else end = middle;
    }
    while (begin < snapshot->count && strcmp(snapshot->rules[begin].domain, domain) == 0) {
        const struct bs_rule *rule = &snapshot->rules[begin++];
        if (parent && !rule->suffix) continue;
        if (rule->allow) *allow = true; else *block = true;
    }
}
struct bs_result { struct core_decision decision; uint64_t generation; size_t count; };
static inline struct bs_result bs_query(struct bs_store *store, const char *raw) {
    char host[254];
    struct core_rule_input input = {1, 1, raw && dr_domain(raw, host, false), true, 0, false, false, 0, 0};
    dr_lock(&store->lock);
    if (input.question_valid) {
        bs_match(&store->current, host, false, &input.allow, &input.block);
        const char *parent = strchr(host, '.');
        while (parent) { parent++; bs_match(&store->current, parent, true, &input.allow, &input.block); parent = strchr(parent, '.'); }
    }
    struct bs_result result = {core_decide(&input), store->current.generation, store->current.count};
    dr_unlock(&store->lock); return result;
}
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
        opened = true; valid = bs_parse_file(file, &builder);
        if (fclose(file) != 0) valid = false;
    }
    if (!opened) valid = false;
    if (valid) {
        bs_finish(&builder);
        dr_lock(&store->lock);
        if (requested != store->last_requested || requested <= store->current.generation) valid = false;
        else {
            struct bs_snapshot old = store->current;
            builder.next.generation = requested; store->current = builder.next;
            memset(&builder.next, 0, sizeof(builder.next));
            dr_unlock(&store->lock); free(old.rules); free(old.pool); bs_free_builder(&builder); return true;
        }
        dr_unlock(&store->lock);
    }
    bs_free_builder(&builder); return false;
}
static inline bool bs_reload(struct bs_store *store, const char *path) { return bs_reload_paths(store, path, NULL, false); }
static inline void bs_dispose(struct bs_store *store) {
    dr_lock(&store->lock); free(store->current.rules); free(store->current.pool);
    memset(&store->current, 0, sizeof(store->current)); dr_unlock(&store->lock);
}
#endif
