#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>
#include <sys/stat.h>
#include <dirent.h>
#include <linux/limits.h>
#include <pthread.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/ec.h>
#include <openssl/pem.h>
#include <openssl/bio.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include "mongoose/mongoose.h"

#define THIS_FILE "WebServer"
/* BUG FIX: bind the loopback IP literal, not the hostname "localhost".
   mg_http_listen() resolves a hostname via getaddrinfo()/the hosts file
   before it can bind, so "localhost" here silently depends on AdAway's
   own hosts file (or /etc/hosts) still containing a working
   "127.0.0.1 localhost" line. AdAway's whole purpose is rewriting that
   file, so any transient state where that line is missing, stale, or
   still being synced turns into "web server failed to start" with no
   obvious cause. Binding 127.0.0.1 directly removes that dependency
   entirely, while intentionally NOT switching to 0.0.0.0 — that would
   expose the block-page server to the whole LAN instead of just this
   device, which is why an earlier attempt at 0.0.0.0 was reverted. */
#define HTTP_URL  "http://127.0.0.1:80"
#define HTTPS_URL "https://127.0.0.1:443"
/* Same services on the IPv6 loopback (::1), so IPv6-first clients
   (e.g. Android resolving "localhost" via ::1, NAT64/DNS64 setups)
   can reach the block server too. Optional: see main() — failure to
   bind these only logs a warning and the IPv4 listeners still serve. */
#define HTTP_URL_IPV6  "http://[::1]:80"
#define HTTPS_URL_IPV6 "https://[::1]:443"

/* BUG FIX: __android_log_print() only reaches logcat, never the process's
   own stdout/stderr. The Java side (ShellUtils.runBundledExecutable)
   launches this binary with `> logfile 2>&1`, expecting to capture
   startup failures (bad args, CA generation failure, port bind failure,
   etc.) in that file — but since nothing was ever written to
   stdout/stderr, that file was always empty regardless of what actually
   went wrong. Log to both destinations for anything that matters for
   diagnosing a failed/successful startup. */
#define LOG_FATAL(fmt, ...) do { \
    __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, fmt, ##__VA_ARGS__); \
    fprintf(stderr, "[FATAL] " fmt "\n", ##__VA_ARGS__); fflush(stderr); \
} while (0)
#define LOG_WARN(fmt, ...) do { \
    __android_log_print(ANDROID_LOG_WARN, THIS_FILE, fmt, ##__VA_ARGS__); \
    fprintf(stderr, "[WARN] " fmt "\n", ##__VA_ARGS__); fflush(stderr); \
} while (0)
#define LOG_INFO(fmt, ...) do { \
    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, fmt, ##__VA_ARGS__); \
    fprintf(stdout, "[INFO] " fmt "\n", ##__VA_ARGS__); fflush(stdout); \
} while (0)

#define OOM_ADJ_PATH   "/proc/self/oom_score_adj"
#define OOM_ADJ_NOKILL -1000   /* OOM_SCORE_ADJ_MIN */

#define MAX_CONNECTIONS   256
#define IDLE_TIMEOUT_MS   10000
/*
 * BUG FIX (usability): the sequential img_00.webp, img_01.webp, ...
 * scheme (see git history for count_block_images(), the previous
 * approach) required contiguous, zero-padded numbering - deleting one
 * image meant renaming every image after it to close the gap, since
 * scanning stopped at the first missing index. Scan the actual
 * directory contents instead and remember whichever filenames are
 * really there: delete any image, rename none of the others, add a
 * new one under any name matching the pattern - all just work. Also no
 * longer bound to the "two-digit index" naming scheme this session's
 * earlier fixes needed, since actual filenames (not reconstructed
 * ones) are what get served.
 */
#define BLOCK_IMAGE_MAX_COUNT 100
#define BLOCK_IMAGE_NAME_MAX  128

/* Scan resource_dir for files matching img_*.webp (case-sensitive,
   any suffix - "img_00.webp", "img_cat.webp", "img_2024-ad.webp" all
   match), storing up to BLOCK_IMAGE_MAX_COUNT filenames into out[].
   Returns the count found; always >= 1 (falls back to "img_00.webp"
   even if nothing was found, so the modulo below never divides by
   zero - the subsequent mg_http_serve_file() call will just 404 on a
   missing file in that degenerate case, rather than crash). */
static int scan_block_images(const char *resource_dir,
                             char out[][BLOCK_IMAGE_NAME_MAX]) {
    int count = 0;
    DIR *dir = opendir(resource_dir);
    if (!dir) {
        LOG_INFO("Could not open resource dir %s to scan for block images", resource_dir);
        snprintf(out[0], BLOCK_IMAGE_NAME_MAX, "img_00.webp");
        return 1;
    }
    struct dirent *entry;
    while (count < BLOCK_IMAGE_MAX_COUNT && (entry = readdir(dir)) != NULL) {
        const char *name = entry->d_name;
        size_t len = strlen(name);
        /* Match "img_*.webp" - starts with "img_", ends with ".webp",
           and has at least one character in between. */
        if (len > 9 /* strlen("img_x.webp") */
                && strncmp(name, "img_", 4) == 0
                && strcmp(name + len - 5, ".webp") == 0) {
            snprintf(out[count], BLOCK_IMAGE_NAME_MAX, "%s", name);
            count++;
        }
    }
    closedir(dir);
    if (count == 0) {
        LOG_INFO("No block placeholder images found in %s", resource_dir);
        snprintf(out[0], BLOCK_IMAGE_NAME_MAX, "img_00.webp");
        return 1;
    }
    LOG_INFO("Found %d block placeholder image(s) in %s", count, resource_dir);
    return count;
}

/* SNI per-domain certificate cache.
   OPTIMIZATION: 48 was tight for real browsing sessions — a single page
   with a dozen distinct ad/tracker domains, multiplied across a few
   tabs/apps, evicts entries before they get reused, forcing needless
   re-generation (even with the EC speedup above, still not free).
   256 entries at ~272 bytes each (hostname[256] + pointer + timestamp)
   is ~68KB — negligible for a process that otherwise stays resident.

   NOTE (threading): Mongoose 7.x is a single-threaded event loop —
   mg_mgr_poll() drives everything via poll()/epoll on one thread, so
   the SNI callback and all MG_EV handlers below run on that same
   thread and there is no real concurrency to guard against in this
   build (no pthread_create anywhere in mongoose.c). The mutex/atomics
   are nevertheless kept as defensive code: they cost ~nothing and
   keep this file correct if a threaded TLS dispatch or a different
   network-stack configuration is ever introduced.

   BUG FIX (cert expiry): per-domain leaf certs used to be issued with
   a 1-day validity and the cache had no expiry awareness at all. The
   web server is a long-running daemon (started at boot via
   BootReceiver), so after 24h of uptime every cache hit would present
   an already-expired certificate and browsers would refuse the
   connection with ERR_CERT_DATE_INVALID, with no way to recover short
   of restarting the server. Certs are now issued for
   SNI_CERT_VALIDITY_DAYS and each cache entry records when it was
   issued; a hit whose cert is past half its validity is treated as a
   miss and re-issued (see sni_callback()). */
#define SNI_CACHE_SIZE 256
#define SNI_CERT_VALIDITY_DAYS 30
#define SNI_CERT_RENEW_MS ((uint64_t) SNI_CERT_VALIDITY_DAYS * 86400000ULL / 2)
struct sni_entry { char hostname[256]; SSL_CTX *ctx; uint64_t issued_at; };
static struct sni_entry s_sni_cache[SNI_CACHE_SIZE];
static int              s_sni_pos = 0;
static pthread_mutex_t  s_sni_mutex = PTHREAD_MUTEX_INITIALIZER;

/* CA state shared with the SNI callback */
struct ca_state { X509 *cert; EVP_PKEY *key; };

/* Mirror of Mongoose 7.21 mg_tls struct for MG_TLS_OPENSSL.
   Matches mongoose.h: struct mg_tls { BIO_METHOD *bm; SSL_CTX *ctx; SSL *ssl; } */
struct mg_tls_openssl { BIO_METHOD *bm; SSL_CTX *ctx; SSL *ssl; };

static SSL_CTX *mg_conn_ssl_ctx(struct mg_connection *c) {
    if (!c->tls) return NULL;
    return ((struct mg_tls_openssl *)c->tls)->ctx;
}

/* ── Settings ─────────────────────────────────────────────────── */
struct settings {
    bool              init;
    struct mg_tls_opts tls_opts;
    char              resource_dir[PATH_MAX];
    char              test_path[PATH_MAX];
    struct ca_state   ca;
    bool              debug;
    int               block_image_count;
    char              block_images[BLOCK_IMAGE_MAX_COUNT][BLOCK_IMAGE_NAME_MAX];
};

/* ── Signal handling ──────────────────────────────────────────── */
static volatile sig_atomic_t s_sig_num = 0;
static void signal_handler(int n) { s_sig_num = n; }
static void setup_signal_handler(void) {
    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_IGN; sigaction(SIGPIPE, &sa, NULL);
    sa.sa_handler = signal_handler;
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP,  &sa, NULL);
}

/* ── OOM killer ───────────────────────────────────────────────── */
static void oom_adjust_setup(void) {
    FILE *fp = fopen(OOM_ADJ_PATH, "r+");
    if (!fp) return;
    char buf[32]; int n = fread(buf, 1, sizeof(buf)-1, fp); buf[n] = '\0';
    char *e; long cur = strtol(buf, &e, 10);
    if (e == buf) { fclose(fp); return; }
    if (OOM_ADJ_NOKILL < cur) {
        rewind(fp);
        if (fprintf(fp, "%d\n", OOM_ADJ_NOKILL) > 0)
            __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
                "OOM score: %ld → %d", cur, OOM_ADJ_NOKILL);
    }
    fclose(fp);
}

/* ── Certificate generation ───────────────────────────────────── */

/* Generate a certificate signed by ca_cert/ca_key for 'hostname'.
   If ca_cert==NULL the cert is self-signed (used for the root CA itself).
   san_override, if non-NULL, replaces the default "DNS:hostname" SAN
   string — used for the localhost leaf cert, which needs both
   "DNS:localhost" and "IP:127.0.0.1" since clients connecting to the
   literal loopback address check the IP SAN, not just the CN.
   use_ec selects EC (P-256) instead of RSA-2048 for the generated key.
   OPTIMIZATION: make_domain_ctx() calls this on every SNI cache miss —
   i.e. once per distinct ad/tracker domain the device visits that isn't
   already in the SNI cache (see SNI_CACHE_SIZE below). RSA-2048 keygen costs tens to
   low-hundreds of milliseconds on a phone CPU; EC P-256 keygen is
   consistently sub-millisecond, and P-256 server certs are supported by
   effectively every modern TLS client. Only the hot path (per-domain
   leaf certs) is switched — the root CA and the localhost leaf cert are
   each generated at most once per process start, so there's nothing to
   gain there and no reason to touch what's already known-working. */
static int make_cert(const char  *hostname,
                     X509        *ca_cert,   /* NULL → self-signed root CA */
                     EVP_PKEY    *ca_key,    /* signing key                */
                     int          is_ca,     /* 1 → add CA extensions      */
                     int          validity_days,
                     const char  *san_override,
                     int          use_ec,
                     X509       **out_cert,
                     EVP_PKEY   **out_key) {
    int ret = EXIT_FAILURE;
    EVP_PKEY_CTX *pctx = NULL;
    EVP_PKEY     *pkey = NULL;
    X509         *x    = NULL;
    X509_NAME    *name = NULL;

    if (use_ec) {
        pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_EC, NULL);
        if (!pctx || EVP_PKEY_keygen_init(pctx) <= 0 ||
            EVP_PKEY_CTX_set_ec_paramgen_curve_nid(pctx, NID_X9_62_prime256v1) <= 0 ||
            EVP_PKEY_keygen(pctx, &pkey) <= 0) goto done;
    } else {
        pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, NULL);
        if (!pctx || EVP_PKEY_keygen_init(pctx) <= 0 ||
            EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, 2048) <= 0 ||
            EVP_PKEY_keygen(pctx, &pkey) <= 0) goto done;
    }
    LOG_INFO("make_cert(%s): %s key generated", hostname, use_ec ? "EC" : "RSA");

    x = X509_new();
    if (!x) goto done;
    X509_set_version(x, 2);

    uint64_t serial = 0;
    if (RAND_bytes((unsigned char *)&serial, sizeof(serial)) != 1)
        serial = (uint64_t)mg_millis();
    serial &= 0x7FFFFFFFFFFFFFFFULL;
    ASN1_INTEGER_set_uint64(X509_get_serialNumber(x), serial);

    X509_gmtime_adj(X509_get_notBefore(x), -60);
    X509_gmtime_adj(X509_get_notAfter(x), (long)60*60*24*validity_days);
    X509_set_pubkey(x, pkey);

    name = X509_get_subject_name(x);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               (const unsigned char *)hostname, -1, -1, 0);

    /* If self-signed: issuer == subject.  Otherwise use the CA's subject. */
    if (ca_cert)
        X509_set_issuer_name(x, X509_get_subject_name(ca_cert));
    else
        X509_set_issuer_name(x, name);

    {
        X509V3_CTX ctx;
        X509V3_set_ctx_nodb(&ctx);
        X509V3_set_ctx(&ctx, ca_cert ? ca_cert : x, x, NULL, NULL, 0);

        /* SAN string: "DNS:hostname" unless the caller supplied an
           explicit override (see san_override doc comment above). */
        char san[288];
        if (san_override) {
            snprintf(san, sizeof(san), "%s", san_override);
        } else {
            snprintf(san, sizeof(san), "DNS:%s", hostname);
        }

        /* Key usage depends on role */
        const char *ku = is_ca
            ? "critical,digitalSignature,keyCertSign,cRLSign"
            : "critical,digitalSignature,keyEncipherment";

        struct { int nid; const char *val; } exts[] = {
            { NID_subject_alt_name,       san                     },
            { NID_key_usage,              ku                      },
            { NID_ext_key_usage,          "serverAuth"            },
            { NID_subject_key_identifier, "hash"                  },
            /* CA-only extensions */
            { is_ca ? NID_basic_constraints : 0,
              is_ca ? "critical,CA:TRUE"     : NULL               },
        };
        for (int i = 0; i < (int)(sizeof(exts)/sizeof(exts[0])); i++) {
            if (!exts[i].nid || !exts[i].val) continue;
            X509_EXTENSION *ext = X509V3_EXT_conf_nid(
                NULL, &ctx, exts[i].nid, exts[i].val);
            if (!ext) { LOG_FATAL("Ext %d failed", exts[i].nid); goto done; }
            int ok = X509_add_ext(x, ext, -1);
            X509_EXTENSION_free(ext);
            if (!ok) goto done;
        }
        LOG_INFO("make_cert(%s): extensions added", hostname);
    }

    EVP_PKEY *sign_key = ca_key ? ca_key : pkey;
    if (X509_sign(x, sign_key, EVP_sha256()) == 0) goto done;
    LOG_INFO("make_cert(%s): signed OK", hostname);

    *out_cert = x;  x = NULL;
    *out_key  = pkey; pkey = NULL;
    ret = EXIT_SUCCESS;

done:
    if (x)    X509_free(x);
    if (pkey) EVP_PKEY_free(pkey);
    if (pctx) EVP_PKEY_CTX_free(pctx);
    return ret;
}

/* Generate root CA cert + key and write as PEM files */
static int generate_root_ca(const char *cert_path, const char *key_path) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    int ret = make_cert("AdAway Root CA", NULL, NULL, 1, 3650, NULL, /*use_ec=*/0, &cert, &key);
    if (ret != EXIT_SUCCESS) return ret;
    ret = EXIT_FAILURE;
    FILE *f = NULL;
    /* BUG FIX: the previous code leaked the FILE* when PEM_write_*
       failed and never checked fclose()'s return value, so a full
       disk or I/O error could leave a truncated CA file on disk that
       would then be loaded (and trusted) on the next start. Fail
       loudly instead, and always release the handle. */
    f = fopen(key_path, "wb");
    if (!f) goto done;
    if (PEM_write_PrivateKey(f, key, NULL, NULL, 0, NULL, NULL) == 0) {
        fclose(f); f = NULL; goto done;
    }
    if (fclose(f) != 0) { f = NULL; goto done; }
    f = NULL;
    chmod(key_path, S_IRUSR|S_IWUSR);

    f = fopen(cert_path, "wb");
    if (!f) goto done;
    if (PEM_write_X509(f, cert) == 0) { fclose(f); f = NULL; goto done; }
    if (fclose(f) != 0) { f = NULL; goto done; }
    f = NULL;
    ret = EXIT_SUCCESS;
done:
    if (f) fclose(f);
    X509_free(cert); EVP_PKEY_free(key);
    return ret;
}

/* Load CA cert + key from PEM files into memory */
static int load_ca(const char *cert_path, const char *key_path,
                   struct ca_state *out) {
    FILE *fc = fopen(cert_path, "rb");
    FILE *fk = fopen(key_path,  "rb");
    if (!fc || !fk) { if (fc) fclose(fc); if (fk) fclose(fk); return EXIT_FAILURE; }
    out->cert = PEM_read_X509(fc, NULL, NULL, NULL);
    out->key  = PEM_read_PrivateKey(fk, NULL, NULL, NULL);
    fclose(fc); fclose(fk);
    return (out->cert && out->key) ? EXIT_SUCCESS : EXIT_FAILURE;
}

/* Serialize an X509 cert / private key to PEM into a freshly malloc'd
   buffer (via a memory BIO, then copied out so the result is owned by
   plain malloc/free like the rest of this file — not OpenSSL's
   allocator). Returns a zeroed mg_str on failure. */
static struct mg_str cert_to_pem_mgstr(X509 *cert) {
    struct mg_str out = {0};
    BIO *bio = BIO_new(BIO_s_mem());
    if (!bio) return out;
    if (PEM_write_bio_X509(bio, cert) != 1) { BIO_free(bio); return out; }
    char *data; long len = BIO_get_mem_data(bio, &data);
    char *buf = malloc((size_t)len);
    if (buf) { memcpy(buf, data, (size_t)len); out.buf = buf; out.len = (size_t)len; }
    BIO_free(bio);
    return out;
}
static struct mg_str key_to_pem_mgstr(EVP_PKEY *key) {
    struct mg_str out = {0};
    BIO *bio = BIO_new(BIO_s_mem());
    if (!bio) return out;
    if (PEM_write_bio_PrivateKey(bio, key, NULL, NULL, 0, NULL, NULL) != 1) {
        BIO_free(bio); return out;
    }
    char *data; long len = BIO_get_mem_data(bio, &data);
    char *buf = malloc((size_t)len);
    if (buf) { memcpy(buf, data, (size_t)len); out.buf = buf; out.len = (size_t)len; }
    BIO_free(bio);
    return out;
}

/* Issue the leaf cert used as the *default* TLS identity for direct
   connections to this device (https://localhost/..., https://127.0.0.1/...,
   https://[::1]/...) — i.e. anything that doesn't send SNI for a blocked
   ad domain and so never reaches sni_callback()/make_domain_ctx().
   BUG FIX: this used to be the raw root CA cert itself (CN "AdAway Root
   CA", no SAN matching "localhost" or "127.0.0.1" at all). Any client
   that checks the presented cert's SAN against the hostname/IP it
   dialed — which is effectively all of them — would fail to validate
   it even though the CA is trusted, since the CA's own identity isn't
   a valid SAN for the server it's terminating TLS for. Sign a proper
   short-lived leaf cert for "localhost" with DNS + IPv4 loopback +
   IPv6 loopback SANs (the server also listens on ::1) and use *that*
   as the default, matching how every other hostname already gets a
   purpose-issued leaf cert via make_domain_ctx(). */
static int make_localhost_leaf(struct ca_state *ca, struct mg_tls_opts *out_opts) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    if (make_cert("localhost", ca->cert, ca->key, 0, 397,
                  "DNS:localhost,IP:127.0.0.1,IP:0:0:0:0:0:0:0:1", /*use_ec=*/0, &cert, &key) != EXIT_SUCCESS)
        return EXIT_FAILURE;
    out_opts->cert = cert_to_pem_mgstr(cert);
    out_opts->key  = key_to_pem_mgstr(key);
    X509_free(cert);
    EVP_PKEY_free(key);
    return (out_opts->cert.buf && out_opts->key.buf) ? EXIT_SUCCESS : EXIT_FAILURE;
}

/* ── SNI per-domain certificate ───────────────────────────────── */

static SSL_CTX *make_domain_ctx(const char *hostname, struct ca_state *ca) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    if (make_cert(hostname, ca->cert, ca->key, 0, SNI_CERT_VALIDITY_DAYS,
                  NULL, /*use_ec=*/1, &cert, &key) != EXIT_SUCCESS)
        return NULL;
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (!ctx) goto fail;
    /*
     * BUG FIX: SSL_CTX_add_extra_chain_cert() takes ownership of the
     * passed X509 (it bumps the refcount), but only when it succeeds.
     * The previous inline X509_dup(ca->cert) leaked that dup on the
     * (rare) failure path — use_certificate/use_PrivateKey short-circuit
     * before the dup runs, but if add_extra_chain_cert itself fails the
     * dup was allocated and nobody would ever free it. Track it
     * explicitly so every failure path releases it exactly once, while
     * the success path leaves ownership with the SSL_CTX.
     */
    X509 *chain = X509_dup(ca->cert);
    if (!chain) { SSL_CTX_free(ctx); ctx = NULL; goto fail; }
    if (SSL_CTX_use_certificate(ctx, cert) != 1 ||
        SSL_CTX_use_PrivateKey(ctx, key)   != 1) {
        X509_free(chain);
        SSL_CTX_free(ctx); ctx = NULL; goto fail;
    }
    if (SSL_CTX_add_extra_chain_cert(ctx, chain) != 1) {
        X509_free(chain);
        SSL_CTX_free(ctx); ctx = NULL; goto fail;
    }
fail:
    if (cert) X509_free(cert);
    if (key)  EVP_PKEY_free(key);
    return ctx;
}

static int sni_callback(SSL *ssl, int *ad, void *arg) {
    (void)ad;  /* unused: required by OpenSSL callback signature */
    const char *host = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
    if (!host || strcmp(host, "localhost") == 0)
        return SSL_TLSEXT_ERR_OK;  /* keep default localhost cert */

    struct ca_state *ca = (struct ca_state *)arg;

    /*
     * NOTE (threading): Mongoose 7.x drives all connections from a
     * single mg_mgr_poll() event loop, so in this build the SNI
     * callback cannot actually run concurrently with itself. The mutex
     * is kept as cheap defensive code (see the cache definition above)
     * so this path stays correct if a threaded TLS dispatch is ever
     * introduced; it is not protecting against a real race today.
     *
     * BUG FIX (cert expiry): a cache hit is only reused while its cert
     * is still comfortably within validity (half of
     * SNI_CERT_VALIDITY_DAYS). Once past that, treat it as a miss so a
     * fresh cert gets issued — otherwise a long-running daemon would
     * keep serving expired certificates to returning clients.
     */

    /* Fast path: cache lookup under lock */
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++) {
        if (s_sni_cache[i].ctx && strcmp(s_sni_cache[i].hostname, host) == 0) {
            if (mg_millis() - s_sni_cache[i].issued_at < SNI_CERT_RENEW_MS) {
                SSL_set_SSL_CTX(ssl, s_sni_cache[i].ctx);
                pthread_mutex_unlock(&s_sni_mutex);
                return SSL_TLSEXT_ERR_OK;
            }
            /* Cert is near/at expiry: fall through and re-issue below.
               The stale entry stays until evicted by the insert (if
               that slot happens to be this one) — a later hit will
               simply find it expired again and re-issue. */
            break;
        }
    }
    pthread_mutex_unlock(&s_sni_mutex);

    /* Slow path: generate new cert outside the lock so we don't
       hold it through OpenSSL keygen (EC P-256 is fast, but still). */
    SSL_CTX *ctx = make_domain_ctx(host, ca);
    if (!ctx) {
        LOG_WARN("SNI: failed to create ctx for %s", host);
        return SSL_TLSEXT_ERR_NOACK;
    }

    /* Insert into cache under lock */
    pthread_mutex_lock(&s_sni_mutex);
    int pos = s_sni_pos % SNI_CACHE_SIZE;
    if (s_sni_cache[pos].ctx) SSL_CTX_free(s_sni_cache[pos].ctx);
    strncpy(s_sni_cache[pos].hostname, host, 255);
    s_sni_cache[pos].issued_at = mg_millis();
    s_sni_cache[pos].hostname[255] = '\0';  /* BUG FIX: strncpy doesn't
        NUL-terminate when truncating; guarantee it here.  A hostname >255
        bytes would leave the buffer unterminated, causing strcmp() above to
        read past the array boundary on subsequent lookups. */
    s_sni_cache[pos].ctx = ctx;
    s_sni_pos++;
    pthread_mutex_unlock(&s_sni_mutex);

    SSL_set_SSL_CTX(ssl, ctx);
    __android_log_print(ANDROID_LOG_DEBUG, THIS_FILE,
        "SNI: issued cert for %s (cache[%d])", host, pos);
    return SSL_TLSEXT_ERR_OK;
}

/* ── Connection counting ──────────────────────────────────────── */
/*
 * NOTE (threading): as with the SNI cache, Mongoose 7.x is a
 * single-threaded event loop, so this plain counter can never actually
 * tear in this build. The __atomic builtins below are kept as
 * defensive code (they compile to single instructions on ARM64 and
 * cost nothing) so the cap stays correct if a threaded dispatch is
 * ever introduced; they do not fix a real race today.
 */
static int s_active_connections = 0;

/* Helper: atomic fetch-and-add, returns the value *before* the add */
static inline int atomic_add_fetch(int *ptr, int val) {
    return __atomic_add_fetch(ptr, val, __ATOMIC_SEQ_CST);
}
static inline int atomic_sub_fetch(int *ptr, int val) {
    return __atomic_sub_fetch(ptr, val, __ATOMIC_SEQ_CST);
}
static inline int atomic_load(int *ptr) {
    return __atomic_load_n(ptr, __ATOMIC_SEQ_CST);
}

/* ── Blocked-request classification ───────────────────────────── */
/*
 * Classify a blocked request by its URI and reply with the most
 * realistic "empty" resource for that type, so ad SDKs think the
 * request succeeded (instead of retrying or erroring out).
 * Returns true if a reply was sent, false if the caller should fall
 * through to the default placeholder-image path (image requests keep
 * serving the user-configured block images).
 */
static bool uri_ends_with_ci(const struct mg_str uri, const char *suffix) {
    size_t n = strlen(suffix);
    if (uri.len < n) return false;
    return mg_strcasecmp(mg_str_n(uri.buf + uri.len - n, n), mg_str(suffix)) == 0;
}

static bool uri_contains_ci(const struct mg_str uri, const char *needle) {
    size_t n = strlen(needle);
    if (n == 0 || uri.len < n) return false;
    for (size_t i = 0; i + n <= uri.len; i++) {
        if (mg_strcasecmp(mg_str_n(uri.buf + i, n), mg_str(needle)) == 0) return true;
    }
    return false;
}

static bool reply_blocked_by_type(struct mg_connection *c, struct mg_http_message *hm) {
    struct mg_str u = hm->uri;

    /* Images & video thumbnails: fall through to the user-configured
       placeholder images (they're meant to be seen). */
    if (uri_ends_with_ci(u, ".jpg") || uri_ends_with_ci(u, ".jpeg") ||
        uri_ends_with_ci(u, ".png") || uri_ends_with_ci(u, ".gif") ||
        uri_ends_with_ci(u, ".webp") || uri_ends_with_ci(u, ".avif") ||
        uri_ends_with_ci(u, ".svg") || uri_ends_with_ci(u, ".ico") ||
        uri_ends_with_ci(u, ".bmp")) {
        return false;
    }

    /* JavaScript: empty script body, HTTP 200. Cached - the empty
       response never changes at runtime, so the client stops
       re-requesting it after the first time (saves battery/bandwidth
       on every page load). */
    if (uri_ends_with_ci(u, ".js") || uri_ends_with_ci(u, ".mjs")) {
        mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Stylesheets: empty CSS, HTTP 200. Cached like JS above. */
    if (uri_ends_with_ci(u, ".css")) {
        mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                              "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Fonts: HTTP 204 (no content needed). */
    if (uri_ends_with_ci(u, ".woff") || uri_ends_with_ci(u, ".woff2") ||
        uri_ends_with_ci(u, ".ttf") || uri_ends_with_ci(u, ".otf") ||
        uri_ends_with_ci(u, ".eot")) {
        mg_http_reply(c, 204, "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* No file extension - the browser's Sec-Fetch-Dest header is the
       most precise signal for what kind of resource this is (sent by
       all modern browsers, and more accurate than the URI for
       extension-less endpoints like /banner?id=123). AdGuard cannot
       annotate these requests itself - it redirects at the DNS layer,
       so the HTTP request never passes through it - but the browser
       already tells us the type. */
    struct mg_str *dest = mg_http_get_header(hm, "Sec-Fetch-Dest");
    if (dest != NULL && dest->len > 0) {
        if (mg_strcasecmp(*dest, mg_str("image")) == 0) {
            return false;  /* image request without extension → placeholder image */
        }
        if (mg_strcasecmp(*dest, mg_str("script")) == 0) {
            mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (mg_strcasecmp(*dest, mg_str("style")) == 0) {
            mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (mg_strcasecmp(*dest, mg_str("font")) == 0) {
            mg_http_reply(c, 204, "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        /* "empty" (XHR/fetch/beacon), "document", others: fall through
           to path-keyword matching below. */
    }

    /* Ad API endpoints: empty JSON object, HTTP 200. */
    if (uri_ends_with_ci(u, ".json") ||
        uri_contains_ci(u, "/ad") || uri_contains_ci(u, "/ads") ||
        uri_contains_ci(u, "/banner") || uri_contains_ci(u, "/feed") ||
        uri_contains_ci(u, "/recommend")) {
        mg_http_reply(c, 200, "Content-Type: application/json\r\n"
                              "Cache-Control: public, max-age=86400\r\n", "{}");
        return true;
    }

    /* Telemetry / analytics endpoints: HTTP 204. */
    if (uri_contains_ci(u, "/track") || uri_contains_ci(u, "/event") ||
        uri_contains_ci(u, "/log") || uri_contains_ci(u, "/collect") ||
        uri_contains_ci(u, "/pixel")) {
        mg_http_reply(c, 204, "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Heartbeats & connectivity probes: HTTP 204. /generate_204 and
       /204 are used by YouTube/Google to probe connectivity - they
       must return 204 (empty body) or the client thinks the network
       is broken. */
    if (uri_contains_ci(u, "/ping") || uri_contains_ci(u, "/heartbeat") ||
        uri_contains_ci(u, "/generate_204") || uri_contains_ci(u, "/204")) {
        mg_http_reply(c, 204, "Cache-Control: public, max-age=86400\r\n", "");
        return true;
    }

    /* Config endpoints: empty JSON, HTTP 200. */
    if (uri_contains_ci(u, "/config") || uri_contains_ci(u, "/settings")) {
        mg_http_reply(c, 200, "Content-Type: application/json\r\n"
                              "Cache-Control: public, max-age=86400\r\n", "{}");
        return true;
    }

    /* No Sec-Fetch-Dest header (older clients) - fall back to the
       Accept header, which browsers still send for every request:
       image/* → placeholder image, text/css → empty CSS,
       application/javascript → empty JS. This is the last chance to
       classify before the generic placeholder-image fallback. */
    struct mg_str *accept = mg_http_get_header(hm, "Accept");
    if (accept != NULL && accept->len > 0) {
        if (uri_contains_ci(*accept, "image/") ||
            uri_contains_ci(*accept, "image/*")) {
            return false;  /* image request → placeholder image */
        }
        if (uri_contains_ci(*accept, "text/css")) {
            mg_http_reply(c, 200, "Content-Type: text/css\r\n"
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
        if (uri_contains_ci(*accept, "application/javascript") ||
            uri_contains_ci(*accept, "text/javascript")) {
            mg_http_reply(c, 200, "Content-Type: application/javascript\r\n"
                                  "Cache-Control: public, max-age=86400\r\n", "");
            return true;
        }
    }

    /* Unknown request type: fall through to the placeholder image
       (better than a bare 204 - the user sees the block placeholder
       instead of a blank/broken slot, and it costs nothing extra). */
    return false;
}

/* ── HTTP event handler ───────────────────────────────────────── */
static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        /* Atomic cap check + increment (see the note on
           s_active_connections above — defensive, not a live race). */
        for (;;) {
            int cur = atomic_load(&s_active_connections);
            if (cur >= MAX_CONNECTIONS) {
                c->is_closing = 1; return;
            }
            if (__atomic_compare_exchange_n(&s_active_connections, &cur,
                    cur + 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST))
                break;
        }
        uint64_t t = mg_millis();
        memcpy(c->data, &t, sizeof(t));
        c->data[sizeof(t)] = 1;

        if (c->is_tls && c->fn_data) {
            struct settings *s = (struct settings *)c->fn_data;
            mg_tls_init(c, &s->tls_opts);

            /* Register SNI callback so we can issue per-domain certs */
            SSL_CTX *ctx = mg_conn_ssl_ctx(c);
            if (ctx) {
                SSL_CTX_set_tlsext_servername_callback(ctx, sni_callback);
                SSL_CTX_set_tlsext_servername_arg(ctx, &s->ca);
            }
        }
        return;
    }

    if (ev == MG_EV_CLOSE) {
        if (c->data[sizeof(uint64_t)]) atomic_sub_fetch(&s_active_connections, 1);
        return;
    }

    /* Idle-timeout enforcement */
    if (ev == MG_EV_POLL && c->data[sizeof(uint64_t)]) {
        uint64_t accepted_at; memcpy(&accepted_at, c->data, sizeof(accepted_at));
        if (mg_millis() - accepted_at > IDLE_TIMEOUT_MS) {
            c->is_draining = 1; return;
        }
    }

    if (ev != MG_EV_HTTP_MSG) return;
    struct mg_http_message *hm = (struct mg_http_message *)ev_data;
    struct settings *s = (struct settings *)c->fn_data;
    if (mg_match(hm->uri, mg_str("/internal-test"), NULL)) {
        struct mg_http_serve_opts o = {0};
        o.mime_types = "html=text/html";
        mg_http_serve_file(c, hm, s->test_path, &o);
        return;
    }

    /* Classify blocked requests by type and reply with the most
       realistic "empty" resource - see reply_blocked_by_type(). */
    if (reply_blocked_by_type(c, hm)) return;


    /* Random block image - serve whichever actual filename was found at
       scan time (see scan_block_images()), not a reconstructed
       img_%02d.webp - deleting/adding images doesn't require renaming
       the rest to stay contiguous. */
    uint64_t t; memcpy(&t, c->data, sizeof(t));
    int idx = (int)(t % (uint64_t)s->block_image_count);
    char img_path[PATH_MAX];
    snprintf(img_path, sizeof(img_path), "%s/%s", s->resource_dir, s->block_images[idx]);
    struct mg_http_serve_opts o = {0};
    o.mime_types = "webp=image/webp";
    /*
     * OPTIMIZATION: every blocked ad slot on every page load re-requests
     * this same placeholder image from the local server with no caching
     * hint at all, so the client re-fetches it every single time instead
     * of ever reusing a cached copy — needless disk I/O and CPU work on
     * a mobile device that may be handling this dozens of times a
     * minute. These images are static build resources that never change
     * at runtime, so let clients cache them.
     *
     * BUG FIX: this used to be "public, max-age=86400" - fine for the
     * built-in defaults, which really don't change at runtime, but the
     * app also lets a user replace them at any time via
     * WebServerUtils#setCustomBlockImage()/resetBlockImagesToDefault(),
     * which overwrite the same filenames in place. A client that had
     * already cached the old bytes under max-age=86400 won't even send
     * a new request - let alone a conditional one - for up to a day,
     * so a picked custom image (or a reset back to the default) could
     * silently not show up for hours. mg_http_serve_file() already
     * generates an ETag from each file's size+mtime and honors
     * If-None-Match (see mg_http_etag() in mongoose.c), so "no-cache"
     * keeps the win this header was added for - clients still cache the
     * bytes and, on every use, get back a cheap 304 with no body as
     * long as the file is actually unchanged - while making sure a
     * genuine content change (different size/mtime → different ETag) is
     * always picked up on the very next request instead of being stuck
     * behind a stale cache.
     */
    o.extra_headers = "Cache-Control: no-cache\r\n";
    mg_http_serve_file(c, hm, img_path, &o);
}

/* ── CLI parsing ──────────────────────────────────────────────── */
static struct settings parse_cli_parameters(int argc, char *argv[]) {
    struct settings s = {0};
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--resources") == 0 && i < argc-1) {
            const char *rpath = argv[++i];
            LOG_INFO("Resources dir: %s", rpath);

            char cert_path[PATH_MAX], key_path[PATH_MAX];
            snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", rpath);
            snprintf(key_path,  sizeof(key_path),  "%s/localhost-2410.key", rpath);

            /* Generate CA cert on first use */
            bool missing = (access(cert_path, F_OK) != 0 || access(key_path, F_OK) != 0);
            LOG_INFO("CA cert missing=%d (cert=%s key=%s)", missing, cert_path, key_path);
            if (missing) {
                LOG_INFO("Generating root CA…");
                if (generate_root_ca(cert_path, key_path) != EXIT_SUCCESS) {
                    LOG_FATAL("CA generation failed");
                    return s;
                }
                LOG_INFO("Root CA generated OK");
            }

            /* Load CA into memory for SNI signing */
            if (load_ca(cert_path, key_path, &s.ca) != EXIT_SUCCESS) {
                LOG_FATAL("Failed to load CA");
                return s;
            }
            LOG_INFO("CA loaded OK");

            /* TLS opts for the localhost listener: a leaf cert issued
               specifically for "localhost"/127.0.0.1, not the raw CA
               cert (see make_localhost_leaf() for why). */
            if (make_localhost_leaf(&s.ca, &s.tls_opts) != EXIT_SUCCESS) {
                LOG_FATAL("Failed to issue localhost leaf cert");
                return s;
            }
            LOG_INFO("localhost leaf cert issued OK");
            snprintf(s.resource_dir, sizeof(s.resource_dir), "%s", rpath);
            snprintf(s.test_path,    sizeof(s.test_path),    "%s/test.html", rpath);
            s.block_image_count = scan_block_images(rpath, s.block_images);
            s.init = true;
        } else if (strcmp(argv[i], "--debug") == 0) {
            s.debug = true;
        }
    }
    return s;
}

/* ── main ─────────────────────────────────────────────────────── */
int main(int argc, char *argv[]) {
    setsid();
    /* NOTE: do NOT redirect stdin/stdout/stderr to /dev/null here even
       though setsid() detaches us from the controlling terminal.
       ShellUtils.runBundledExecutable() launches this binary with
       `> logfile 2>&1` and relies on that file to diagnose startup
       failures (bad args, CA generation failure, port bind failure,
       ...) via LOG_FATAL/LOG_WARN/LOG_INFO, which write to both logcat
       and stdio. dup2()-ing the std fds to /dev/null would make that
       capture file permanently empty and silently defeat the
       diagnostic mechanism. The launching shell has already redirected
       our std fds, so there is no tty-sharing hazard to fix here. */

    /* DIAGNOSTIC CHECKPOINT 1: if this line never shows up in the log,
       the process is crashing during dynamic linking / static
       initialization (loading libssl.so/libcrypto.so/libc++_shared.so)
       before main() itself ever runs any of our code — a completely
       different class of bug than anything inside main()'s own logic. */
    LOG_INFO("main() entered, argc=%d", argc);

    struct settings s = parse_cli_parameters(argc, argv);
    if (!s.init) {
        LOG_FATAL("Bad parameters.");
        return EXIT_FAILURE;
    }
    if (s.debug) mg_log_set(MG_LL_DEBUG);

    oom_adjust_setup();

    struct mg_mgr mgr;
    mg_mgr_init(&mgr);

    if (!mg_http_listen(&mgr, HTTP_URL,  fn, &s)) {
        LOG_FATAL("HTTP bind failed (port 80).");
        mg_mgr_free(&mgr); return EXIT_FAILURE;
    }
    if (!mg_http_listen(&mgr, HTTPS_URL, fn, &s)) {
        LOG_FATAL("HTTPS bind failed (port 443).");
        mg_mgr_free(&mgr); return EXIT_FAILURE;
    }
    /*
     * IPv6 loopback listeners (::1) - optional. Devices with IPv6
     * disabled fail to bind these; that is fine, the IPv4 listeners
     * above still serve. Both must succeed for ipv6_ok so the ready
     * log reflects the actual state.
     */
    bool ipv6_ok = true;
    if (!mg_http_listen(&mgr, HTTP_URL_IPV6, fn, &s)) {
        LOG_WARN("HTTP IPv6 bind failed (http://[::1]:80) — continuing with IPv4 only.");
        ipv6_ok = false;
    }
    if (!mg_http_listen(&mgr, HTTPS_URL_IPV6, fn, &s)) {
        LOG_WARN("HTTPS IPv6 bind failed (https://[::1]:443) — continuing with IPv4 only.");
        ipv6_ok = false;
    }

    setup_signal_handler();
    LOG_INFO("AdAway webserver ready — arm64, Mongoose " MG_VERSION
        ", SNI cert issuance enabled, IPv6 loopback %s.",
        ipv6_ok ? "on" : "off");

    while (s_sig_num == 0) mg_mgr_poll(&mgr, 1000);

    LOG_INFO("Signal %d — shutting down.", s_sig_num);
    mg_mgr_free(&mgr);

    /* Free SNI cache.
       BUG FIX: take the mutex before freeing so no concurrent
       sni_callback() thread is still walking the cache while we destroy
       it. After mg_mgr_free() all connections are closed and no new
       callbacks can be dispatched, but an abundance of caution is cheap. */
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++)
        if (s_sni_cache[i].ctx) SSL_CTX_free(s_sni_cache[i].ctx);
    pthread_mutex_unlock(&s_sni_mutex);
    pthread_mutex_destroy(&s_sni_mutex);

    /* Free CA in-memory objects */
    if (s.ca.cert) X509_free(s.ca.cert);
    if (s.ca.key)  EVP_PKEY_free(s.ca.key);

    free((void *)s.tls_opts.cert.buf);
    free((void *)s.tls_opts.key.buf);

    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Clean shutdown.");
    return EXIT_SUCCESS;
}
