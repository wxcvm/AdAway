#ifndef ADAWAY_SNI_CACHE_SLOT_H
#define ADAWAY_SNI_CACHE_SLOT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

/* SSL_CTX is declared by the including OpenSSL header (or the host fixture). */
struct sni_entry { char hostname[256]; SSL_CTX *ctx; uint64_t issued_at; };

struct sni_slot_match {
    int slot;
    bool reusable;
};

/* A restored hostname without a context is a slot match, not a TLS hit.
 * Prefer a live unexpired match if a legacy disk snapshot contains duplicates. */
static inline struct sni_slot_match sni_match_slot(
        const struct sni_entry *entries, size_t count, const char *host,
        uint64_t now, uint64_t renew_ms) {
    struct sni_slot_match match = {-1, false};
    if (!host || !*host) return match;
    for (size_t i = 0; i < count; ++i) {
        const struct sni_entry *entry = &entries[i];
        if (strcmp(entry->hostname, host) != 0) continue;
        if (match.slot < 0) match.slot = (int)i;
        if (entry->ctx && now >= entry->issued_at &&
            now - entry->issued_at < renew_ms) {
            match.slot = (int)i;
            match.reusable = true;
            break;
        }
    }
    return match;
}

#endif
