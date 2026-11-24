#define _GNU_SOURCE 1
#include "uid_allow_snapshot.h"

#include <stdio.h>
#include <unistd.h>

#define CHECK(expression) do { \
    if (!(expression)) { \
        fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #expression); \
        exit(1); \
    } \
} while (0)

static void write_atomic(const char *path, const char *content) {
    char temporary[PATH_MAX];
    int length = snprintf(temporary, sizeof(temporary), "%s.tmp", path);
    CHECK(length > 0 && (size_t)length < sizeof(temporary));
    FILE *file = fopen(temporary, "wb");
    CHECK(file);
    size_t bytes = strlen(content);
    CHECK(fwrite(content, 1, bytes, file) == bytes);
    CHECK(fclose(file) == 0);
    CHECK(rename(temporary, path) == 0);
}

static void make_path(char *out, size_t capacity, const char *directory) {
    int length = snprintf(out, capacity, "%s/allowlist.txt", directory);
    CHECK(length > 0 && (size_t)length < capacity);
}

int main(void) {
    char directory[] = "/tmp/uid-allow-snapshot-XXXXXX";
    CHECK(mkdtemp(directory) != NULL);
    char other_directory[] = "/tmp/uid-allow-other-XXXXXX";
    CHECK(mkdtemp(other_directory) != NULL);

    char path[PATH_MAX], other_path[PATH_MAX];
    make_path(path, sizeof(path), directory);
    make_path(other_path, sizeof(other_path), other_directory);

    struct ua_snapshot snapshot = UA_SNAPSHOT_INITIALIZER;
    write_atomic(path, "10001\n10002\n10001\n");
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)10001, directory));
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)10002, directory));
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10003, directory));
    CHECK(snapshot.count == 2 && snapshot.reloads == 1);

    /* An unchanged file is parsed once, even across many request lookups. */
    for (int i = 0; i < 1000; i++) {
        CHECK(ua_snapshot_contains(&snapshot, (uid_t)10001, directory));
        CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10003, directory));
    }
    CHECK(snapshot.reloads == 1);

    /* Same-size atomic replacement must invalidate the cached UID set. */
    write_atomic(path, "10003\n10004\n10003\n");
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10001, directory));
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)10003, directory));
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)10004, directory));
    CHECK(snapshot.count == 2 && snapshot.reloads == 2);

    /* Preserve the old parser's acceptance of a numeric prefix. */
    write_atomic(path, "10005suffix\n-1\ninvalid\n");
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)10005, directory));
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10003, directory));
    CHECK(snapshot.reloads == 3);

    /* A missing file is a valid empty list and is cached as such. */
    CHECK(remove(path) == 0);
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10005, directory));
    CHECK(snapshot.count == 0 && snapshot.reloads == 4);
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10005, directory));
    CHECK(snapshot.reloads == 4);

    /* Switching resource directories must never reuse the previous list. */
    write_atomic(other_path, "20001\n");
    CHECK(ua_snapshot_contains(&snapshot, (uid_t)20001, other_directory));
    CHECK(!ua_snapshot_contains(&snapshot, (uid_t)10005, other_directory));
    CHECK(snapshot.reloads == 5);

    ua_snapshot_dispose(&snapshot);
    CHECK(remove(other_path) == 0);
    CHECK(rmdir(directory) == 0);
    CHECK(rmdir(other_directory) == 0);
    puts("UID allowlist snapshot: cache hits, duplicate removal, atomic same-size replacement, legacy parsing, deletion and path isolation passed");
    return 0;
}
