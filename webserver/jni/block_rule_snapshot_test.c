#define _POSIX_C_SOURCE 200809L
#include "block_rule_snapshot.h"
#include "subscription_import.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)
static FILE *text_file(const char *text) {
    FILE *file = tmpfile(); CHECK(file); CHECK(fwrite(text, 1, strlen(text), file) == strlen(text)); rewind(file); return file;
}
static bool update(struct bs_store *store, const char *text, uint64_t generation) {
    FILE *file = text_file(text); struct bs_builder builder; memset(&builder, 0, sizeof(builder));
    bool success = bs_parse_file(file, &builder); CHECK(fclose(file) == 0);
    if (success) { bs_finish(&builder); success = bs_publish(store, &builder, generation); }
    bs_free_builder(&builder); return success;
}
static void decision_and_rollback(void) {
    struct bs_store store = BS_STORE_INITIALIZER;
    CHECK(update(&store, "0.0.0.0 EXACT.TEST.\n0.0.0.0 exact.test\n||suffix.test^\n@@||allow.suffix.test^\n", 1));
    CHECK(bs_query(&store, "exact.test").count == 3);
    CHECK(bs_query(&store, "EXACT.TEST.").decision.action == CORE_NXDOMAIN);
    CHECK(bs_query(&store, "sub.exact.test").decision.action == CORE_PASS);
    CHECK(bs_query(&store, "sub.suffix.test").decision.action == CORE_NXDOMAIN);
    CHECK(bs_query(&store, "sub.allow.suffix.test").decision.reason == CORE_ALLOW_EXCEPTION);
    CHECK(!update(&store, "0.0.0.0 new.test\n999.1.1.1 bad.test\n", 2));
    CHECK(bs_query(&store, "exact.test").generation == 1);
    CHECK(!update(&store, "", 1));
    CHECK(update(&store, "||suffix.test^/path\n@@||other.test^$script\n", 2));
    CHECK(bs_query(&store, "suffix.test").count == 0);
    bs_dispose(&store); puts("Production block loader: exact/suffix/exception, deduplication and failed/stale update rollback passed");
}
static void subscription_pipeline(void) {
    FILE *input = text_file("0.0.0.0 one.test two.test\n@@||one.test^\n||new.test^/path\n192.0.2.8 redirect.test\nplain.test\n");
    FILE *output = tmpfile(); CHECK(output); struct si_stats stats = {0};
    CHECK(si_import(input, output, &stats)); CHECK(stats.entries == 5 && stats.unsupported == 1);
    rewind(output); struct bs_builder builder; memset(&builder, 0, sizeof(builder));
    CHECK(bs_parse_file(output, &builder)); bs_finish(&builder);
    struct bs_store store = BS_STORE_INITIALIZER; CHECK(bs_publish(&store, &builder, 1));
    CHECK(bs_query(&store, "one.test").decision.reason == CORE_ALLOW_EXCEPTION);
    CHECK(bs_query(&store, "two.test").decision.action == CORE_NXDOMAIN);
    CHECK(bs_query(&store, "plain.test").decision.action == CORE_NXDOMAIN);
    CHECK(bs_query(&store, "new.test").decision.action == CORE_PASS);
    CHECK(bs_query(&store, "redirect.test").decision.action == CORE_REPLACE);
    CHECK(bs_query(&store, "redirect.test").source == 2);
    CHECK(fclose(input) == 0); CHECK(fclose(output) == 0); bs_free_builder(&builder); bs_dispose(&store);
    input = text_file("0.0.0.0 valid.test\n999.1.1.1 invalid.test\n"); output = tmpfile(); CHECK(output); memset(&stats, 0, sizeof(stats));
    CHECK(!si_import(input, output, &stats)); CHECK(fclose(input) == 0); CHECK(fclose(output) == 0);
    puts("Actual subscription importer preserves exceptions/replacements and skips conditional rules without broadening");
}
struct reader_context { struct bs_store *store; int failed; };
#ifdef _WIN32
static DWORD WINAPI reader(void *opaque)
#else
static void *reader(void *opaque)
#endif
{
    struct reader_context *context = (struct reader_context *)opaque;
    for (unsigned i = 0; i < 50000; i++) {
        struct bs_result result = bs_query(context->store, "ads.test");
        if (!result.generation) continue;
        if (result.decision.action != (result.generation % 2 ? CORE_NXDOMAIN : CORE_PASS)
                || result.count != (result.generation % 2 ? 2u : 1u)) { context->failed = 1; break; }
    }
#ifdef _WIN32
    return 0;
#else
    return NULL;
#endif
}
static void concurrent(void) {
    struct bs_store store = BS_STORE_INITIALIZER; struct reader_context contexts[4];
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
    for (uint64_t i = 1; i <= 1000; i++) CHECK(update(&store, i % 2 ? "0.0.0.0 ads.test other.test\n" : "@@||ads.test^\n", i));
    for (unsigned i = 0; i < 4; i++) {
#ifdef _WIN32
        CHECK(WaitForSingleObject(threads[i], 10000) == WAIT_OBJECT_0); CHECK(CloseHandle(threads[i]));
#else
        CHECK(pthread_join(threads[i], NULL) == 0);
#endif
        CHECK(!contexts[i].failed);
    }
    bs_dispose(&store); puts("Production block snapshot: 4 readers / 1000 publications / 200000 consistent decisions+counts passed");
}
static void large_source(void) {
    FILE *file = tmpfile(); CHECK(file);
    for (unsigned i = 0; i < 300000; i++) CHECK(fprintf(file, "0.0.0.0 h%u.large.test\n", i) > 0);
    rewind(file); struct bs_builder builder; memset(&builder, 0, sizeof(builder));
    CHECK(bs_parse_file(file, &builder)); CHECK(fclose(file) == 0); bs_finish(&builder);
    struct bs_store store = BS_STORE_INITIALIZER; CHECK(bs_publish(&store, &builder, 1));
    CHECK(bs_query(&store, "h299999.large.test").count == 300000);
    CHECK(bs_query(&store, "h299999.large.test").decision.action == CORE_NXDOMAIN);
    bs_free_builder(&builder); bs_dispose(&store); puts("300000-entry production source loaded and last entry matched");
}
static void rejects_unterminated_domain(void) {
    struct dr_entry invalid = {0};
    struct bs_builder builder = {0};
    memset(invalid.domain, 'a', sizeof(invalid.domain));
    CHECK(!bs_append(&invalid, &builder)); CHECK(builder.next.count == 0);
    snprintf(invalid.domain, sizeof(invalid.domain), "%s", "valid.test"); builder.used = BS_MAX_POOL + 1;
    CHECK(!bs_append(&invalid, &builder)); CHECK(builder.next.count == 0);
    bs_free_builder(&builder);
    puts("Unterminated names and invalid pool offsets rejected before allocation/copy");
}
int main(void) { decision_and_rollback(); subscription_pipeline(); concurrent(); large_source(); rejects_unterminated_domain(); return 0; }
