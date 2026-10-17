#ifndef ADAWAY_DOMAIN_ALLOW_SNAPSHOT_H
#define ADAWAY_DOMAIN_ALLOW_SNAPSHOT_H
#include "domain_rule_snapshot.h"
#include "rule_norm.h"

/* Adapter for the existing production exemption file, including its explicit URL-to-host UI syntax.
 * ABP conditional rules go through the strict source parser; URL text in an exemption is an
 * intentional whole-domain user exemption, not an imported URL filter. */
static inline bool da_append(const struct dr_entry *entry, void *context) {
    struct dr_entry allowed = *entry;
    allowed.kind = DR_ALLOW; allowed.suffix = true; allowed.family = 0;
    memset(allowed.address, 0, sizeof(allowed.address));
    return dr_append(&allowed, context);
}
static inline bool da_parse_line(const char *line, struct dr_builder *builder) {
    while (*line == ' ' || *line == '\t') line++;
    if (!*line || *line == '#' || *line == '!') return true;
    if (strncmp(line, "||", 2) == 0 || strncmp(line, "@@", 2) == 0)
        return dr_parse_line(line, true, false, da_append, builder) != DR_INVALID;
    if (rule_has_unsupported_modifier(line)) return true;
    char norm[DR_MAX_LINE + 1];
    domain_rule_normalize(line, norm, sizeof(norm));
    struct dr_entry entry; memset(&entry, 0, sizeof(entry));
    if (!dr_domain(norm, entry.domain, false) || !strchr(entry.domain, '.') || strlen(entry.domain) < 4)
        return false;
    return da_append(&entry, builder);
}
static inline bool da_parse_file(FILE *file, struct dr_builder *builder) {
    char line[DR_MAX_LINE + 1]; size_t length = 0, bytes = 0; int c;
    while ((c = fgetc(file)) != EOF) {
        if (++bytes > DR_MAX_BYTES || !c) return false;
        if (c == '\n') {
            line[length] = 0;
            if (!da_parse_line(line, builder)) return false;
            length = 0;
        } else {
            if (length == DR_MAX_LINE) return false;
            line[length++] = (char)c;
        }
    }
    if (ferror(file)) return false;
    line[length] = 0; return da_parse_line(line, builder);
}
/* All state is paired with its resource path; a failure for another directory cannot use old rules. */
struct da_repository {
    struct dr_store writer, rules;
    char path[4096];
    uint64_t generation;
};
#define DA_REPOSITORY_INITIALIZER { DR_STORE_INITIALIZER, DR_STORE_INITIALIZER, "", 0 }
struct da_result { bool allowed, update_valid; uint64_t generation; };
static inline struct da_result da_lookup(struct da_repository *repository, const char *host, const char *directory) {
    struct da_result result = {false, false, 0};
    if (!host || !directory || !*directory) return result;
    char path[4096];
    int length = snprintf(path, sizeof(path), "%s/domain_allowlist.txt", directory);
    if (length < 0 || (size_t)length >= sizeof(path)) return result;
    dr_lock(&repository->writer);
    struct dr_builder builder; memset(&builder, 0, sizeof(builder));
    errno = 0;
    FILE *file = fopen(path, "rb");
    bool valid = file ? da_parse_file(file, &builder) : errno == ENOENT;
    if (file && fclose(file) != 0) valid = false;
    result.update_valid = valid;
    if (valid && repository->generation < INT64_MAX && (strcmp(repository->path, path) != 0
            || !repository->generation || !dr_same_rules(&repository->rules, &builder))) {
        if (dr_publish(&repository->rules, &builder, repository->generation + 1)) {
            repository->generation++;
            memcpy(repository->path, path, (size_t)length + 1);
        }
    }
    free(builder.next.entries);
    result.allowed = strcmp(repository->path, path) == 0 &&
            dr_query(&repository->rules, host, 1, 1, 0, 0).decision.reason == CORE_ALLOW_EXCEPTION;
    result.generation = repository->generation;
    dr_unlock(&repository->writer); return result;
}
#endif
