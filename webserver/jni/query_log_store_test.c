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
    unsigned first_returned = (unsigned)mg_json_get_long(body, "$.returned", -1);
    assert(first_returned > 0 && first_returned <= 300);
    assert(mg_json_get_long(body, "$.total", -1) == 600);
    char generation[33], query[200];
    memcpy(generation, s_qlog_generation, sizeof(generation));
    for (unsigned i = 601; i <= 620; i++) add(i);
    snprintf(query, sizeof(query), "offset=%u&limit=300&generation=%s&anchor=600", first_returned, generation);
    body = reply(query, 200);
    char *host = mg_json_get_str(body, "$.entries[0].host");
    char expected[192]; snprintf(expected, sizeof(expected), "request-%u.example.test", 600-first_returned);
    assert(host != NULL && strcmp(host, expected) == 0);
    free(host);
    assert(mg_json_get_long(body, "$.nextOffset", -1) == (long)(first_returned + mg_json_get_long(body, "$.returned", -1)));
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
    char path[64];
    struct mg_http_message request;
    struct mg_connection connection;
    memset(&request, 0, sizeof(request));
    memset(&connection, 0, sizeof(connection));
    connection.rem.is_ip6 = family == 6;
    connection.rem.addr.ip[0] = family == 6 ? 0x20 : 127;
    snprintf(host, sizeof(host), "request-%u.example.test", number);
    snprintf(path, sizeof(path), "/request-%u", number);
    request.uri = mg_str(path);
    request.headers[0].name = mg_str("Host");
    request.headers[0].value = mg_str(host);
    qlog_add_status((uid_t)-1, QLOG_BLOCK, &request, family ? &connection : NULL, (uint16_t)(200 + number % 400));
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
        char expected_path[64]; snprintf(expected_path,sizeof(expected_path),"/request-%u",number);
        assert(!strcmp(s_qlog_metadata[k].path,expected_path) && s_qlog_metadata[k].status==200+number%400 && !s_qlog_metadata[k].flags);
    }
    char path[PATH_MAX];
    snprintf(path, sizeof(path), "%s/query_log.dat", directory);
    FILE *file = fopen(path, "rb");
    assert(file && fseek(file, 0, SEEK_END) == 0);
    assert(ftell(file) == (long)(12 + QLOG_PERSIST_MAX * (sizeof(struct qlog_entry) + 1 + sizeof(struct response_log_metadata))));
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
        for (uint32_t i = 0; i < s_qlog_count; i++) assert(s_qlog_family[i] == 0 && !s_qlog_metadata[i].path[0] && !s_qlog_metadata[i].status && !s_qlog_metadata[i].flags);
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

static void long_path_pages(void) {
    uint32_t offset=0;
    char generation[33]={0};
    while (offset<1000) {
        char query[180];
        if (!offset) snprintf(query,sizeof(query),"offset=0&limit=1000");
        else snprintf(query,sizeof(query),"offset=%u&limit=1000&generation=%s&anchor=1000",offset,generation);
        struct mg_str body=reply(query,200);
        if (!offset) memcpy(generation,s_qlog_generation,sizeof(generation));
        long returned=mg_json_get_long(body,"$.returned",-1);
        long next=mg_json_get_long(body,"$.nextOffset",-1);
        assert(returned>0 && next==(long)offset+returned && next<=1000);
        int length=0; assert(mg_json_get(body,"$",&length)==0 && (size_t)length==body.len);
        bool truncated=false; assert(mg_json_get_bool(body,"$.entries[0].pathTruncated",&truncated) && truncated);
        offset=(uint32_t)next;
    }
}

static void response_metadata(const char *directory) {
    const char *uris[] = {"/internal-test?password=secret#fragment", "/favicon.ico", "https://secret.example/path",
                          "authority:443", "*", "/bad\001path", "/bad\xc0\xaf", "/bad\xed\xa0\x80", "/bad\xf4\x90\x80\x80", "/quote\"and\\slash"};
    const char *expected[] = {"/internal-test", "/favicon.ico", "", "", "", "", "", "", "", "/quote\"and\\slash"};
    qlog_clear();
    for (unsigned i = 0; i < sizeof(uris)/sizeof(uris[0]); i++) {
        struct mg_http_message hm; memset(&hm, 0, sizeof(hm)); hm.uri = mg_str(uris[i]);
        qlog_add_status((uid_t)-1, i == 0 ? QLOG_DENY : QLOG_BLOCK, &hm, NULL, i == 0 ? 403 : 200);
        assert(!strcmp(s_qlog_metadata[i].path, expected[i]) && !s_qlog_metadata[i].flags);
    }
    qlog_save(directory); qlog_clear(); qlog_load(directory);
    assert(s_qlog_count == sizeof(uris)/sizeof(uris[0]));
    for (unsigned i = 0; i < s_qlog_count; i++) {
        assert(!strcmp(s_qlog_metadata[i].path, expected[i]));
        assert(s_qlog_metadata[i].status == (i == 0 ? 403 : 200));
    }
    struct mg_str body = reply("limit=1", 200);
    char *path = mg_json_get_str(body, "$.entries[0].path");
    assert(path && !strcmp(path, expected[9])); free(path);
    assert(!strstr(body.buf, "password") && !strstr(body.buf, "fragment"));

    char long_path[700]; memset(long_path, 'a', sizeof(long_path)); long_path[0]='/';
    struct response_log_metadata metadata;
    memset(&metadata, 0, sizeof(metadata));
    response_log_path(&metadata, long_path, 159); assert(strlen(metadata.path)==159 && !metadata.flags);
    response_log_path(&metadata, long_path, 160); assert(strlen(metadata.path)==159 && metadata.flags==1);
    /* A multi-byte code point straddling the byte limit is omitted in full. */
    memcpy(long_path+158, "\xe6\xb1\x89", 3);
    response_log_path(&metadata, long_path, 161); assert(strlen(metadata.path)==158 && metadata.flags==1);
    long_path[650]=(char)0xff;
    response_log_path(&metadata, long_path, sizeof(long_path)); assert(!metadata.path[0] && !metadata.flags);
    long_path[650]='a';
    response_log_path(&metadata,"/c1\xc2\x80",6); assert(!metadata.path[0] && !metadata.flags);
    qlog_clear();
    for (unsigned i=0; i<1000; i++) {
        struct mg_http_message hm; memset(&hm,0,sizeof(hm)); hm.uri=mg_str_n(long_path, sizeof(long_path));
        qlog_add_status(42,QLOG_BLOCK,&hm,NULL,304);
    }
    qlog_save(directory); qlog_clear(); qlog_load(directory);
    assert(s_qlog_count==1000 && s_qlog_metadata[999].flags==1 && s_qlog_metadata[999].status==304);
    long_path_pages();
    /* Parser sees only this request's newly queued response. */
    const char responses[]="HTTP/1.1 403 Forbidden\r\n\r\nHTTP/1.1 200 OK\r\n\r\n";
    size_t second=strlen("HTTP/1.1 403 Forbidden\r\n\r\n");
    assert(response_log_status(responses,strlen(responses),0)==403);
    assert(response_log_status(responses,strlen(responses),second)==200);
    assert(!response_log_status(responses,second,second));
    assert(!response_log_status(responses,strlen(responses),strlen(responses)+1));
    assert(!response_log_status("HTTP/1.1 999 Bad\r\n",18,0));
}

static void v2_migration_and_corrupt_v3(const char *directory) {
    char path[PATH_MAX]; snprintf(path,sizeof(path),"%s/query_log.dat",directory);
    qlog_clear(); add_with_family(1,4); add_with_family(2,6);
    FILE *file=fopen(path,"wb"); assert(file);
    uint32_t header[]={QLOG_MAGIC_V2,0,2};
    assert(fwrite(header,sizeof(header),1,file)==1 && fwrite(s_qlog,sizeof(*s_qlog),2,file)==2 && fwrite(s_qlog_family,1,2,file)==2);
    assert(!fclose(file)); qlog_load(directory);
    for (unsigned i=0;i<2;i++) assert(!s_qlog_metadata[i].path[0] && !s_qlog_metadata[i].status && !s_qlog_metadata[i].flags);
    struct mg_http_message hm; memset(&hm,0,sizeof(hm)); hm.uri=mg_str("/internal-test?secret=never-store");
    qlog_add_status(42,QLOG_DENY,&hm,NULL,403);
    qlog_save(directory); qlog_clear(); qlog_load(directory);
    assert(s_qlog_count==3 && s_qlog[2].action==QLOG_DENY && s_qlog_metadata[2].status==403);
    reply("limit=1",200);
    char cursor[180]; snprintf(cursor,sizeof(cursor),"limit=1&generation=%s&anchor=3",s_qlog_generation);
    struct qlog_entry entries[3]; memcpy(entries,s_qlog,sizeof(entries));
    struct response_log_metadata metadata[3]; memcpy(metadata,s_qlog_metadata,sizeof(metadata));
    uint8_t families[3]; memcpy(families,s_qlog_family,sizeof(families));
    for (unsigned variant=0;variant<15;variant++) {
        struct response_log_metadata broken[3]; memcpy(broken,metadata,sizeof(broken));
        struct qlog_entry records[3]; memcpy(records,entries,sizeof(records));
        switch(variant) {
            case 0: broken[2].status=99; break;
            case 1: broken[2].status=600; break;
            case 2: broken[2].flags=2; break;
            case 3: broken[2].reserved=1; break;
            case 4: memset(broken[2].path,'a',sizeof(broken[2].path)); break;
            case 5: snprintf(broken[2].path,sizeof(broken[2].path),"http://absolute/path"); break; // NOSONAR: Invalid persisted metadata fixture, never fetched or opened; production TLS is unchanged.
            case 6: snprintf(broken[2].path,sizeof(broken[2].path),"/path?secret=value"); break;
            case 7: snprintf(broken[2].path,sizeof(broken[2].path),"/path#fragment"); break;
            case 8: broken[2].path[1]=1; break;
            case 9: broken[2].path[1]=(char)0xff; break;
            case 10: broken[2].path[0]=0; broken[2].flags=1; break;
            case 11: records[2].action=4; break;
            case 12: records[2].ts=0; break;
            default: break;
        }
        header[0]=QLOG_MAGIC_V3; header[2]=3;
        file=fopen(path,"wb"); assert(file);
        assert(fwrite(header,sizeof(header),1,file)==1 && fwrite(records,sizeof(records),1,file)==1 && fwrite(families,1,3,file)==3);
        size_t bytes=sizeof(broken)-(variant==13 ? 1 : 0);
        assert(fwrite(broken,1,bytes,file)==bytes);
        if(variant==14) assert(fputc(0,file)==0);
        assert(!fclose(file)); qlog_load(directory);
        assert(s_qlog_count==3 && s_qlog_pos==3 && s_qlog_sequence==3);
        assert(!memcmp(s_qlog,entries,sizeof(entries)) && !memcmp(s_qlog_metadata,metadata,sizeof(metadata)) && !memcmp(s_qlog_family,families,sizeof(families)));
        reply(cursor,200);
    }
}

static void filtered_add(unsigned number) {
    struct mg_http_message hm;
    struct mg_connection connection;
    memset(&hm, 0, sizeof(hm)); memset(&connection, 0, sizeof(connection));
    hm.headers[0].name = mg_str("Host");
    hm.headers[0].value = mg_str("same.example.test");
    hm.uri = mg_str(number <= 10 ? "/Needle/Path?private=never-store" : "/noise/path");
    if (number % 2) { connection.rem.is_ip6 = 1; connection.rem.addr.ip[15] = 1; }
    s_last_type = 2;
    s_last_mode = 0;
    qlog_add_status(number <= 10 ? 42 : 43, number <= 10 ? QLOG_BLOCK : QLOG_ALLOW,
                    &hm, &connection, number <= 10 ? 200 : 204);
}

static void filtered_history(const char *directory) {
    qlog_clear(); test_clock_enabled = true; test_clock_value = 1700000000;
    for (unsigned i = 1; i <= 600; i++) filtered_add(i);
    struct mg_str body = reply("limit=10", 200);
    assert(mg_json_get_long(body, "$.filterVersion", -1) == -1);
    assert(mg_json_get_long(body, "$.retainedTotal", -1) == -1);
    assert(mg_json_get_str(body, "$.entries[0].sequence") == NULL);
    body = reply("filterVersion=1&q=NEEDLE&limit=2", 200);
    assert(mg_json_get_long(body, "$.filterVersion", -1) == 1);
    assert(mg_json_get_long(body, "$.total", -1) == 10);
    assert(mg_json_get_long(body, "$.retainedTotal", -1) == 600);
    assert(mg_json_get_long(body, "$.returned", -1) == 2);
    char *key = mg_json_get_str(body, "$.queryKey");
    char *sequence = mg_json_get_str(body, "$.entries[0].sequence");
    assert(key && strlen(key) == 64 && sequence && !strcmp(sequence, "10")); free(sequence);
    char generation[33], query[600]; memcpy(generation, s_qlog_generation, sizeof(generation));
    for (unsigned i = 601; i <= 620; i++) filtered_add(i);
    snprintf(query, sizeof(query), "filterVersion=1&q=needle&offset=2&limit=2&generation=%s&anchor=600&queryKey=%s", generation, key);
    body = reply(query, 200);
    assert(mg_json_get_long(body, "$.retainedTotal", -1) == 600);
    assert(mg_json_get_long(body, "$.total", -1) == 10);
    sequence = mg_json_get_str(body, "$.entries[0].sequence"); assert(sequence && !strcmp(sequence, "8")); free(sequence);
    body = reply("filterVersion=1&q=needle&family=6&type=2&uid=42&action=1", 200);
    assert(mg_json_get_long(body, "$.total", -1) == 5);
    assert(mg_json_get_long(body, "$.entries[0].family", -1) == 6);
    sequence = mg_json_get_str(body, "$.entries[0].sequence"); assert(sequence && !strcmp(sequence, "9")); free(sequence);
    struct mg_http_message hm; memset(&hm, 0, sizeof(hm));
    hm.headers[0].name = mg_str("Host"); hm.headers[0].value = mg_str("denied.example.test");
    hm.uri = mg_str("/local/+space \xe6\xb1\x89");
    qlog_add_status((uid_t)-1, QLOG_DENY, &hm, NULL, 403);
    body = reply("filterVersion=1&family=0&type=-1&uid=-1&q=403", 200);
    assert(mg_json_get_long(body, "$.total", -1) == 1);
    assert(mg_json_get_long(body, "$.entries[0].action", -1) == QLOG_DENY);
    body = reply("filterVersion=1&q=%2BSPACE+%E6%B1%89", 200);
    assert(mg_json_get_long(body, "$.total", -1) == 1);
    body = reply("filterVersion=1&q=private", 200);
    assert(mg_json_get_long(body, "$.total", -1) == 0); /* URL query never stored/searched. */
    struct qlog_filter f1, f2;
    assert(qlf_parse(mg_str("filterVersion=1"), &f1) == 1);
    assert(qlf_parse(mg_str("q=&filterVersion=1"), &f2) == 1 && !strcmp(f1.key, f2.key));
    assert(!strcmp(f1.key, "6335df49c049a260eb4077f1cf319a66e88a8114bbed41877060d3ddcb1a9ac9"));
    assert(qlf_parse(mg_str("filterVersion=1&uid=00042&q=Needle"), &f1) == 1);
    assert(qlf_parse(mg_str("filterVersion=1&q=needle&uid=42"), &f2) == 1 && !strcmp(f1.key, f2.key));
    assert(qlf_parse(mg_str("filterVersion=1&q=+needle+&uid=42"), &f2) == 1 && strcmp(f1.key, f2.key));
    static const char *const invalid[] = {
        "filterVersion=2", "filterVersion=", "filterVersion=1&filterVersion=1", "q=needle",
        "filterVersion=1&action=1&%61ction=2", "filterVersion=1&family=5", "filterVersion=1&uid=2147483648",
        "filterVersion=1&type=65535", "filterVersion=1&action=-1", "filterVersion=1&q=%00",
        "filterVersion=1&q=%", "filterVersion=1&q=%GG", "filterVersion=1&q=%c0%af", "filterVersion=1&q=%ed%a0%80",
        "filterVersion=1&q=%c2%80", "filterVersion=1&q=%7f", "filterVersion=1&q=%0a", "filterVersion=1&q=%f4%90%80%80",
        "filterVersion=1&offset=0&offset=0", "filterVersion=1&limit=1&limit=2", "filterVersion=1&offset=1",
        "filterVersion=1&generation=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "filterVersion=1&anchor=600",
        "filterVersion=1&queryKey=0000000000000000000000000000000000000000000000000000000000000000"
    };
    for (size_t i = 0; i < sizeof(invalid)/sizeof(invalid[0]); i++) reply(invalid[i], 400);
    char long_query[850]; snprintf(long_query, sizeof(long_query), "filterVersion=1&q=");
    size_t prefix = strlen(long_query); memset(long_query+prefix, 'a', 191); long_query[prefix+191] = 0;
    reply(long_query, 200); long_query[prefix+191] = 'a'; long_query[prefix+192] = 0; reply(long_query, 400);
    snprintf(long_query, sizeof(long_query), "filterVersion=1&q=");
    for (size_t i = 0; i < 63; i++) strcat(long_query, "%E6%B1%89");
    strcat(long_query, "ab"); reply(long_query, 200); /* 191 bytes. */
    snprintf(long_query, sizeof(long_query), "filterVersion=1&q=");
    for (size_t i = 0; i < 64; i++) strcat(long_query, "%E6%B1%89");
    reply(long_query, 400); /* 192 valid UTF-8 bytes, never a prefix. */
    /* Percent expansion is not silently truncated, including valid >191-byte Unicode. */
    assert(qlf_parse(mg_str("filterVersion=1&q=%e6%b1%89"), &f1) == 1);
    snprintf(query, sizeof(query), "filterVersion=1&q=noise&generation=%s&anchor=600&queryKey=%s", generation, key);
    body = reply(query, 400); assert(strstr(body.buf, "query_mismatch"));
    snprintf(query, sizeof(query), "filterVersion=1&q=needle&generation=%s&anchor=600&queryKey=%s", generation, key);
    for (unsigned i = 622; i <= 10020; i++) filtered_add(i);
    reply(query, 409); /* Even an unreturned tail overwrite expires the fixed history. */
    qlog_save(directory); qlog_clear(); qlog_load(directory); reply(query, 409);
    body = reply("filterVersion=1", 200);
    assert(mg_json_get_long(body, "$.retainedTotal", -1) == QLOG_PERSIST_MAX);
    free(key); test_clock_enabled = false;
}

static void filtered_byte_budget_and_benchmark(void) {
    qlog_clear();
    struct mg_http_message hm; memset(&hm, 0, sizeof(hm));
    char host[192], path[160]; memset(host, 'a', sizeof(host)-1); host[191] = 0;
    memset(path, 'p', sizeof(path)-1); path[0] = '/'; path[159] = 0;
    hm.headers[0].name = mg_str("Host"); hm.headers[0].value = mg_str(host); hm.uri = mg_str(path);
    for (unsigned i = 0; i < QLOG_MAX; i++) qlog_add_status(42, QLOG_BLOCK, &hm, NULL, 200);
    char generation[33], key[65], query[300];
    uint32_t offset = 0, previous = QLOG_MAX+1;
    unsigned pages_count = 0;
    do {
        if (!offset) snprintf(query, sizeof(query), "filterVersion=1&limit=1000");
        else snprintf(query, sizeof(query), "filterVersion=1&limit=1000&offset=%u&generation=%s&anchor=%u&queryKey=%s", offset, generation, QLOG_MAX, key);
        struct mg_str body = reply(query, 200);
        assert(body.len <= QLOG_JSON_MAX);
        if (!offset) {
            memcpy(generation, s_qlog_generation, sizeof(generation));
            char *value = mg_json_get_str(body, "$.queryKey"); assert(value); memcpy(key, value, sizeof(key)); free(value);
        }
        long returned = mg_json_get_long(body, "$.returned", -1);
        assert(returned > 0 && returned < 1000);
        assert(mg_json_get_long(body, "$.total", -1) == QLOG_MAX);
        for (long n = 0; n < returned; n++) {
            char expression[80]; snprintf(expression, sizeof(expression), "$.entries[%ld].sequence", n);
            char *value = mg_json_get_str(body, expression); assert(value);
            unsigned sequence = (unsigned)strtoul(value, NULL, 10); free(value);
            assert(sequence + 1 == previous); previous = sequence;
        }
        offset = (uint32_t)mg_json_get_long(body, "$.nextOffset", -1);
        bool more; assert(mg_json_get_bool(body, "$.hasMore", &more) && more == (offset < QLOG_MAX));
        pages_count++;
    } while (offset < QLOG_MAX);
    assert(previous == 1 && pages_count > 1);
    uint64_t started = mg_millis();
    for (unsigned i = 0; i < 30; i++) {
        struct mg_str body = reply("filterVersion=1&q=absent", 200);
        assert(mg_json_get_long(body, "$.total", -1) == 0);
    }
    printf("{\"fixture\":\"full-history-filter\",\"retained\":%u,\"pages\":%u,\"maxScanPerQuery\":%u,\"benchmarkQueries\":30,\"benchmarkTotalMs\":%llu}\n",
        QLOG_MAX, pages_count, QLOG_MAX, (unsigned long long)(mg_millis()-started));
}

struct filtered_http_client { bool query; unsigned family; int done; };
static void filtered_http_server(struct mg_connection *c, int event, void *data) {
    if (event != MG_EV_HTTP_MSG) return;
    struct mg_http_message *hm = data;
    if (mg_match(hm->uri, mg_str("/internal-qlog"), NULL)) qlog_reply(c, hm);
    else {
        qlog_add_status(42, QLOG_BLOCK, hm, c, 200);
        mg_http_reply(c, 200, "Content-Type: image/test\r\n", "image-fixture");
    }
}
static void filtered_http_client(struct mg_connection *c, int event, void *data) {
    struct filtered_http_client *test = c->fn_data;
    if (event == MG_EV_CONNECT) {
        if (test->query) mg_printf(c, "GET /internal-qlog?filterVersion=1&family=%u&q=NEEDLE HTTP/1.1\r\nHost: same.example.test\r\nConnection: close\r\n\r\n", test->family);
        else mg_printf(c, "GET /Needle/Proof HTTP/1.1\r\nHost: same.example.test\r\nConnection: close\r\n\r\n");
    }
    if (event == MG_EV_HTTP_MSG) {
        struct mg_http_message *hm = data;
        if (test->query) {
            assert(mg_json_get_long(hm->body, "$.filterVersion", -1) == 1);
            assert(mg_json_get_long(hm->body, "$.total", -1) == 1);
            assert(mg_json_get_long(hm->body, "$.entries[0].family", -1) == (long)test->family);
            assert(mg_json_get_long(hm->body, "$.entries[0].status", -1) == 200);
            char *sequence = mg_json_get_str(hm->body, "$.entries[0].sequence");
            assert(sequence && !strcmp(sequence, "1")); free(sequence);
        } else assert(hm->body.len == strlen("image-fixture") && !memcmp(hm->body.buf, "image-fixture", hm->body.len));
        test->done = 1; c->is_closing = 1;
    }
    if (event == MG_EV_ERROR) test->done = -1;
}
static void live_filtered_history(const char *bind, unsigned family) {
    struct mg_mgr manager; mg_mgr_init(&manager);
    struct mg_connection *listener = mg_http_listen(&manager, bind, filtered_http_server, NULL);
    assert(listener);
    char url[128]; mg_snprintf(url, sizeof(url), "%s:%c%c%M", "http", '/', '/', mg_print_ip_port, &listener->loc);
    qlog_clear();
    for (unsigned phase = 0; phase < 2; phase++) {
        struct filtered_http_client test = {phase != 0, family, 0};
        assert(mg_http_connect(&manager, url, filtered_http_client, &test));
        uint64_t deadline = mg_millis()+3000;
        while (!test.done && mg_millis() < deadline) mg_mgr_poll(&manager, 5);
        assert(test.done == 1 && s_qlog_count == 1 && s_qlog_sequence == 1);
        /* Closing the client before the stack test context goes away avoids
         * a late close callback retaining the previous phase's context. */
        for (struct mg_connection *c = manager.conns; c; c = c->next)
            if (!c->is_listening && c->fn == filtered_http_client) c->is_closing = 1;
        mg_mgr_poll(&manager, 0);
    }
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
    response_metadata(argv[1]);
    v2_migration_and_corrupt_v3(argv[1]);
    filtered_history(argv[1]);
    filtered_byte_budget_and_benchmark();
    live_family("http://127.0.0.1:0", 4);
    live_filtered_history("http://127.0.0.1:0", 4);
#if MG_ENABLE_IPV6
    live_family("http://[::1]:0", 6);
    live_filtered_history("http://[::1]:0", 6);
#endif
    puts("PASS: production query log persistence, rollback, bounded pages, stable cursor, family metadata/mapped peers, QLOG/QLG2 migration, QLG3 metadata/corruption, private UTF-8 bounded paths, response offsets and actual IPv4/IPv6 HTTP round trips");
    return 0;
}
