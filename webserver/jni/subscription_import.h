#ifndef ADAWAY_SUBSCRIPTION_IMPORT_H
#define ADAWAY_SUBSCRIPTION_IMPORT_H
#include "domain_rule_snapshot.h"

struct si_stats { size_t entries, unsupported, cosmetics, bytes; };
struct si_sink { FILE *output; struct si_stats *stats; };
static inline bool si_emit(const struct dr_entry *entry, void *context) {
    struct si_sink *sink = (struct si_sink *)context;
    if (sink->stats->entries >= 500000u) return false;
    char address[64] = "", line[512]; int length;
    if (entry->kind == DR_REPLACE) {
#ifdef _WIN32
        if (!InetNtopA(entry->family == 4 ? AF_INET : AF_INET6, (void *)entry->address, address, sizeof(address))) return false;
#else
        if (!inet_ntop(entry->family == 4 ? AF_INET : AF_INET6, entry->address, address, sizeof(address))) return false;
#endif
        length = snprintf(line, sizeof(line), "%s %s\n", address, entry->domain);
    } else if (entry->suffix) {
        length = snprintf(line, sizeof(line), "%s||%s^\n", entry->kind == DR_ALLOW ? "@@" : "", entry->domain);
    } else length = snprintf(line, sizeof(line), "0.0.0.0 %s\n", entry->domain);
    if (length < 0 || (size_t)length >= sizeof(line) || (size_t)length > DR_MAX_BYTES - sink->stats->bytes) return false;
    if (fwrite(line, 1, (size_t)length, sink->output) != (size_t)length) return false;
    sink->stats->bytes += (size_t)length; sink->stats->entries++; return true;
}
static inline bool si_consume(const char *line, struct si_sink *sink) {
    enum dr_parse_status status = dr_parse_line(line, false, true, si_emit, sink);
    if (status == DR_UNSUPPORTED) sink->stats->unsupported++;
    if (status == DR_COSMETIC) sink->stats->cosmetics++;
    return status != DR_INVALID;
}
/* Validated semantic lines, not truncated host fragments, are written to the unpublished staging file. */
static inline bool si_import(FILE *source, FILE *output, struct si_stats *stats) {
    struct si_sink sink = {output, stats};
    char line[DR_MAX_LINE + 1]; size_t length = 0, bytes = 0; int c;
    while ((c = fgetc(source)) != EOF) {
        if (++bytes > DR_MAX_BYTES || !c) return false;
        if (c == '\n') { line[length] = 0; if (!si_consume(line, &sink)) return false; length = 0; }
        else { if (length == DR_MAX_LINE) return false; line[length++] = (char)c; }
    }
    if (ferror(source)) return false;
    line[length] = 0;
    return si_consume(line, &sink) && !ferror(output);
}
#endif
