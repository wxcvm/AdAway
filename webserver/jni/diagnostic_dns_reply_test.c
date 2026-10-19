#include "diagnostic_dns_reply.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

static size_t hex(const char *text, uint8_t *out) {
    if (!strcmp(text, "-")) return 0;
    size_t n = strlen(text); assert(!(n & 1) && n / 2 <= 514);
    for (size_t i = 0; i < n / 2; i++) {
        char part[3] = {text[i * 2], text[i * 2 + 1], 0}; char *end;
        unsigned long value = strtoul(part, &end, 16); assert(!*end && value <= 255);
        out[i] = (uint8_t)value;
    }
    return n / 2;
}
int main(int argc, char **argv) {
    assert(argc == 2); FILE *file = fopen(argv[1], "rb"); assert(file);
    char line[4096]; unsigned cases = 0;
    while (fgets(line, sizeof(line), file)) {
        if (line[0] == '#') continue;
        char *name = strtok(line, "\t"), *q = strtok(NULL, "\t"), *r = strtok(NULL, "\t"), *expected = strtok(NULL, "\t\r\n");
        assert(name && q && r && expected);
        uint8_t query[514], response[514]; size_t qn = hex(q, query), rn = hex(r, response);
        enum ddns_reply got = ddns_classify(query, qn, response, rn);
        if ((int)got != atoi(expected)) { fprintf(stderr, "case failed: %s\n", name); fclose(file); return 1; }
        cases++;
    }
    assert(!ferror(file)); assert(!fclose(file)); assert(cases == 53);
    uint8_t q[DDNS_QUERY_SIZE], response[514]; ddns_query(q, 42);
    uint8_t original[DDNS_QUERY_SIZE]; memcpy(original,q,sizeof(q));
    assert(!ddns_query_type(q,42,15) && !memcmp(original,q,sizeof(q)));
    uint32_t seed = 53537;
    for (unsigned i = 0; i < 10000; i++) {
        assert(ddns_query_type(q,42,i % 2 ? 1 : 28));
        seed = seed * 1664525u + 1013904223u; size_t n = seed % sizeof(response);
        for (size_t j = 0; j < n; j++) { seed = seed * 1664525u + 1013904223u; response[j] = (uint8_t)(seed >> 24); }
        (void)ddns_classify(q, sizeof(q), response, n);
    }
    printf("%u shared DNS reply cases and 10000 malformed packets passed\n", cases);
    return 0;
}
