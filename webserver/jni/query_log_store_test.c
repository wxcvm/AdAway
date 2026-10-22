/* Exercises the production include and real Mongoose request/response parsing. */
#include "mongoose/mongoose.h"
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "request_host.h"
#undef assert
#define assert(condition) do { if (!(condition)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition); exit(1); } } while (0)
#ifdef _WIN32
typedef unsigned int uid_t;
#endif
#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define QLOG_MAX 10000
#define QLOG_PERSIST_MAX 4096
#define QLOG_RENDER_MAX 400
#define QLOG_JSON_MAX 49152
static int s_last_type = -1, s_last_mode;
static void req_host_of(struct mg_http_message *hm, char *out, size_t cap) {
    const struct mg_str *header = hm ? mg_http_get_header(hm, "Host") : NULL;
    (void)request_host_parse(header ? header->buf : NULL, header ? header->len : 0, out, cap);
}
static void json_safe_copy(char *out, size_t cap, const char *host) {
    snprintf(out, cap, "%s", host); /* The fixture only generates DNS labels. */
}
static FILE *open_atomic_write(const char *path, char *tmp, size_t cap) {
    size_t length = strlen(path);
    if (length > cap || cap - length < 5) return NULL;
    memcpy(tmp, path, length);
    memcpy(tmp + length, ".tmp", 5);
    return fopen(tmp, "wb");
}
static void finish_atomic_write(FILE *fp, const char *path, const char *tmp) {
    assert(fclose(fp) == 0);
#ifdef _WIN32
    remove(path); /* Test adapter only; production overlay uses its platform helper. */
#endif
    assert(rename(tmp, path) == 0);
}
static bool test_clock_enabled;
static time_t test_clock_value;
static time_t query_log_test_time(time_t *output) {
    time_t value = test_clock_enabled ? test_clock_value : time(NULL);
    if (output) *output = value;
    return value;
}
#define time query_log_test_time
#include "query_log_store.inc"
#undef time

static void add(unsigned number) {
    char host[192];
    struct mg_http_message hm;
    memset(&hm, 0, sizeof(hm));
    snprintf(host, sizeof(host), "request-%u.example.test", number);
    hm.uri = mg_str("/query-log-fixture.gif");
    hm.headers[0].name = mg_str("Host");
    hm.headers[0].value = mg_str(host);
    qlog_add((uid_t)-1, QLOG_BLOCK, &hm, NULL);
}
static void newest_is(unsigned number) {
    char expected[192];
    snprintf(expected, sizeof(expected), "request-%u.example.test", number);
    assert(strcmp(s_qlog[(s_qlog_pos + QLOG_MAX - 1) % QLOG_MAX].host, expected) == 0);
}
static char response[QLOG_JSON_MAX + 2048];
static struct mg_str reply(const char *query, unsigned expected_status) {
    struct mg_connection c;
    struct mg_http_message request, parsed;
    char status[32];
    memset(&c, 0, sizeof(c));
    memset(&request, 0, sizeof(request));
    request.query = mg_str(query);
    qlog_reply(&c, &request);
    assert(c.send.len < sizeof(response));
    memcpy(response, c.send.buf, c.send.len);
    response[c.send.len] = 0;
    mg_iobuf_free(&c.send);
    snprintf(status, sizeof(status), "HTTP/1.1 %u", expected_status);
    if (strncmp(response, status, strlen(status)) != 0) fprintf(stderr, "Unexpected response: %.180s\n", response);
    assert(strncmp(response, status, strlen(status)) == 0);
    assert(mg_http_parse(response, strlen(response), &parsed) > 0);
    return parsed.body;
}
static void persistence(const char *directory) {
    static const unsigned counts[] = {0, 1, 400, 500, 4096, 7000, 10000, 12037};
    for (unsigned c = 0; c < sizeof(counts)/sizeof(counts[0]); c++) {
        unsigned count = counts[c];
        qlog_clear();
        for (unsigned i = 1; i <= count; i++) add(i);
        qlog_save(directory);
        qlog_clear();
        qlog_load(directory);
        unsigned persisted = count < QLOG_PERSIST_MAX ? count : QLOG_PERSIST_MAX;
        assert(s_qlog_count == persisted);
        for (unsigned k = 0; k < persisted; k++) {
            char expected[192];
            snprintf(expected, sizeof(expected), "request-%u.example.test", count - persisted + 1 + k);
            assert(strcmp(s_qlog[k].host, expected) == 0);
        }
        if (count) newest_is(count);
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    FILE *f = fopen(path, "wb");
    assert(f != NULL);
    uint32_t header[] = {QLOG_MAGIC, 0, 50};
    assert(fwrite(header, sizeof(header), 1, f) == 1);
    fclose(f);
    qlog_load(directory); /* Truncation preserves the last good in-memory ring. */
    assert(s_qlog_count == QLOG_PERSIST_MAX);
    newest_is(12037);
    remove(path);
}
static void pages(void) {
    qlog_clear();
    for (unsigned i = 1; i <= 600; i++) add(i);
    reply("", 200);
    struct mg_str body = reply("offset=0&limit=300", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 300);
    assert(mg_json_get_long(body, "$.total", -1) == 600);
    char generation[33], query[200];
    memcpy(generation, s_qlog_generation, sizeof(generation));
    for (unsigned i = 601; i <= 620; i++) add(i);
    snprintf(query, sizeof(query), "offset=300&limit=300&generation=%s&anchor=600", generation);
    body = reply(query, 200);
    char *host = mg_json_get_str(body, "$.entries[0].host");
    assert(host != NULL && strcmp(host, "request-300.example.test") == 0);
    free(host);
    assert(mg_json_get_long(body, "$.nextOffset", -1) == 600);
    assert(mg_json_get_long(body, "$.total", -1) == 600);
    static char small[250];
    assert(qlog_render_page(0, 1000, small, sizeof(small)) > 0);
    assert(qlog_render_page(0, 1000, small, sizeof(small)) < 1000);
    char summary[QLOG_JSON_MAX];
    qlog_render(summary, sizeof(summary));
    assert(strstr(summary, "request-620.example.test") != NULL);
    reply("offset=-1", 400);
    reply("offset=4294967296", 400);
    reply("limit=0", 400);
    reply("limit=", 400);
    reply("limit=1001", 400);
    reply("anchor=18446744073709551616", 400);
    reply("generation=invalid&anchor=600", 400);
    reply("anchor=600", 400);
    snprintf(query, sizeof(query), "offset=0&generation=%s&anchor=999999999999999999999999999", generation);
    reply(query, 400);
    snprintf(query, sizeof(query), "offset=300&limit=300&generation=%s&anchor=600", generation);
    for (unsigned i = 621; i <= 10100; i++) add(i);
    /* The original newest row still exists, but the requested tail was overwritten. */
    reply(query, 409);
    for (unsigned i = 10101; i <= 10600; i++) add(i);
    reply(query, 409);
    qlog_clear();
    reply(query, 409);
}
/* Boundary metadata uses the actual Mongoose boolean API; disk round trips
 * exercise the same compact record layout used by the production server. */
static void page_boundaries(void) {
    qlog_clear();
    struct mg_str body = reply("offset=0&limit=1", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 0);
    bool more = true;
    assert(mg_json_get_bool(body, "$.hasMore", &more) && !more);
    for (unsigned i = 1; i <= 5; i++) add(i);
    body = reply("offset=5&limit=10", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 0);
    assert(mg_json_get_long(body, "$.nextOffset", -1) == 5);
    assert(mg_json_get_bool(body, "$.hasMore", &more) && !more);
    char query[200];
    snprintf(query, sizeof(query), "offset=5&limit=10&generation=%s&anchor=5", s_qlog_generation);
    body = reply(query, 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 0);
    reply("offset=6&limit=1", 409);
    body = reply("offset=0&limit=1", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 1);
    assert(mg_json_get_bool(body, "$.hasMore", &more) && more);
    body = reply("offset=0&limit=1000", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 5);
    assert(mg_json_get_bool(body, "$.hasMore", &more) && !more);
}

static void metadata(const char *directory) {
    qlog_clear();
    struct mg_http_message request;
    memset(&request, 0, sizeof(request));
    request.uri = mg_str("metadata.example.test");
    s_last_type = 3;
    s_last_mode = 2;
    qlog_add((uid_t)-1, QLOG_BLOCK, &request, NULL);
    qlog_add((uid_t)42, QLOG_ALLOW, &request, NULL);
    qlog_add((uid_t)7, QLOG_PROXY, &request, NULL);
    s_last_type = -1;
    s_last_mode = 0;
    qlog_add((uid_t)9, QLOG_BLOCK, &request, NULL);
    qlog_save(directory);
    qlog_clear();
    qlog_load(directory);
    struct mg_str body = reply("offset=0&limit=10", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 4);
    assert(mg_json_get_long(body, "$.entries[3].action", -1) == QLOG_BLOCK);
    assert(mg_json_get_long(body, "$.entries[3].type", 99) == 3);
    assert(mg_json_get_long(body, "$.entries[3].mode", 99) == 2);
    assert(mg_json_get_long(body, "$.entries[3].uid", 99) == -1);
    assert(mg_json_get_long(body, "$.entries[2].action", -1) == QLOG_ALLOW);
    assert(mg_json_get_long(body, "$.entries[2].uid", 99) == 42);
    assert(mg_json_get_long(body, "$.entries[2].type", 99) == -1);
    assert(mg_json_get_long(body, "$.entries[2].mode", 99) == 0);
    assert(mg_json_get_long(body, "$.entries[1].action", -1) == QLOG_PROXY);
    assert(mg_json_get_long(body, "$.entries[1].uid", 99) == 7);
    assert(mg_json_get_long(body, "$.entries[1].type", 99) == -1);
    assert(mg_json_get_long(body, "$.entries[1].mode", 99) == 0);
    assert(mg_json_get_long(body, "$.entries[0].type", 99) == -1);
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    assert(remove(path) == 0);
}

static void invalid_clock(const char *directory) {
    qlog_clear();
    test_clock_enabled = true;
    test_clock_value = 0;
    add(1);
    assert(s_qlog_count == 0 && s_qlog_sequence == 0);
    test_clock_value = 1;
    for (unsigned i = 1; i <= QLOG_MAX; i++) add(i);
    uint32_t position = s_qlog_pos;
    uint64_t sequence = s_qlog_sequence;
    struct qlog_entry previous = s_qlog[position];
    for (unsigned i = 0; i < 2; i++) {
        test_clock_value = i == 0 ? 0 : (time_t)-1;
        add(QLOG_MAX + 1);
        assert(s_qlog_count == QLOG_MAX && s_qlog_pos == position && s_qlog_sequence == sequence);
        assert(memcmp(&previous, &s_qlog[position], sizeof(previous)) == 0);
    }
    qlog_save(directory);
    qlog_clear();
    qlog_load(directory);
    assert(s_qlog_count == QLOG_PERSIST_MAX);
    newest_is(QLOG_MAX);
    test_clock_value = 2;
    add(QLOG_MAX + 2);
    newest_is(QLOG_MAX + 2);
    bool more = false;
    struct mg_str body = reply("offset=0&limit=1", 200);
    assert(mg_json_get_long(body, "$.returned", -1) == 1);
    assert(mg_json_get_bool(body, "$.hasMore", &more) && more);
    test_clock_enabled = false;
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    assert(remove(path) == 0);
}

static void host_authorities(void) {
    qlog_clear();
    const char *requests[] = {
        "GET /private?credential=secret HTTP/1.0\r\nHost: [2001:db8::1]:8443\r\n\r\n",
        "GET /private/secret HTTP/1.0\r\n\r\n"
    };
    /* Documentation-only authority parsed in memory; no network request. */
    const char *expected[] = {"2001:db8::1", "(unknown host)"};
    for (size_t i = 0; i < sizeof(requests)/sizeof(requests[0]); i++) {
        struct mg_http_message request;
        assert(mg_http_parse(requests[i], strlen(requests[i]), &request) > 0);
        qlog_add((uid_t)-1, QLOG_BLOCK, &request, NULL);
        struct mg_str body = reply("offset=0&limit=1", 200);
        char *observed = mg_json_get_str(body, "$.entries[0].host");
        assert(observed && !strcmp(observed, expected[i]));
        assert(!strstr(observed, "private") && !strstr(observed, "secret"));
        free(observed);
    }
}

static void add_with_family(unsigned number, unsigned family) {
    char host[192];
    struct mg_http_message request;
    struct mg_connection connection;
    memset(&request, 0, sizeof(request));
    memset(&connection, 0, sizeof(connection));
    connection.rem.is_ip6 = family == 6;
    connection.rem.addr.ip[0] = family == 6 ? 0x20 : 127;
    snprintf(host, sizeof(host), "request-%u.example.test", number);
    request.headers[0].name = mg_str("Host");
    request.headers[0].value = mg_str(host);
    qlog_add((uid_t)-1, QLOG_BLOCK, &request, family ? &connection : NULL);
}

static void family_metadata(const char *directory) {
    struct mg_http_message request;
    struct mg_connection connection;
    qlog_clear();
    memset(&request, 0, sizeof(request));
    memset(&connection, 0, sizeof(connection));
    request.headers[0].name = mg_str("Host");
    request.headers[0].value = mg_str("[2001:db8::1]:443");
    s_last_type = 13; s_last_mode = 255;
    qlog_add((uid_t)4, QLOG_BLOCK, &request, &connection); /* IPv6 Host on IPv4 socket. */
    connection.rem.is_ip6 = true;
    connection.rem.addr.ip[0] = 0x20;
    qlog_add((uid_t)6, QLOG_BLOCK, &request, &connection);
    memset(connection.rem.addr.ip, 0, sizeof(connection.rem.addr.ip));
    connection.rem.addr.ip[10] = 255;
    connection.rem.addr.ip[11] = 255;
    connection.rem.addr.ip[12] = 127;
    qlog_add((uid_t)44, QLOG_BLOCK, &request, &connection); /* Mapped peer remains IPv4. */
    qlog_add((uid_t)0, QLOG_ALLOW, &request, NULL); /* No reconstruction from Host. */
    qlog_save(directory);
    qlog_clear(); qlog_load(directory);
    static const unsigned expected[] = {4, 6, 4, 0};
    assert(s_qlog_count == 4);
    for (unsigned i = 0; i < 4; i++) assert(s_qlog_family[i] == expected[i]);
    struct mg_str body = reply("offset=0&limit=10", 200);
    assert(mg_json_get_long(body, "$.entries[0].family", -1) == 0);
    assert(mg_json_get_long(body, "$.entries[1].family", -1) == 4);
    assert(mg_json_get_long(body, "$.entries[2].family", -1) == 6);
    assert(mg_json_get_long(body, "$.entries[3].family", -1) == 4);
    assert(mg_json_get_long(body, "$.entries[3].type", -1) == 13);
    assert(mg_json_get_long(body, "$.entries[3].mode", -1) == 255);
    char summary[QLOG_JSON_MAX];
    qlog_render(summary, sizeof(summary));
    assert(strstr(summary, "\"family\":6") && strstr(summary, "\"family\":4") && strstr(summary, "\"family\":0"));
}

static unsigned family_for_number(unsigned number) {
    static const unsigned families[] = {0, 4, 6};
    return families[number % 3];
}

static void family_compaction(const char *directory) {
    qlog_clear();
    for (unsigned i = 1; i <= QLOG_MAX + 2037; i++) add_with_family(i, family_for_number(i));
    qlog_save(directory);
    qlog_clear(); qlog_load(directory);
    assert(s_qlog_count == QLOG_PERSIST_MAX);
    for (unsigned k = 0; k < QLOG_PERSIST_MAX; k++) {
        unsigned number = QLOG_MAX + 2037 - QLOG_PERSIST_MAX + 1 + k;
        char host[192];
        snprintf(host, sizeof(host), "request-%u.example.test", number);
        assert(!strcmp(s_qlog[k].host, host));
        assert(s_qlog_family[k] == family_for_number(number));
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    FILE *file = fopen(path, "rb");
    assert(file && fseek(file, 0, SEEK_END) == 0);
    assert(ftell(file) == (long)(12 + QLOG_PERSIST_MAX * (sizeof(struct qlog_entry) + 1)));
    fclose(file);
}

static void write_legacy(const char *directory, uint32_t count, uint32_t position) {
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    FILE *file = fopen(path, "wb");
    assert(file);
    uint32_t header[] = {QLOG_MAGIC, position, count};
    assert(fwrite(header, sizeof(header), 1, file) == 1);
    for (uint32_t i = 0; i < count; i++) {
        struct qlog_entry entry;
        memset(&entry, 0, sizeof(entry));
        entry.ts = 42; entry.uid = i; entry.rtype = 13; entry.action = QLOG_BLOCK; entry.pad = 255;
        snprintf(entry.host, sizeof(entry.host), "2001:db8::%u", i);
        assert(fwrite(&entry, sizeof(entry), 1, file) == 1);
    }
    assert(fclose(file) == 0);
}

static void legacy_family_migration(const char *directory) {
    const uint32_t counts[] = {3, QLOG_MAX};
    const uint32_t positions[] = {3, 17};
    for (unsigned variant = 0; variant < 2; variant++) {
        write_legacy(directory, counts[variant], positions[variant]);
        qlog_load(directory);
        assert(s_qlog_count == counts[variant] && s_qlog_pos == positions[variant]);
        for (uint32_t i = 0; i < s_qlog_count; i++) assert(s_qlog_family[i] == 0);
        struct mg_str body = reply("offset=0&limit=1", 200);
        assert(mg_json_get_long(body, "$.entries[0].family", -1) == 0);
        assert(mg_json_get_long(body, "$.entries[0].type", -1) == 13);
        assert(mg_json_get_long(body, "$.entries[0].mode", -1) == 255);
        qlog_save(directory); qlog_clear(); qlog_load(directory);
        assert(s_qlog_count == (counts[variant] < QLOG_PERSIST_MAX ? counts[variant] : QLOG_PERSIST_MAX));
        for (uint32_t i = 0; i < s_qlog_count; i++) assert(s_qlog_family[i] == 0 && s_qlog[i].pad == 255);
    }
}

static void corrupt_family_keeps_live_state(const char *directory) {
    char path[PATH_MAX];
    char cursor[160];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    qlog_clear(); add_with_family(1, 4); add_with_family(2, 6);
    reply("offset=0&limit=1", 200);
    snprintf(cursor, sizeof(cursor), "offset=0&limit=1&generation=%s&anchor=2", s_qlog_generation);
    struct qlog_entry before[2]; memcpy(before, s_qlog, sizeof(before));
    const uint8_t families[2] = {4,6};
    static const int invalid[] = {1,2,3,5,255,-1,-2,-3,-4};
    for (unsigned variant = 0; variant < sizeof(invalid)/sizeof(invalid[0]); variant++) {
        FILE *file = fopen(path, "wb"); assert(file);
        uint32_t header[] = {QLOG_MAGIC_V2, 0, 2};
        if (invalid[variant] == -3) header[1] = 1;
        if (invalid[variant] == -4) header[2] = QLOG_PERSIST_MAX + 1;
        assert(fwrite(header, sizeof(header), 1, file) == 1);
        assert(fwrite(before, sizeof(before), 1, file) == 1);
        uint8_t broken[3] = {4,6,0};
        if (invalid[variant] >= 0) broken[1] = (uint8_t)invalid[variant];
        size_t bytes = 2;
        if (invalid[variant] == -1) bytes = 1;
        else if (invalid[variant] == -2) bytes = 3;
        assert(fwrite(broken, 1, bytes, file) == bytes);
        assert(fclose(file) == 0);
        qlog_load(directory);
        assert(s_qlog_count == 2 && s_qlog_pos == 2 && s_qlog_sequence == 2);
        assert(!memcmp(before, s_qlog, sizeof(before)) && !memcmp(families, s_qlog_family, sizeof(families)));
        reply(cursor, 200);
    }
}

static void live_family_server(struct mg_connection *connection, int event, void *data) {
    if (event == MG_EV_HTTP_MSG) {
        qlog_add((uid_t)-1, QLOG_ALLOW, (struct mg_http_message *)data, connection);
        mg_http_reply(connection, 204, "", "");
    }
}
static void live_family_client(struct mg_connection *connection, int event, void *data) {
    (void)data;
    int *done = (int *)connection->fn_data;
    if (event == MG_EV_CONNECT) mg_printf(connection, "GET /family HTTP/1.1\r\nHost: [2001:db8::5]\r\nConnection: close\r\n\r\n");
    if (event == MG_EV_HTTP_MSG) { *done = 1; connection->is_closing = 1; }
    if (event == MG_EV_ERROR) *done = -1;
}
static void live_family(const char *bind, unsigned expected) {
    struct mg_mgr manager;
    mg_mgr_init(&manager);
    struct mg_connection *listener = mg_http_listen(&manager, bind, live_family_server, NULL);
    assert(listener);
    char url[128];
    /* Plain HTTP is the private ephemeral loopback fixture's protocol; this
       exercises real address-family reporting, not remote secure transport. */
    mg_snprintf(url, sizeof(url), "%s:%c%c%M", "http", '/', '/', mg_print_ip_port, &listener->loc);
    int done = 0;
    qlog_clear();
    assert(mg_http_connect(&manager, url, live_family_client, &done));
    uint64_t deadline = mg_millis() + 3000;
    while (!done && mg_millis() < deadline) mg_mgr_poll(&manager, 10);
    assert(done == 1 && s_qlog_count == 1 && s_qlog_family[0] == expected);
    struct mg_str body = reply("offset=0&limit=1", 200);
    assert(mg_json_get_long(body, "$.entries[0].family", -1) == (long)expected);
    mg_mgr_free(&manager);
}

int main(int argc, char **argv) {
    assert(argc == 2); /* Existing disposable directory supplied by the test runner. */
    mg_log_set(MG_LL_NONE);
    host_authorities();
    persistence(argv[1]);
    pages();
    page_boundaries();
    metadata(argv[1]);
    invalid_clock(argv[1]);
    family_metadata(argv[1]);
    family_compaction(argv[1]);
    legacy_family_migration(argv[1]);
    corrupt_family_keeps_live_state(argv[1]);
    live_family("http://127.0.0.1:0", 4);
#if MG_ENABLE_IPV6
    live_family("http://[::1]:0", 6);
#endif
    puts("PASS: production query log persistence, rollback, bounded pages, stable cursor, family metadata/mapped peers, QLOG migration, QLG2 rejection and actual IPv4/IPv6 HTTP round trips");
    return 0;
}
