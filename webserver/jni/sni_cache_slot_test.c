#include <stdio.h>
#include <stdlib.h>

typedef struct ssl_ctx_st { int unused; } SSL_CTX;
#include "sni_cache_slot.h"

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)

int main(void) {
    SSL_CTX live = {0};
    struct sni_entry entries[4] = {
        {"restored.test", NULL, 9000},
        {"hot.test", &live, 100},
        {"expired.test", &live, 0},
        {"", NULL, 0}
    };
    struct sni_slot_match result = sni_match_slot(entries, 4, "restored.test", 120, 50);
    CHECK(result.slot == 0 && !result.reusable);
    result = sni_match_slot(entries, 4, "hot.test", 120, 50);
    CHECK(result.slot == 1 && result.reusable);
    result = sni_match_slot(entries, 4, "hot.test", 150, 50);
    CHECK(result.slot == 1 && !result.reusable);
    result = sni_match_slot(entries, 4, "expired.test", 120, 50);
    CHECK(result.slot == 2 && !result.reusable);
    result = sni_match_slot(entries, 4, "new.test", 120, 50);
    CHECK(result.slot == -1 && !result.reusable);
    result = sni_match_slot(entries, 4, "", 120, 50);
    CHECK(result.slot == -1 && !result.reusable);
    /* Restored metadata with a later monotonic timestamp is never a hit. */
    entries[0].ctx = &live;
    result = sni_match_slot(entries, 4, "restored.test", 120, 50);
    CHECK(result.slot == 0 && !result.reusable);
    /* Old snapshots can contain duplicate names: prefer the reusable context. */
    entries[0].ctx = NULL;
    strcpy(entries[3].hostname, "restored.test");
    entries[3].ctx = &live;
    entries[3].issued_at = 110;
    result = sni_match_slot(entries, 4, "restored.test", 120, 50);
    CHECK(result.slot == 3 && result.reusable);
    /* Replacing a cold slot in place makes the next lookup a true hit. */
    entries[3].hostname[0] = '\0';
    entries[0].ctx = &live;
    entries[0].issued_at = 120;
    result = sni_match_slot(entries, 4, "restored.test", 121, 50);
    CHECK(result.slot == 0 && result.reusable);
    puts("PASS: restored SNI slots, live hits, expiry, duplicate preference and new hostname");
    return 0;
}
