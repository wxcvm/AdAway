#ifndef ADWAY_UID_ALLOW_SNAPSHOT_H
#define ADWAY_UID_ALLOW_SNAPSHOT_H

#include <stdbool.h>
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>

/*
 * allowlist.txt is replaced atomically by the app. A file fingerprint lets
 * the single-threaded Mongoose event loop reuse the parsed UID set instead of
 * opening and scanning the file for every request.
 */
struct ua_file_stamp {
    bool exists;
    uint64_t size, device, inode;
    int64_t modified_sec, modified_nsec, changed_sec, changed_nsec;
};

static inline bool ua_file_stamp_read(const char *path, struct ua_file_stamp *stamp) {
    memset(stamp, 0, sizeof(*stamp));
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
    return true;
}

static inline bool ua_file_stamp_equal(const struct ua_file_stamp *a,
                                       const struct ua_file_stamp *b) {
    return a->exists == b->exists && a->size == b->size &&
           a->device == b->device && a->inode == b->inode &&
           a->modified_sec == b->modified_sec && a->modified_nsec == b->modified_nsec &&
           a->changed_sec == b->changed_sec && a->changed_nsec == b->changed_nsec;
}

struct ua_snapshot {
    char path[PATH_MAX];
    struct ua_file_stamp stamp;
    bool stamp_valid;
    uid_t *uids;
    size_t count;
    uint64_t reloads;
};

#define UA_SNAPSHOT_INITIALIZER { "", {0}, false, NULL, 0, 0 }

static inline int ua_uid_compare(const void *left, const void *right) {
    uid_t a = *(const uid_t *)left;
    uid_t b = *(const uid_t *)right;
    return a < b ? -1 : a > b ? 1 : 0;
}

static inline bool ua_snapshot_contains_cached(const struct ua_snapshot *snapshot, uid_t uid) {
    size_t low = 0, high = snapshot->count;
    while (low < high) {
        size_t middle = low + (high - low) / 2;
        if (snapshot->uids[middle] < uid) low = middle + 1;
        else high = middle;
    }
    return low < snapshot->count && snapshot->uids[low] == uid;
}

/*
 * A stat error or a file changing during a read fails closed for this request.
 * A missing file is a valid empty allowlist. Malformed lines remain ignored,
 * matching the previous fgets/strtol reader's behavior.
 */
static inline bool ua_snapshot_contains(struct ua_snapshot *snapshot, uid_t uid,
                                        const char *resource_dir) {
    if (!snapshot || !resource_dir || !*resource_dir) return false;

    char path[PATH_MAX];
    int length = snprintf(path, sizeof(path), "%s/allowlist.txt", resource_dir);
    if (length < 0 || (size_t)length >= sizeof(path)) return false;

    struct ua_file_stamp stamp;
    if (!ua_file_stamp_read(path, &stamp)) return false;
    if (snapshot->stamp_valid && strcmp(snapshot->path, path) == 0 &&
        ua_file_stamp_equal(&snapshot->stamp, &stamp))
        return ua_snapshot_contains_cached(snapshot, uid);

    uid_t *uids = NULL;
    size_t count = 0, capacity = 0;
    FILE *file = stamp.exists ? fopen(path, "r") : NULL;
    if (stamp.exists && !file) return false;

    bool ok = true;
    if (file) {
        char line[32];
        while (fgets(line, sizeof(line), file) != NULL) {
            char *end = NULL;
            long value = strtol(line, &end, 10);
            if (end == line || value < 0) continue;

            if (count == capacity) {
                size_t next_capacity = capacity ? capacity * 2 : 16;
                if (next_capacity < capacity || next_capacity > SIZE_MAX / sizeof(*uids)) {
                    ok = false;
                    break;
                }
                uid_t *next = (uid_t *)realloc(uids, next_capacity * sizeof(*uids));
                if (!next) {
                    ok = false;
                    break;
                }
                uids = next;
                capacity = next_capacity;
            }
            uids[count++] = (uid_t)value;
        }
        if (ferror(file)) ok = false;
        if (fclose(file) != 0) ok = false;
    }

    struct ua_file_stamp after;
    if (!ok || !ua_file_stamp_read(path, &after) || !ua_file_stamp_equal(&stamp, &after)) {
        free(uids);
        return false;
    }

    if (count > 1) qsort(uids, count, sizeof(*uids), ua_uid_compare);
    size_t unique = 0;
    for (size_t i = 0; i < count; i++) {
        if (unique == 0 || uids[i] != uids[unique - 1]) uids[unique++] = uids[i];
    }

    free(snapshot->uids);
    snapshot->uids = uids;
    snapshot->count = unique;
    memcpy(snapshot->path, path, (size_t)length + 1);
    snapshot->stamp = stamp;
    snapshot->stamp_valid = true;
    snapshot->reloads++;
    return ua_snapshot_contains_cached(snapshot, uid);
}

static inline void ua_snapshot_dispose(struct ua_snapshot *snapshot) {
    if (!snapshot) return;
    free(snapshot->uids);
    memset(snapshot, 0, sizeof(*snapshot));
}

#endif
