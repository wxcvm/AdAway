#define _POSIX_C_SOURCE 200809L
#include "block_rule_snapshot.h"
#include "subscription_import.h"
#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); exit(1); } } while (0)

static void vectors(const char *path) {
    FILE *vectors = fopen(path, "rb"); CHECK(vectors);
    FILE *staged = NULL; char line[2048], current[80] = ""; uint64_t orders[33] = {0};
    struct si_stats stats = {0}; unsigned queries = 0;
    while (fgets(line, sizeof(line), vectors)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        char *ending = strpbrk(line, "\r\n");
        if (ending) *ending = 0;
        char *fields[10]; fields[0] = line; unsigned count = 1;
        for (char *p = line; *p; p++) if (*p == '\t') { *p = 0; CHECK(count < 10); fields[count++] = p + 1; }
        CHECK(count == 6 || count == 10);
        if (strcmp(fields[1], current) != 0) {
            if (staged) CHECK(fclose(staged) == 0);
            staged = tmpfile(); CHECK(staged); CHECK(strlen(fields[1]) < sizeof(current)); strcpy(current, fields[1]);
            memset(orders, 0, sizeof(orders)); memset(&stats, 0, sizeof(stats));
        }
        if (strcmp(fields[0], "R") == 0) {
            CHECK(count == 6);
            unsigned source = (unsigned)atoi(fields[2]); CHECK(source < 33 && source > 0);
            struct si_sink sink = {staged, &stats, source, orders[source]};
            CHECK(dr_parse_line(fields[5], atoi(fields[3]) != 0, atoi(fields[4]) != 0, si_emit, &sink) != DR_INVALID);
            orders[source] = sink.order;
        } else {
            CHECK(strcmp(fields[0], "Q") == 0 && count == 10);
            CHECK(fflush(staged) == 0); rewind(staged);
            struct bs_builder builder; memset(&builder, 0, sizeof(builder));
            CHECK(bs_parse_file(staged, &builder)); CHECK(bs_finish(&builder));
            struct bs_store store = BS_STORE_INITIALIZER; CHECK(bs_publish(&store, &builder, 7));
            struct bs_result result = bs_decide(&store, fields[2], atoi(fields[3]), atoi(fields[4]));
            CHECK((int)result.decision.action == atoi(fields[5]));
            CHECK(result.source == (unsigned)atoi(fields[6])); CHECK(result.order == (uint64_t)atoi(fields[7]));
            CHECK((int)result.scope == atoi(fields[8])); CHECK(result.generation == 7);
            if (strcmp(fields[9], "-") == 0) CHECK(result.family == 0);
            else { uint8_t address[16] = {0}; CHECK(dr_ip(fields[9], result.family, address)); CHECK(memcmp(address, result.address, 16) == 0); }
            bs_dispose(&store); bs_free_builder(&builder); queries++;
            CHECK(fseek(staged, 0, SEEK_END) == 0);
        }
    }
    CHECK(!ferror(vectors)); CHECK(fclose(vectors) == 0); if (staged) CHECK(fclose(staged) == 0);
    CHECK(queries == 30); printf("%u shared typed parser/serialization/production snapshot decisions passed\n", queries);
}
static void failed_and_stale(void) {
    struct bs_store store = BS_STORE_INITIALIZER; struct bs_builder b; memset(&b, 0, sizeof(b));
    CHECK(bs_consume("@adaway-rule-v1\t2\t1\t2\t0\tredirect.test\t192.0.2.8", &b));
    CHECK(bs_finish(&b)); CHECK(bs_publish(&store, &b, 1)); bs_free_builder(&b);
    CHECK(!bs_consume("@adaway-rule-v1\t2\t2\t0\t3\tbad.test\t-", &b));
    CHECK(bs_query(&store, "redirect.test").source == 2);
    CHECK(bs_finish(&b)); CHECK(!bs_publish(&store, &b, 1)); bs_free_builder(&b);
    // Untrusted record metadata cannot introduce source zero, overflow or mismatched scope.
    struct tr_record r;
    CHECK(!tr_parse("@adaway-rule-v1\t0\t1\t0\t0\tbad.test\t-", &r));
    CHECK(!tr_parse("@adaway-rule-v1\t2\t18446744073709551616\t0\t0\tbad.test\t-", &r));
    CHECK(!tr_parse("@adaway-rule-v1\t2\t1\t0\t0\t*.bad.test\t-", &r));
    CHECK(!tr_parse("@adaway-rule-v1\t2\t1\t2\t0\tbad.test\tinvalid", &r));
    CHECK(bs_decide(&store, "redirect.test", 28, 1).decision.action == CORE_PASS);
    CHECK(bs_query(&store, "redirect.test").generation == 1); bs_dispose(&store);
}
static void indexed_wildcards(void) {
    struct bs_builder b; memset(&b, 0, sizeof(b));
    for (unsigned i = 0; i < 100000; i++) {
        char line[100]; snprintf(line, sizeof(line), "192.0.2.8 *.h%u.large.test", i);
        CHECK(bs_consume(line, &b));
    }
    CHECK(bs_finish(&b)); struct bs_store store = BS_STORE_INITIALIZER; CHECK(bs_publish(&store, &b, 1));
    struct bs_result result = bs_query(&store, "sub.h99999.large.test");
    CHECK(result.decision.action == CORE_REPLACE); CHECK(result.candidates < 100000);
    result = bs_query(&store, "unrelated.other.invalid"); CHECK(result.candidates < 100000);
    CHECK(result.decision.action == CORE_PASS); bs_dispose(&store); bs_free_builder(&b);
    puts("100000 wildcard rules use required-anchor buckets; no unconditional full scan");
}
int main(int argc, char **argv) { CHECK(argc == 2); vectors(argv[1]); failed_and_stale(); indexed_wildcards(); return 0; }
