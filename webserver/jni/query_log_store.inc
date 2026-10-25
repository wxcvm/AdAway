/* Shared bounded query log implementation. Included after req_host_of/top_host_add. */
#include "response_log.h"
#include "qlog_filter_contract.h"
enum { QLOG_PROXY = 0, QLOG_BLOCK = 1, QLOG_ALLOW = 2, QLOG_DENY = 3 };
struct qlog_entry {
    uint64_t ts;          /* epoch seconds */
    uint32_t uid;         /* 0xFFFFFFFF when unknown */
    uint16_t rtype;       /* blocked_* category hint (0 = unknown) */
    uint8_t  action;      /* QLOG_* */
    uint8_t  pad;
    char     host[192];
};
_Static_assert(sizeof(struct qlog_entry) == 208, "Legacy query log record ABI must stay unchanged");
static struct qlog_entry s_qlog[QLOG_MAX];
/* Keep the legacy record ABI and its response-mode byte intact. The separate
   byte follows the same ring slot through clear, compaction and publication. */
static uint8_t s_qlog_family[QLOG_MAX];
static struct response_log_metadata s_qlog_metadata[QLOG_MAX];
static uint32_t s_qlog_pos;    /* next slot to write */
static uint64_t s_qlog_sequence;
static char s_qlog_generation[33];
static uint32_t s_qlog_count;  /* valid entries (<= QLOG_MAX) */
/* Compaction buffer for persistence: only the newest QLOG_PERSIST_MAX entries
   are written, laid out from slot 0 so the loader rebuilds a contiguous ring. */
static struct qlog_entry s_qlog_persist[QLOG_PERSIST_MAX];
static uint8_t s_qlog_persist_family[QLOG_PERSIST_MAX];
static struct response_log_metadata s_qlog_persist_metadata[QLOG_PERSIST_MAX];

static void qlog_clear(void) {
    s_qlog_sequence = 0;
    s_qlog_generation[0] = 0;
    s_qlog_pos = 0;
    s_qlog_count = 0;
    memset(s_qlog, 0, sizeof(s_qlog));
    memset(s_qlog_family, 0, sizeof(s_qlog_family));
    memset(s_qlog_metadata, 0, sizeof(s_qlog_metadata));
}

/* Describe the observed client transport, never the spelling of its Host. A
   mapped IPv4 peer on a dual-stack socket is still an IPv4 request. */
static uint8_t qlog_connection_family(const struct mg_connection *connection) {
    static const uint8_t mapped[12] = {0,0,0,0,0,0,0,0,0,0,255,255};
    if (connection == NULL) return 0;
    if (!connection->rem.is_ip6 || !memcmp(connection->rem.addr.ip, mapped, sizeof(mapped))) return 4;
    return 6;
}

static void qlog_add_status(uid_t uid, int action, struct mg_http_message *hm, const struct mg_connection *connection, uint16_t status) {
    time_t observed = time(NULL);
    /* Zero timestamps are the legacy empty-slot sentinel. Do not publish a
       counted but unreadable row, or poison a previously valid disk snapshot,
       while a cold-boot clock is unset or the clock read fails. */
    if (observed <= 0) return;
    struct qlog_entry *e = &s_qlog[s_qlog_pos % QLOG_MAX];
    s_qlog_family[s_qlog_pos % QLOG_MAX] = qlog_connection_family(connection);
    struct response_log_metadata *metadata = &s_qlog_metadata[s_qlog_pos % QLOG_MAX];
    memset(metadata, 0, sizeof(*metadata));
    response_log_path(metadata, hm ? hm->uri.buf : NULL, hm ? hm->uri.len : 0);
    metadata->status = status >= 100 && status <= 599 ? status : 0;
    memset(e, 0, sizeof(*e));
    e->ts = (uint64_t) observed;
    e->uid = (uid == (uid_t) -1) ? 0xFFFFFFFFu : (uint32_t) uid;
    e->action = (uint8_t) action;
    /* "Why and how": a blocked row carries the policy type that matched and the
       mode that type is configured with (0xFFFF = nothing matched). */
    if (action == QLOG_BLOCK) {
        e->rtype = (uint16_t) (s_last_type < 0 ? 0xFFFF : s_last_type);
        e->pad = (uint8_t) s_last_mode;
    } else {
        e->rtype = 0xFFFF;
        e->pad = 0;
    }
    req_host_of(hm, e->host, sizeof(e->host));
    s_qlog_pos = (s_qlog_pos + 1) % QLOG_MAX;
    if (s_qlog_count < QLOG_MAX) s_qlog_count++;
    s_qlog_sequence++;
}

static void qlog_add(uid_t uid, int action, struct mg_http_message *hm, const struct mg_connection *connection) {
    qlog_add_status(uid, action, hm, connection, 0);
}

/* Paths are already validated UTF-8; only JSON quotation needs escaping. */
static void qlog_path_json(char *out, const char *path) {
    size_t written = 0;
    for (size_t i = 0; path[i]; i++) {
        if (path[i] == '"' || path[i] == '\\') out[written++] = '\\';
        out[written++] = path[i];
    }
    out[written] = 0;
}

/* Newest first, bounded (QLOG_RENDER_MAX entries / QLOG_JSON_MAX bytes). */
static void qlog_render(char *out, size_t cap) {
    size_t off = 0;
    if (out == NULL || cap == 0) return;
    out[0] = '\0';
    uint32_t total = s_qlog_count < QLOG_RENDER_MAX ? s_qlog_count : QLOG_RENDER_MAX;
    for (uint32_t k = 0; k < total; k++) {
        uint32_t idx = (s_qlog_pos + QLOG_MAX - 1 - k) % QLOG_MAX;
        struct qlog_entry *e = &s_qlog[idx];
        char host[400], path[RESPONSE_LOG_PATH_CAP * 2];
        int n;
        if (e->ts == 0) continue;
        json_safe_copy(host, sizeof(host), e->host);
        qlog_path_json(path, s_qlog_metadata[idx].path);
        n = snprintf(out + off, cap - off,
                     "%s{\"ts\":%llu,\"uid\":%d,\"action\":%d,\"type\":%d,"
                     "\"mode\":%d,\"family\":%u,\"host\":\"%s\",\"path\":\"%s\",\"status\":%u,\"pathTruncated\":%s}",
                     off ? "," : "", (unsigned long long) e->ts,
                     (e->uid == 0xFFFFFFFFu) ? -1 : (int) e->uid,
                     (int) e->action,
                     (e->rtype == 0xFFFF) ? -1 : (int) e->rtype,
                     (int) e->pad, (unsigned) s_qlog_family[idx], host, path,
                     (unsigned)s_qlog_metadata[idx].status,
                     s_qlog_metadata[idx].flags & RESPONSE_LOG_TRUNCATED ? "true" : "false");
        if (n <= 0 || off + (size_t) n >= cap) { out[off] = '\0'; break; }
        off += (size_t) n;
    }
}

/*
 * One page of the query log, newest first: offset skips the newest entries and
 * limit bounds how many are rendered, so /internal-qlog can walk the whole ring
 * without ever building one huge document.
 */
static uint32_t qlog_render_page(uint32_t offset, uint32_t limit, char *out, size_t cap) {
    size_t off = 0;
    if (out == NULL || cap == 0) return 0;
    out[0] = '\0';
    uint32_t rendered = 0;
    uint32_t total = s_qlog_count < QLOG_MAX ? s_qlog_count : QLOG_MAX;
    for (uint32_t k = offset; k < offset + limit && k < total; k++) {
        uint32_t idx = (s_qlog_pos + QLOG_MAX - 1 - k) % QLOG_MAX;
        struct qlog_entry *e = &s_qlog[idx];
        char host[400], path[RESPONSE_LOG_PATH_CAP * 2];
        int n;
        if (e->ts == 0) continue;
        json_safe_copy(host, sizeof(host), e->host);
        qlog_path_json(path, s_qlog_metadata[idx].path);
        n = snprintf(out + off, cap - off,
                     "%s{\"ts\":%llu,\"uid\":%d,\"action\":%d,\"type\":%d,"
                     "\"mode\":%d,\"family\":%u,\"host\":\"%s\",\"path\":\"%s\",\"status\":%u,\"pathTruncated\":%s}",
                     off ? "," : "", (unsigned long long) e->ts,
                     (e->uid == 0xFFFFFFFFu) ? -1 : (int) e->uid,
                     (int) e->action,
                     (e->rtype == 0xFFFF) ? -1 : (int) e->rtype,
                     (int) e->pad, (unsigned) s_qlog_family[idx], host, path,
                     (unsigned)s_qlog_metadata[idx].status,
                     s_qlog_metadata[idx].flags & RESPONSE_LOG_TRUNCATED ? "true" : "false");
        if (n <= 0 || off + (size_t) n >= cap) { out[off] = '\0'; break; }
        off += (size_t) n;
        rendered++;
    }
    return rendered;
}

#define QLOG_MAGIC 0x514C4F47u   /* "QLOG" */
#define QLOG_MAGIC_V2 0x514C4732u /* "QLG2": legacy records followed by count family bytes */
#define QLOG_MAGIC_V3 0x514C4733u /* "QLG3": QLG2 layout followed by count metadata records */
static void qlog_save(const char *resource_dir) {
    char path[PATH_MAX];
    if (resource_dir == NULL || resource_dir[0] == '\0') return;
    snprintf(path, sizeof(path), "%s/query_log.dat", resource_dir);
    /* 原子写：写 <path>.tmp，成功后 rename —— 读端永远不会看到写了一半的查询日志。 */
    char atomic_tmp[PATH_MAX];
    FILE *fp = open_atomic_write(path, atomic_tmp, sizeof(atomic_tmp));
    if (fp == NULL) return;
    /*
     * The ring holds QLOG_MAX (10k, ~3.6 MiB with response metadata) so the app
     * can page through history, but flushing all of it would write ~3.6 MiB
     * each time. Persist only the newest QLOG_PERSIST_MAX entries, compacted
     * from slot 0; the loader stores pos=0 and derives count from the header,
     * so it rebuilds a full contiguous ring.
     */
    uint32_t total = s_qlog_count < QLOG_MAX ? s_qlog_count : QLOG_MAX;
    uint32_t persist = total < QLOG_PERSIST_MAX ? total : QLOG_PERSIST_MAX;
    for (uint32_t k = 0; k < persist; k++) {
        uint32_t idx = (s_qlog_pos + QLOG_MAX - persist + k) % QLOG_MAX;
        s_qlog_persist[k] = s_qlog[idx];
        s_qlog_persist_family[k] = s_qlog_family[idx];
        s_qlog_persist_metadata[k] = s_qlog_metadata[idx];
    }
    uint32_t zero = 0;
    uint32_t magic = QLOG_MAGIC_V3;
    if (fwrite(&magic, sizeof(magic), 1, fp) != 1 ||
        fwrite(&zero, sizeof(zero), 1, fp) != 1 ||
        fwrite(&persist, sizeof(persist), 1, fp) != 1 ||
        fwrite(s_qlog_persist, sizeof(s_qlog_persist[0]), persist, fp) != persist ||
        fwrite(s_qlog_persist_family, 1, persist, fp) != persist ||
        fwrite(s_qlog_persist_metadata, sizeof(s_qlog_persist_metadata[0]), persist, fp) != persist) {
        fclose(fp);
        remove(atomic_tmp);
        return;
    }
    finish_atomic_write(fp, path, atomic_tmp);
}

static void qlog_load(const char *resource_dir) {
    char path[PATH_MAX];
    if (resource_dir == NULL || resource_dir[0] == '\0') return;
    snprintf(path, sizeof(path), "%s/query_log.dat", resource_dir);
    FILE *fp = fopen(path, "rb");
    if (fp == NULL) return;
    uint32_t magic = 0, pos = 0, count = 0;
    if (fread(&magic, sizeof(magic), 1, fp) == 1 && (magic == QLOG_MAGIC || magic == QLOG_MAGIC_V2 || magic == QLOG_MAGIC_V3) &&
        fread(&pos, sizeof(pos), 1, fp) == 1 &&
        fread(&count, sizeof(count), 1, fp) == 1 &&
        count <= QLOG_MAX && pos < QLOG_MAX &&
        (magic == QLOG_MAGIC || (pos == 0 && count <= QLOG_PERSIST_MAX))) {
        /* Validate in staging: a corrupt/truncated file cannot replace live rows.
           Full legacy rings may start at any position; compact files use zero. */
        struct qlog_entry *staged = calloc(count ? count : 1, sizeof(*staged));
        uint8_t *families = calloc(count ? count : 1, 1);
        struct response_log_metadata *metadata = calloc(count ? count : 1, sizeof(*metadata));
        if (staged != NULL && families != NULL && metadata != NULL) {
            bool valid = (pos == 0 || count == QLOG_MAX || pos == count) &&
                fread(staged, sizeof(*staged), count, fp) == count;
            /* Legacy syntax never recorded the socket family. Zero-initialized
               staging keeps it unknown; no inference from a literal/domain. */
            if (valid && magic != QLOG_MAGIC) valid = fread(families, 1, count, fp) == count;
            if (valid && magic == QLOG_MAGIC_V3) valid = fread(metadata, sizeof(*metadata), count, fp) == count;
            valid = valid && fgetc(fp) == EOF && !ferror(fp);
            for (uint32_t i = 0; valid && i < count; i++) {
                valid = staged[i].ts != 0 && staged[i].action <= (magic == QLOG_MAGIC_V3 ? QLOG_DENY : QLOG_ALLOW) &&
                    memchr(staged[i].host, 0, sizeof(staged[i].host)) != NULL &&
                    (families[i] == 0 || families[i] == 4 || families[i] == 6);
                const struct response_log_metadata *m = &metadata[i];
                const char *end = memchr(m->path, 0, sizeof(m->path));
                valid = valid && end != NULL && m->reserved == 0 && m->flags <= RESPONSE_LOG_TRUNCATED &&
                    (m->status == 0 || (m->status >= 100 && m->status <= 599));
                if (valid) {
                    struct response_log_metadata checked = {0};
                    response_log_path(&checked, m->path, (size_t)(end - m->path));
                    valid = !strcmp(checked.path, m->path) && (!m->flags || m->path[0] == '/');
                }
            }
            if (valid) {
                qlog_clear();
                memcpy(s_qlog, staged, count * sizeof(*staged));
                memcpy(s_qlog_family, families, count);
                memcpy(s_qlog_metadata, metadata, count * sizeof(*metadata));
                s_qlog_pos = pos ? pos : (uint32_t) (count % QLOG_MAX);
                s_qlog_count = count;
                s_qlog_sequence = count;
            }
        }
        free(staged);
        free(families);
        free(metadata);
    }
    fclose(fp);
}

/* Numeric parameters are bounded before converting, including on 32-bit builds. */
static bool qlog_number(struct mg_http_message *hm, const char *key, uint64_t maximum, uint64_t *value) {
    char text[24];
    int length = mg_http_get_var(&hm->query, key, text, sizeof(text));
    if (length == -4 || (length == -1 && hm->query.len == 0)) return true;
    if (length <= 0) return false;
    uint64_t parsed = 0;
    for (int i = 0; i < length; i++) {
        if (text[i] < '0' || text[i] > '9') return false;
        unsigned digit = (unsigned)(text[i] - '0');
        if (parsed > maximum / 10 || (parsed == maximum / 10 && digit > maximum % 10)) return false;
        parsed = parsed * 10 + digit;
    }
    *value = parsed;
    return true;
}

static bool qlog_ensure_generation(void) {
    if (s_qlog_generation[0]) return true;
    unsigned char bytes[16];
    if (!mg_random(bytes, sizeof(bytes))) return false;
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < sizeof(bytes); i++) {
        s_qlog_generation[2*i] = hex[bytes[i] >> 4];
        s_qlog_generation[2*i+1] = hex[bytes[i] & 15];
    }
    s_qlog_generation[32] = 0;
    return true;
}

static void qlog_reply_legacy(struct mg_connection *connection, struct mg_http_message *hm) {
    const char *headers = "Content-Type: application/json\r\nCache-Control: no-store\r\n";
    uint64_t offset = 0, limit = 200, anchor = s_qlog_sequence;
    char generation[34], anchor_text[24];
    int gen_length = mg_http_get_var(&hm->query, "generation", generation, sizeof(generation));
    int anchor_length = mg_http_get_var(&hm->query, "anchor", anchor_text, sizeof(anchor_text));
    bool has_generation = gen_length != -4 && !(gen_length == -1 && hm->query.len == 0);
    bool has_anchor = anchor_length != -4 && !(anchor_length == -1 && hm->query.len == 0);
    if (!qlog_number(hm, "offset", QLOG_MAX, &offset) ||
        !qlog_number(hm, "limit", 1000, &limit) || !limit ||
        !qlog_number(hm, "anchor", UINT64_MAX, &anchor) ||
        (has_generation && gen_length != 32) ||
        (has_generation != has_anchor)) {
        mg_http_reply(connection, 400, headers, "{\"error\":\"invalid_page\"}");
        return;
    }
    if (!qlog_ensure_generation()) {
        mg_http_reply(connection, 503, headers, "{\"error\":\"generation_unavailable\"}");
        return;
    }
    if ((gen_length > 0 && strcmp(generation, s_qlog_generation) != 0) || anchor > s_qlog_sequence) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}");
        return;
    }
    uint64_t drift = s_qlog_sequence - anchor;
    if (drift > s_qlog_count || (drift == s_qlog_count && anchor != 0)) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}");
        return;
    }
    uint32_t total = drift < s_qlog_count ? s_qlog_count - (uint32_t)drift : 0;
    uint32_t original_total = anchor < QLOG_MAX ? (uint32_t)anchor : QLOG_MAX;
    if (total != original_total || offset > total) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}");
        return;
    }
    static char page[QLOG_JSON_MAX];
    uint32_t returned = qlog_render_page((uint32_t)(offset + drift), (uint32_t)limit, page, sizeof(page));
    uint32_t next = (uint32_t)offset + returned;
    mg_http_reply(connection, 200, headers,
        "{\"offset\":%u,\"limit\":%u,\"total\":%u,\"returned\":%u,\"nextOffset\":%u,"
        "\"hasMore\":%s,\"generation\":\"%s\",\"anchor\":\"%llu\",\"entries\":[%s]}",
        (unsigned)offset, (unsigned)limit, total, returned, next, next < total ? "true" : "false",
        s_qlog_generation, (unsigned long long)anchor, page);
}

static bool qlog_filter_matches(const struct qlog_filter *f, uint32_t idx) {
    const struct qlog_entry *e = &s_qlog[idx];
    int uid = e->uid == 0xFFFFFFFFu ? -1 : (int)e->uid;
    int type = e->rtype == 0xFFFF ? -1 : (int)e->rtype;
    if (!e->ts || (f->has_action && e->action != f->action) ||
        (f->has_family && s_qlog_family[idx] != f->family) ||
        (f->has_type && type != f->type) || (f->has_uid && uid != f->uid)) return false;
    if (!f->query[0] || qlf_contains(e->host, f->query) || qlf_contains(s_qlog_metadata[idx].path, f->query)) return true;
    char status[6];
    if (!s_qlog_metadata[idx].status) return false;
    snprintf(status, sizeof(status), "%u", (unsigned)s_qlog_metadata[idx].status);
    return qlf_contains(status, f->query);
}

/* Exactly one bounded pass over the fixed snapshot. Count every match even
 * after the page byte budget fills; offsets count matches, never raw slots. */
static uint32_t qlog_render_filtered(const struct qlog_filter *filter, uint32_t drift,
                                    uint32_t retained, uint32_t offset, uint32_t limit,
                                    char *out, size_t cap, uint32_t *matched_total) {
    size_t written = 0;
    uint32_t matched = 0, rendered = 0;
    bool full = false;
    out[0] = 0;
    for (uint32_t k = 0; k < retained; k++) {
        uint32_t age = drift + k;
        uint32_t idx = (s_qlog_pos + QLOG_MAX - 1 - age) % QLOG_MAX;
        if (!qlog_filter_matches(filter, idx)) continue;
        matched++;
        if (matched <= offset || rendered >= limit || full) continue;
        const struct qlog_entry *e = &s_qlog[idx];
        char host[400], path[RESPONSE_LOG_PATH_CAP * 2];
        json_safe_copy(host, sizeof(host), e->host);
        qlog_path_json(path, s_qlog_metadata[idx].path);
        int n = snprintf(out + written, cap - written,
            "%s{\"ts\":%llu,\"uid\":%d,\"action\":%d,\"type\":%d,\"mode\":%d,"
            "\"family\":%u,\"host\":\"%s\",\"path\":\"%s\",\"status\":%u,\"pathTruncated\":%s,\"sequence\":\"%llu\"}",
            written ? "," : "", (unsigned long long)e->ts,
            e->uid == 0xFFFFFFFFu ? -1 : (int)e->uid, (int)e->action,
            e->rtype == 0xFFFF ? -1 : (int)e->rtype, (int)e->pad,
            (unsigned)s_qlog_family[idx], host, path, (unsigned)s_qlog_metadata[idx].status,
            s_qlog_metadata[idx].flags & RESPONSE_LOG_TRUNCATED ? "true" : "false",
            (unsigned long long)(s_qlog_sequence - age));
        if (n <= 0 || (size_t)n >= cap - written) { out[written] = 0; full = true; continue; }
        written += (size_t)n;
        rendered++;
    }
    *matched_total = matched;
    return rendered;
}

static bool qlog_v1_number(struct mg_http_message *hm, const char *name, uint64_t maximum, uint64_t *number) {
    char text[24];
    int present = qlf_parameter(hm->query, name, text, sizeof(text));
    if (!present) return true;
    if (present < 0 || !text[0]) return false;
    uint64_t value = 0;
    for (size_t i = 0; text[i]; i++) {
        if (text[i] < '0' || text[i] > '9') return false;
        unsigned digit = (unsigned)(text[i] - '0');
        if (value > maximum / 10 || (value == maximum / 10 && digit > maximum % 10)) return false;
        value = value * 10 + digit;
    }
    *number = value;
    return true;
}

static void qlog_reply(struct mg_connection *connection, struct mg_http_message *hm) {
    const char *headers = "Content-Type: application/json\r\nCache-Control: no-store\r\n";
    struct qlog_filter filter;
    int version = qlf_parse(hm->query, &filter);
    if (!version) { qlog_reply_legacy(connection, hm); return; }
    if (version < 0) {
        mg_http_reply(connection, 400, headers, "{\"error\":\"invalid_filter\"}"); return;
    }
    uint64_t offset = 0, limit = 200, anchor = s_qlog_sequence;
    char generation[34], anchor_text[24], key[66];
    int gen = qlf_parameter(hm->query, "generation", generation, sizeof(generation));
    int anch = qlf_parameter(hm->query, "anchor", anchor_text, sizeof(anchor_text));
    int keyed = qlf_parameter(hm->query, "queryKey", key, sizeof(key));
    if (!qlog_v1_number(hm, "offset", QLOG_MAX, &offset) ||
        !qlog_v1_number(hm, "limit", 1000, &limit) || !limit ||
        !qlog_v1_number(hm, "anchor", UINT64_MAX, &anchor) ||
        gen < 0 || anch < 0 || keyed < 0 || gen != anch || gen != keyed ||
        (gen && (!qlf_lower_hex(generation, 32) || !qlf_lower_hex(key, 64))) || (!gen && offset != 0)) {
        mg_http_reply(connection, 400, headers, "{\"error\":\"invalid_page\"}"); return;
    }
    if (gen && strcmp(key, filter.key)) {
        mg_http_reply(connection, 400, headers, "{\"error\":\"query_mismatch\"}"); return;
    }
    if (!qlog_ensure_generation()) {
        mg_http_reply(connection, 503, headers, "{\"error\":\"generation_unavailable\"}"); return;
    }
    if ((gen && strcmp(generation, s_qlog_generation)) || anchor > s_qlog_sequence) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}"); return;
    }
    uint64_t drift = s_qlog_sequence - anchor;
    uint32_t retained = drift < s_qlog_count ? s_qlog_count - (uint32_t)drift : 0;
    uint32_t original = anchor < QLOG_MAX ? (uint32_t)anchor : QLOG_MAX;
    if (drift > s_qlog_count || retained != original) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}"); return;
    }
    static char page[QLOG_JSON_MAX];
    uint32_t total = 0;
    /* Reserve room for the response envelope inside the existing byte cap. */
    uint32_t returned = qlog_render_filtered(&filter, (uint32_t)drift, retained, (uint32_t)offset,
        (uint32_t)limit, page, sizeof(page) - 1024, &total);
    if (offset > total) {
        mg_http_reply(connection, 409, headers, "{\"error\":\"cursor_expired\"}"); return;
    }
    uint32_t next = (uint32_t)offset + returned;
    mg_http_reply(connection, 200, headers,
        "{\"filterVersion\":1,\"queryKey\":\"%s\",\"retainedTotal\":%u,"
        "\"offset\":%u,\"limit\":%u,\"total\":%u,\"returned\":%u,\"nextOffset\":%u,"
        "\"hasMore\":%s,\"generation\":\"%s\",\"anchor\":\"%llu\",\"entries\":[%s]}",
        filter.key, retained, (unsigned)offset, (unsigned)limit, total, returned, next,
        next < total ? "true" : "false", s_qlog_generation, (unsigned long long)anchor, page);
}

