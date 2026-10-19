#define _POSIX_C_SOURCE 200809L
#include "domain_allow_snapshot.h"

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)
struct capture { char text[4097]; };
static bool capture_entry(const struct dr_entry *entry, void *context) {
    struct capture *c = (struct capture *)context;
    char address[64] = "";
    if (entry->family) {
#ifdef _WIN32
        CHECK(InetNtopA(entry->family == 4 ? AF_INET : AF_INET6, (void *)entry->address, address, sizeof(address)) != NULL);
#else
        CHECK(inet_ntop(entry->family == 4 ? AF_INET : AF_INET6, entry->address, address, sizeof(address)) != NULL);
#endif
    }
    const char *kind = entry->kind == DR_ALLOW ? "ALLOW" : entry->kind == DR_BLOCK ? "BLOCK" : "REPLACE";
    size_t used = strlen(c->text);
    int n = snprintf(c->text + used, sizeof(c->text) - used, "%s%s:%s:%s", used ? ";" : "", kind, entry->domain, address);
    return n >= 0 && (size_t)n < sizeof(c->text) - used;
}
static void vectors(const char *path) {
    FILE *file = fopen(path, "rb"); CHECK(file != NULL);
    char line[8192]; unsigned count = 0;
    const char *status[] = {"OK", "UNSUPPORTED", "COSMETIC", "INVALID"};
    while (fgets(line, sizeof(line), file)) {
        if (*line == '#' || *line == '\n' || *line == '\r') continue;
        size_t n = strlen(line); while (n && (line[n - 1] == '\r' || line[n - 1] == '\n')) line[--n] = 0;
        char *cells[4]; cells[0] = line;
        for (unsigned i = 1; i < 4; i++) {
            char *tab = strchr(cells[i - 1], '\t'); CHECK(tab); *tab = 0; cells[i] = tab + 1;
        }
        int flags = atoi(cells[0]); struct capture capture = {{0}};
        enum dr_parse_status got = dr_parse_line(cells[1], (flags & 1) != 0, (flags & 2) != 0, capture_entry, &capture);
        if (strcmp(status[got], cells[2]) != 0) fprintf(stderr, "vector: %s expected %s got %s\n", cells[1], cells[2], status[got]);
        CHECK(strcmp(status[got], cells[2]) == 0);
        if (got != DR_INVALID) CHECK(strcmp(capture.text, strcmp(cells[3], "-") == 0 ? "" : cells[3]) == 0);
        count++;
    }
    CHECK(!ferror(file)); CHECK(fclose(file) == 0); CHECK(count >= 25);
    printf("Shared real-loader vectors: %u passed\n", count);
}
static bool update(struct dr_store *store, const char *text, uint64_t generation, bool user) {
    FILE *file = tmpfile(); CHECK(file); CHECK(fwrite(text, 1, strlen(text), file) == strlen(text)); rewind(file);
    struct dr_builder builder; memset(&builder, 0, sizeof(builder)); builder.user = user;
    bool success = dr_parse_file(file, false, true, &builder);
    CHECK(fclose(file) == 0);
    if (success) success = dr_publish(store, &builder, generation);
    free(builder.next.entries); return success;
}
static void rollback_and_priority(void) {
    struct dr_store store = DR_STORE_INITIALIZER;
    CHECK(update(&store, "0.0.0.0 ads.test\n192.0.2.8 ads.test\n@@||ads.test^\n", 1, false));
    struct dr_result result = dr_query(&store, "ads.test", 1, 1, 0, 1);
    CHECK(result.generation == 1 && result.decision.reason == CORE_ALLOW_EXCEPTION);
    CHECK(!update(&store, "0.0.0.0 other.test\n999.1.1.1 bad.test\n", 2, false));
    CHECK(dr_query(&store, "ads.test", 1, 1, 0, 2).generation == 1);
    CHECK(!update(&store, "0.0.0.0 other.test\n", 1, false));
    CHECK(!update(&store, "0.0.0.0 other.test\n", UINT64_MAX, false));
    CHECK(update(&store, "0.0.0.0 ads.test\n@@||ads.test^\n0.0.0.0 ads.test\n", 2, true));
    result = dr_query(&store, "ads.test", 1, 1, 0, 2);
    CHECK(result.decision.action == CORE_NXDOMAIN && result.decision.reason == CORE_USER_OVERRIDE);
    CHECK(update(&store, "0.0.0.0 ads.test\n@@||ads.test^\n2001:db8::8 ads.test\n", 3, true));
    result = dr_query(&store, "ads.test", 28, 1, 0, 2);
    CHECK(result.decision.action == CORE_REPLACE && result.family == 6);
    CHECK(dr_query(&store, "ads.test", 65, 1, 0, 2).decision.action == CORE_PASS);
    dr_dispose(&store);
    puts("Native invalid/stale update rollback and precedence passed");
}
struct reader_context { struct dr_store *store; int failed; };
#ifdef _WIN32
static DWORD WINAPI reader(void *opaque)
#else
static void *reader(void *opaque)
#endif
{
    struct reader_context *context = (struct reader_context *)opaque;
    for (unsigned i = 0; i < 50000; i++) {
        struct dr_result result = dr_query(context->store, "redirect.test", 1, 1, 0, i);
        if (!result.generation) continue;
        enum core_action expected = result.generation % 2 ? CORE_PASS : CORE_REPLACE;
        if (result.decision.action != expected || (expected == CORE_REPLACE && (result.family != 4 || result.address[3] != 8))) {
            context->failed = 1; break;
        }
    }
#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}
static void concurrent(void) {
    struct dr_store store = DR_STORE_INITIALIZER;
    struct reader_context contexts[4];
#ifdef _WIN32
    HANDLE threads[4];
#else
    pthread_t threads[4];
#endif
    for (unsigned i = 0; i < 4; i++) {
        contexts[i].store = &store; contexts[i].failed = 0;
#ifdef _WIN32
        threads[i] = CreateThread(NULL, 0, reader, &contexts[i], 0, NULL); CHECK(threads[i]);
#else
        CHECK(pthread_create(&threads[i], NULL, reader, &contexts[i]) == 0);
#endif
    }
    for (uint64_t i = 1; i <= 1000; i++) CHECK(update(&store,
            i % 2 ? "2001:db8::8 redirect.test\n" : "192.0.2.8 redirect.test\n", i, false));
    for (unsigned i = 0; i < 4; i++) {
#ifdef _WIN32
        CHECK(WaitForSingleObject(threads[i], 10000) == WAIT_OBJECT_0); CHECK(CloseHandle(threads[i]));
#else
        CHECK(pthread_join(threads[i], NULL) == 0);
#endif
        CHECK(!contexts[i].failed);
    }
    CHECK(dr_query(&store, "redirect.test", 1, 1, 0, 1).generation == 1000);
    dr_dispose(&store); puts("4 native readers / 1000 publications / 200000 reads passed");
}
static void write_file(const char *path, const char *text) {
    FILE *file = fopen(path, "wb"); CHECK(file); CHECK(fwrite(text, 1, strlen(text), file) == strlen(text)); CHECK(fclose(file) == 0);
}
static void production_adapter(const char *directory) {
    char path[4096]; CHECK(snprintf(path, sizeof(path), "%s/domain_allowlist.txt", directory) > 0);
    struct da_repository repository = DA_REPOSITORY_INITIALIZER;
    write_file(path, "@@||ads.test^\nhttps://legacy.test/path\n*.wild.test\n");
    struct da_result result = da_lookup(&repository, "sub.ads.test", directory);
    CHECK(result.allowed && result.update_valid && result.generation == 1);
    CHECK(da_lookup(&repository, "legacy.test", directory).allowed);
    CHECK(da_lookup(&repository, "wild.test", directory).allowed);
    CHECK(da_lookup(&repository, "notads.test", directory).generation == 1);
    write_file(path, "@@||new.test^\nbad..test\n");
    result = da_lookup(&repository, "ads.test", directory);
    CHECK(result.allowed && !result.update_valid && result.generation == 1);
    CHECK(!da_lookup(&repository, "new.test", directory).allowed);
    write_file(path, "@@||new.test^/path\n@@||new.test^$script\n");
    result = da_lookup(&repository, "new.test", directory);
    CHECK(!result.allowed && result.update_valid && result.generation == 2);
    write_file(path, "new.test\n"); CHECK(da_lookup(&repository, "new.test", directory).allowed);
    CHECK(remove(path) == 0);
    result = da_lookup(&repository, "new.test", directory); CHECK(!result.allowed && result.update_valid);
    dr_dispose(&repository.rules);
    puts("Actual production exemption loader: rollback, stable generation, legacy syntax and explicit deletion passed");
}
static void equality_ignores_padding(void) {
    struct dr_store store = DR_STORE_INITIALIZER;
    struct dr_builder builder = {0};
    store.current.entries = malloc(sizeof(struct dr_entry));
    builder.next.entries = malloc(sizeof(struct dr_entry));
    CHECK(store.current.entries && builder.next.entries);
    store.current.count = 1;
    builder.next.count = 1;
    memset(store.current.entries, 0xa5, sizeof(struct dr_entry));
    memset(builder.next.entries, 0x5a, sizeof(struct dr_entry));
    struct dr_entry *entries[] = {store.current.entries, builder.next.entries};
    for (unsigned i = 0; i < 2; i++) {
        snprintf(entries[i]->domain, sizeof(entries[i]->domain), "%s", "padding.test"); entries[i]->kind = DR_BLOCK;
        entries[i]->suffix = false; entries[i]->user = false; entries[i]->family = 0;
        memset(entries[i]->address, 0, sizeof(entries[i]->address));
    }
    CHECK(dr_same_rules(&store, &builder));
    builder.next.entries[0].suffix = true; CHECK(!dr_same_rules(&store, &builder));
    dr_dispose(&store); free(builder.next.entries);
    puts("Rule equality ignores structure padding and detects semantic scope changes");
}
int main(int argc, char **argv) {
    CHECK(argc == 3); vectors(argv[1]); rollback_and_priority(); concurrent(); production_adapter(argv[2]); equality_ignores_padding(); return 0;
}
