#ifndef ADAWAY_DOMAIN_ALLOW_SNAPSHOT_H
#define ADAWAY_DOMAIN_ALLOW_SNAPSHOT_H
#include "domain_rule_snapshot.h"
#include "rule_norm.h"
#include <sys/stat.h>

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
/* A stat fingerprint avoids reopening and reparsing the exemption file on
 * every HTTP request. The app publishes this file atomically, so inode/file
 * identity plus size and timestamps also detects same-size replacements. */
struct da_file_stamp {
    bool exists;
    uint64_t size, device, inode;
    int64_t modified_sec, modified_nsec, changed_sec, changed_nsec;
};
static inline bool da_file_stamp_read(const char *path, struct da_file_stamp *stamp) {
    memset(stamp, 0, sizeof(*stamp));
#ifdef _WIN32
    struct _stat64 info;
    if (_stat64(path, &info) != 0) return errno == ENOENT;
    stamp->exists = true;
    stamp->size = (uint64_t)info.st_size;
    stamp->device = (uint64_t)info.st_dev;
    stamp->inode = (uint64_t)info.st_ino;
    stamp->modified_sec = (int64_t)info.st_mtime;
    stamp->changed_sec = (int64_t)info.st_ctime;
#else
    struct stat info;
    if (stat(path, &info) != 0) return errno == ENOENT;
    stamp->exists = true;
    stamp->size = (uint64_t)info.st_size;
    stamp->device = (uint64_t)info.st_dev;
    stamp->inode = (uint64_t)info.st_ino;
#if defined(__APPLE__)
    stamp->modified_sec = (int64_t)info.st_mtimespec.tv_sec;
    stamp->modified_nsec = (int64_t)info.st_mtimespec.tv_nsec;
    stamp->changed_sec = (int64_t)info.st_ctimespec.tv_sec;
    stamp->changed_nsec = (int64_t)info.st_ctimespec.tv_nsec;
#else
    stamp->modified_sec = (int64_t)info.st_mtim.tv_sec;
    stamp->modified_nsec = (int64_t)info.st_mtim.tv_nsec;
    stamp->changed_sec = (int64_t)info.st_ctim.tv_sec;
    stamp->changed_nsec = (int64_t)info.st_ctim.tv_nsec;
#endif
#endif
    return true;
}
static inline bool da_file_stamp_equal(const struct da_file_stamp *a,
                                       const struct da_file_stamp *b) {
    return a->exists == b->exists && a->size == b->size &&
           a->device == b->device && a->inode == b->inode &&
           a->modified_sec == b->modified_sec && a->modified_nsec == b->modified_nsec &&
           a->changed_sec == b->changed_sec && a->changed_nsec == b->changed_nsec;
}

/* Keep the last valid rules separate from the last attempted file version:
 * an invalid replacement must not discard working rules or be reparsed per request. */
struct da_repository {
    struct dr_store writer, rules;
    char path[4096];          /* path of the currently published rules */
    char checked_path[4096];  /* path of the last successfully fingerprinted attempt */
    struct da_file_stamp checked_stamp;
    bool checked_stamp_valid, last_update_valid;
    uint64_t generation, reloads;
};
#define DA_REPOSITORY_INITIALIZER { DR_STORE_INITIALIZER, DR_STORE_INITIALIZER, "", "", {0}, false, false, 0, 0 }
struct da_result { bool allowed, update_valid, refresh_attempted; uint64_t generation; };
static inline struct da_result da_lookup(struct da_repository *repository, const char *host, const char *directory) {
    struct da_result result = {false, false, false, 0};
    if (!host || !directory || !*directory) return result;
    char path[4096];
    int length = snprintf(path, sizeof(path), "%s/domain_allowlist.txt", directory);
    if (length < 0 || (size_t)length >= sizeof(path)) return result;
    dr_lock(&repository->writer);

    struct da_file_stamp stamp;
    if (!da_file_stamp_read(path, &stamp)) {
        result.refresh_attempted = true; /* transient I/O error: retry on the next request */
        goto query;
    }
    bool unchanged = repository->checked_stamp_valid &&
        strcmp(repository->checked_path, path) == 0 &&
        da_file_stamp_equal(&repository->checked_stamp, &stamp);
    if (unchanged) {
        result.update_valid = repository->last_update_valid;
    } else {
        struct dr_builder builder; memset(&builder, 0, sizeof(builder));
        errno = 0;
        FILE *file = stamp.exists ? fopen(path, "rb") : NULL;
        bool valid = stamp.exists ? (file ? da_parse_file(file, &builder) : errno == ENOENT) : true;
        bool cacheable = !stamp.exists || file != NULL || errno == ENOENT;
        if (file && fclose(file) != 0) { valid = false; cacheable = false; }
        result.update_valid = valid;
        result.refresh_attempted = true;
        repository->reloads++;
        if (cacheable) {
            memcpy(repository->checked_path, path, (size_t)length + 1);
            repository->checked_stamp = stamp;
            repository->checked_stamp_valid = true;
            repository->last_update_valid = valid;
        }
        if (valid && repository->generation < INT64_MAX &&
            (strcmp(repository->path, path) != 0 || !repository->generation ||
             !dr_same_rules(&repository->rules, &builder))) {
            if (dr_publish(&repository->rules, &builder, repository->generation + 1)) {
                repository->generation++;
                memcpy(repository->path, path, (size_t)length + 1);
            }
        }
        free(builder.next.entries);
    }
query:
    result.allowed = strcmp(repository->path, path) == 0 &&
        dr_query(&repository->rules, host, 1, 1, 0, 0).decision.reason == CORE_ALLOW_EXCEPTION;
done:
    result.generation = repository->generation;
    dr_unlock(&repository->writer);
    return result;
}
#endif
