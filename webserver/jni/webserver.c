#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>
#include <sys/stat.h>
#include <linux/limits.h>
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
#define BLOCK_IMAGE_COUNT 7

/* SNI per-domain certificate cache.
   OPTIMIZATION: 48 was tight for real browsing sessions — a single page
   with a dozen distinct ad/tracker domains, multiplied across a few
   tabs/apps, evicts entries before they get reused, forcing needless
   re-generation (even with the EC speedup above, still not free).
   256 entries at ~264 bytes each (hostname[256] + pointer) is ~68KB —
   negligible for a process that otherwise stays resident. */
#define SNI_CACHE_SIZE 256
struct sni_entry { char hostname[256]; SSL_CTX *ctx; };
static struct sni_entry s_sni_cache[SNI_CACHE_SIZE];
static int              s_sni_pos = 0;

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
    FILE *f;
    f = fopen(key_path, "wb");
    if (!f || PEM_write_PrivateKey(f, key, NULL, NULL, 0, NULL, NULL) == 0)
        goto done;
    fclose(f); f = NULL;
    chmod(key_path, S_IRUSR|S_IWUSR);

    f = fopen(cert_path, "wb");
    if (!f || PEM_write_X509(f, cert) == 0) goto done;
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
   connections to this device (https://localhost/..., https://127.0.0.1/...)
   — i.e. anything that doesn't send SNI for a blocked ad domain and so
   never reaches sni_callback()/make_domain_ctx().
   BUG FIX: this used to be the raw root CA cert itself (CN "AdAway Root
   CA", no SAN matching "localhost" or "127.0.0.1" at all). Any client
   that checks the presented cert's SAN against the hostname/IP it
   dialed — which is effectively all of them — would fail to validate
   it even though the CA is trusted, since the CA's own identity isn't
   a valid SAN for the server it's terminating TLS for. Sign a proper
   short-lived leaf cert for "localhost" with both a DNS and an IP SAN
   and use *that* as the default, matching how every other hostname
   already gets a purpose-issued leaf cert via make_domain_ctx(). */
static int make_localhost_leaf(struct ca_state *ca, struct mg_tls_opts *out_opts) {
    X509 *cert = NULL; EVP_PKEY *key = NULL;
    if (make_cert("localhost", ca->cert, ca->key, 0, 397,
                  "DNS:localhost,IP:127.0.0.1", /*use_ec=*/0, &cert, &key) != EXIT_SUCCESS)
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
    if (make_cert(hostname, ca->cert, ca->key, 0, 1, NULL, /*use_ec=*/1, &cert, &key) != EXIT_SUCCESS)
        return NULL;
    SSL_CTX *ctx = SSL_CTX_new(TLS_server_method());
    if (!ctx) goto fail;
    if (SSL_CTX_use_certificate(ctx, cert) != 1 ||
        SSL_CTX_use_PrivateKey(ctx, key)   != 1 ||
        SSL_CTX_add_extra_chain_cert(ctx, X509_dup(ca->cert)) != 1) {
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

    /* Search cache */
    for (int i = 0; i < SNI_CACHE_SIZE; i++) {
        if (s_sni_cache[i].ctx && strcmp(s_sni_cache[i].hostname, host) == 0) {
            SSL_set_SSL_CTX(ssl, s_sni_cache[i].ctx);
            return SSL_TLSEXT_ERR_OK;
        }
    }

    struct ca_state *ca = (struct ca_state *)arg;
    SSL_CTX *ctx = make_domain_ctx(host, ca);
    if (!ctx) {
        LOG_WARN("SNI: failed to create ctx for %s", host);
        return SSL_TLSEXT_ERR_NOACK;
    }

    int pos = s_sni_pos % SNI_CACHE_SIZE;
    if (s_sni_cache[pos].ctx) SSL_CTX_free(s_sni_cache[pos].ctx);
    strncpy(s_sni_cache[pos].hostname, host, 255);
    s_sni_cache[pos].ctx = ctx;
    s_sni_pos++;

    SSL_set_SSL_CTX(ssl, ctx);
    __android_log_print(ANDROID_LOG_DEBUG, THIS_FILE,
        "SNI: issued cert for %s (cache[%d])", host, pos);
    return SSL_TLSEXT_ERR_OK;
}

/* ── Connection counting ──────────────────────────────────────── */
static int s_active_connections = 0;

/* ── HTTP event handler ───────────────────────────────────────── */
static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        if (s_active_connections >= MAX_CONNECTIONS) {
            c->is_closing = 1; return;
        }
        s_active_connections++;
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
        if (c->data[sizeof(uint64_t)]) s_active_connections--;
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

    /* Random block image */
    uint64_t t; memcpy(&t, c->data, sizeof(t));
    int idx = (int)(t % BLOCK_IMAGE_COUNT);
    char img_path[PATH_MAX];
    snprintf(img_path, sizeof(img_path), "%s/img_%02d.webp", s->resource_dir, idx);
    struct mg_http_serve_opts o = {0};
    o.mime_types = "webp=image/webp";
    /* OPTIMIZATION: every blocked ad slot on every page load re-requests
       this same placeholder image from the local server with no caching
       hint at all, so the client re-fetches it every single time instead
       of ever reusing a cached copy — needless disk I/O and CPU work on
       a mobile device that may be handling this dozens of times a
       minute. These images are static build resources that never change
       at runtime, so let clients cache them. */
    o.extra_headers = "Cache-Control: public, max-age=86400\r\n";
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

    setup_signal_handler();
    LOG_INFO("AdAway webserver ready — arm64, Mongoose " MG_VERSION
        ", SNI cert issuance enabled.");

    while (s_sig_num == 0) mg_mgr_poll(&mgr, 1000);

    LOG_INFO("Signal %d — shutting down.", s_sig_num);
    mg_mgr_free(&mgr);

    /* Free SNI cache */
    for (int i = 0; i < SNI_CACHE_SIZE; i++)
        if (s_sni_cache[i].ctx) SSL_CTX_free(s_sni_cache[i].ctx);

    /* Free CA in-memory objects */
    if (s.ca.cert) X509_free(s.ca.cert);
    if (s.ca.key)  EVP_PKEY_free(s.ca.key);

    free((void *)s.tls_opts.cert.buf);
    free((void *)s.tls_opts.key.buf);

    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Clean shutdown.");
    return EXIT_SUCCESS;
}
