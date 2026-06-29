#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <android/log.h>
#include <errno.h>
#include <sys/stat.h>
#include <pthread.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <openssl/ssl.h>
#include <openssl/err.h>
#include "mongoose/mongoose.h"

#define THIS_FILE       "WebServer"
#define HTTP_URL        "http://0.0.0.0:80"
#define HTTPS_URL       "https://0.0.0.0:443"
#define OOM_ADJ_PATH    "/proc/self/oom_score_adj"
#define OOM_ADJ_NOKILL  -1000
#define MAX_CONNECTIONS 256
#define IDLE_TIMEOUT_MS 15000
#define SNI_CACHE_SIZE  64
#define PATH_MAX_LEN    512

/* ── Signal ─────────────────────────────────────────── */
static volatile sig_atomic_t s_sig_num = 0;
static int s_active_connections = 0;

/* ── Settings ───────────────────────────────────────── */
#define BLOCK_IMAGE_COUNT 7

struct settings {
    bool     init;
    struct mg_tls_opts tls_opts;
    char     test_path[PATH_MAX_LEN];
    char     resource_dir[PATH_MAX_LEN];
    char     cert_path[PATH_MAX_LEN];
    char     key_path[PATH_MAX_LEN];
    EVP_PKEY *ca_pkey;   /* CA private key kept in memory for SNI signing */
    X509     *ca_cert;   /* CA cert kept in memory for SNI signing        */
    bool     debug;
};

/* ── SNI cert cache ─────────────────────────────────── */
typedef struct {
    char      hostname[256];
    X509     *cert;
    EVP_PKEY *pkey;
} sni_cache_entry;

static sni_cache_entry  s_sni_cache[SNI_CACHE_SIZE];
static int              s_sni_cache_next = 0;
static pthread_mutex_t  s_sni_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct settings *s_global_settings = NULL;

/* ── Forward declarations ───────────────────────────── */
static int generate_self_signed_cert(const char *cert_path, const char *key_path,
                                     EVP_PKEY **out_pkey, X509 **out_cert);
static X509 *sign_leaf_cert(const char *hostname,
                             X509 *ca_cert, EVP_PKEY *ca_pkey,
                             EVP_PKEY *leaf_pkey);

/* ── SNI callback ───────────────────────────────────── */
/*
 * Called by OpenSSL during the TLS handshake when the client sends a
 * ServerName extension.  We look up (or generate) a leaf certificate
 * signed by our CA for the requested hostname, then swap it into the
 * SSL session so the client sees a cert that matches the domain it
 * thinks it is talking to.
 *
 * This is what makes HTTPS blocking actually work: without it the
 * browser sees a `localhost` cert for `ad.example.com` and shows a
 * cert-mismatch error instead of our block page.
 */
static int sni_callback(SSL *ssl, int *al, void *arg) {
    (void)al;
    (void)arg;
    if (!s_global_settings) return SSL_TLSEXT_ERR_NOACK;

    const char *host = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
    if (!host || strcmp(host, "localhost") == 0) {
        return SSL_TLSEXT_ERR_OK;  /* use the default localhost cert */
    }

    pthread_mutex_lock(&s_sni_mutex);

    /* Check cache */
    for (int i = 0; i < SNI_CACHE_SIZE; i++) {
        if (s_sni_cache[i].cert &&
            strcmp(s_sni_cache[i].hostname, host) == 0) {
            SSL_use_certificate(ssl, s_sni_cache[i].cert);
            SSL_use_PrivateKey(ssl, s_sni_cache[i].pkey);
            pthread_mutex_unlock(&s_sni_mutex);
            __android_log_print(ANDROID_LOG_DEBUG, THIS_FILE,
                "SNI: cache hit for %s", host);
            return SSL_TLSEXT_ERR_OK;
        }
    }

    /* Cache miss: generate a leaf key + cert signed by our CA */
    EVP_PKEY_CTX *pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, NULL);
    EVP_PKEY *leaf_pkey = NULL;
    X509 *leaf_cert = NULL;

    if (!pctx ||
        EVP_PKEY_keygen_init(pctx) <= 0 ||
        EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, 2048) <= 0 ||
        EVP_PKEY_keygen(pctx, &leaf_pkey) <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE,
            "SNI: key gen failed for %s", host);
        if (pctx) EVP_PKEY_CTX_free(pctx);
        pthread_mutex_unlock(&s_sni_mutex);
        return SSL_TLSEXT_ERR_NOACK;
    }
    EVP_PKEY_CTX_free(pctx);

    leaf_cert = sign_leaf_cert(host,
                               s_global_settings->ca_cert,
                               s_global_settings->ca_pkey,
                               leaf_pkey);
    if (!leaf_cert) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE,
            "SNI: cert sign failed for %s", host);
        EVP_PKEY_free(leaf_pkey);
        pthread_mutex_unlock(&s_sni_mutex);
        return SSL_TLSEXT_ERR_NOACK;
    }

    /* Store in cache (evict oldest) */
    int slot = s_sni_cache_next % SNI_CACHE_SIZE;
    if (s_sni_cache[slot].cert) {
        X509_free(s_sni_cache[slot].cert);
        EVP_PKEY_free(s_sni_cache[slot].pkey);
    }
    strncpy(s_sni_cache[slot].hostname, host,
            sizeof(s_sni_cache[slot].hostname) - 1);
    s_sni_cache[slot].hostname[sizeof(s_sni_cache[slot].hostname)-1] = '\0';
    s_sni_cache[slot].cert  = leaf_cert;
    s_sni_cache[slot].pkey  = leaf_pkey;
    s_sni_cache_next++;

    SSL_use_certificate(ssl, leaf_cert);
    SSL_use_PrivateKey(ssl, leaf_pkey);

    pthread_mutex_unlock(&s_sni_mutex);

    __android_log_print(ANDROID_LOG_DEBUG, THIS_FILE,
        "SNI: issued leaf cert for %s", host);
    return SSL_TLSEXT_ERR_OK;
}

/* ── HTTP event handler ─────────────────────────────── */
static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        if (s_active_connections >= MAX_CONNECTIONS) {
            c->is_closing = 1;
            return;
        }
        s_active_connections++;
        uint64_t accepted_at = mg_millis();
        memcpy(c->data, &accepted_at, sizeof(accepted_at));
        c->data[sizeof(accepted_at)] = 1;

        if (c->is_tls && c->fn_data != NULL) {
            struct settings *s = (struct settings *) c->fn_data;
            mg_tls_init(c, &s->tls_opts);

            /* Install SNI callback on the per-connection SSL context */
            if (c->tls) {
                /* The mg_tls struct for OpenSSL is {BIO_METHOD*, SSL_CTX*, SSL*}.
                 * We need the SSL* to reach SSL_get_SSL_CTX(). */
                struct mg_tls_openssl {
                    void    *bm;
                    SSL_CTX *ctx;
                    SSL     *ssl;
                };
                struct mg_tls_openssl *tls =
                    (struct mg_tls_openssl *) c->tls;
                if (tls->ctx) {
                    SSL_CTX_set_tlsext_servername_callback(
                        tls->ctx, sni_callback);
                }
            }
        }
    } else if (ev == MG_EV_CLOSE) {
        if (c->data[sizeof(uint64_t)] == 1) {
            s_active_connections--;
        }
    } else if (ev == MG_EV_POLL && c->is_accepted) {
        if (!c->is_draining && !c->is_closing) {
            uint64_t accepted_at, now = mg_millis();
            memcpy(&accepted_at, c->data, sizeof(accepted_at));
            if (now - accepted_at > IDLE_TIMEOUT_MS) {
                c->is_closing = 1;
            }
        }
    } else if (ev == MG_EV_HTTP_MSG && c->fn_data != NULL) {
        struct mg_http_message *hm = (struct mg_http_message *) ev_data;
        struct settings *s = (struct settings *) c->fn_data;

        struct mg_http_serve_opts opts;
        memset(&opts, 0, sizeof(opts));
        opts.extra_headers = "Connection: close\r\n";

        if (mg_strcmp(hm->uri, mg_str("/internal-test")) == 0) {
            mg_http_serve_file(c, hm, s->test_path, &opts);
        } else {
            char img_path[PATH_MAX_LEN];
            int idx = (int)(mg_millis() % BLOCK_IMAGE_COUNT);
            snprintf(img_path, sizeof(img_path),
                     "%s/img_%02d.webp", s->resource_dir, idx);
            mg_http_serve_file(c, hm, img_path, &opts);
        }
        c->is_draining = 1;
    }
}

/* ── Signals ────────────────────────────────────────── */
static void signal_handler(int sig_num) { s_sig_num = sig_num; }

static void setup_signal_handler(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = SIG_IGN;
    sigaction(SIGPIPE, &sa, NULL);
    sa.sa_handler = signal_handler;
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGHUP,  &sa, NULL);
}

/* ── OOM killer ─────────────────────────────────────── */
static void oom_adjust_setup(void) {
    FILE *fp = fopen(OOM_ADJ_PATH, "r+");
    if (!fp) return;
    char buf[32];
    if (!fgets(buf, sizeof(buf), fp)) { fclose(fp); return; }
    char *ep; errno = 0;
    int score = (int)strtol(buf, &ep, 10);
    if (ep != buf && errno == 0 && OOM_ADJ_NOKILL < score) {
        rewind(fp);
        fprintf(fp, "%d\n", OOM_ADJ_NOKILL);
        __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
            "OOM score %d → %d", score, OOM_ADJ_NOKILL);
    }
    fclose(fp);
}

/* ── Cert helpers ───────────────────────────────────── */
/*
 * Generates a CA key+cert (self-signed) that:
 *  (a) can be installed as a trusted CA in Android's user trust store
 *  (b) is used to sign per-hostname leaf certs via SNI callback
 *  (c) is also used directly as the localhost TLS server cert
 */
static int generate_self_signed_cert(const char *cert_path,
                                     const char *key_path,
                                     EVP_PKEY **out_pkey,
                                     X509     **out_cert) {
    int ret = EXIT_FAILURE;
    EVP_PKEY_CTX *pctx = NULL;
    EVP_PKEY     *pkey = NULL;
    X509         *x509 = NULL;
    X509_NAME    *name = NULL;
    FILE         *kf   = NULL, *cf = NULL;
    uint64_t      serial = 0;

    pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, NULL);
    if (!pctx ||
        EVP_PKEY_keygen_init(pctx) <= 0 ||
        EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, 2048) <= 0 ||
        EVP_PKEY_keygen(pctx, &pkey) <= 0) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
            "CA key gen failed");
        goto cleanup;
    }

    x509 = X509_new();
    if (!x509) goto cleanup;
    X509_set_version(x509, 2);

    if (RAND_bytes((unsigned char *)&serial, sizeof(serial)) != 1)
        serial = (uint64_t)mg_millis();
    serial &= 0x7FFFFFFFFFFFFFFFULL;
    ASN1_INTEGER_set_uint64(X509_get_serialNumber(x509), serial);

    X509_gmtime_adj(X509_get_notBefore(x509), 0);
    X509_gmtime_adj(X509_get_notAfter(x509),
                    (long)60 * 60 * 24 * 3650); /* 10 years */
    X509_set_pubkey(x509, pkey);

    name = X509_get_subject_name(x509);
    X509_NAME_add_entry_by_txt(name, "O",  MBSTRING_ASC,
        (const unsigned char *)"AdAway", -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
        (const unsigned char *)"AdAway Root CA", -1, -1, 0);
    X509_set_issuer_name(x509, name);

    {
        X509V3_CTX ctx;
        X509V3_set_ctx_nodb(&ctx);
        X509V3_set_ctx(&ctx, x509, x509, NULL, NULL, 0);
        static const struct { int nid; const char *v; } exts[] = {
            { NID_basic_constraints,      "critical,CA:TRUE"                          },
            { NID_subject_key_identifier, "hash"                                      },
            { NID_key_usage, "critical,digitalSignature,keyCertSign,cRLSign"          },
            { NID_ext_key_usage, "serverAuth"                                         },
            { NID_subject_alt_name, "DNS:localhost,IP:127.0.0.1"                      },
        };
        for (int i = 0; i < (int)(sizeof(exts)/sizeof(exts[0])); i++) {
            X509_EXTENSION *e = X509V3_EXT_conf_nid(NULL, &ctx,
                                                     exts[i].nid, exts[i].v);
            if (!e) goto cleanup;
            int ok = X509_add_ext(x509, e, -1);
            X509_EXTENSION_free(e);
            if (!ok) goto cleanup;
        }
    }

    if (X509_sign(x509, pkey, EVP_sha256()) == 0) goto cleanup;

    kf = fopen(key_path, "wb");
    if (!kf || PEM_write_PrivateKey(kf, pkey, NULL, NULL, 0, NULL, NULL) == 0)
        goto cleanup;
    fclose(kf); kf = NULL;
    chmod(key_path, S_IRUSR | S_IWUSR);

    cf = fopen(cert_path, "wb");
    if (!cf || PEM_write_X509(cf, x509) == 0)
        goto cleanup;
    fclose(cf); cf = NULL;

    if (out_pkey) { *out_pkey = pkey; pkey = NULL; }
    if (out_cert) { *out_cert = x509; x509 = NULL; }
    ret = EXIT_SUCCESS;

cleanup:
    if (kf)   fclose(kf);
    if (cf)   fclose(cf);
    if (x509) X509_free(x509);
    if (pkey) EVP_PKEY_free(pkey);
    if (pctx) EVP_PKEY_CTX_free(pctx);
    return ret;
}

/*
 * Signs a leaf certificate for `hostname` using the CA cert + key.
 * The leaf cert has:
 *  - SAN: DNS:<hostname>  (required by modern TLS stacks)
 *  - EKU: serverAuth
 *  - KeyUsage: digitalSignature
 *  - Validity: 1 year
 * Returns a new X509* (caller owns it), or NULL on failure.
 */
static X509 *sign_leaf_cert(const char *hostname,
                             X509      *ca_cert,
                             EVP_PKEY  *ca_pkey,
                             EVP_PKEY  *leaf_pkey) {
    X509      *x509 = NULL;
    X509_NAME *name = NULL;
    uint64_t   serial = 0;

    x509 = X509_new();
    if (!x509) return NULL;
    X509_set_version(x509, 2);

    if (RAND_bytes((unsigned char *)&serial, sizeof(serial)) != 1)
        serial = (uint64_t)mg_millis();
    serial &= 0x7FFFFFFFFFFFFFFFULL;
    ASN1_INTEGER_set_uint64(X509_get_serialNumber(x509), serial);

    X509_gmtime_adj(X509_get_notBefore(x509), 0);
    X509_gmtime_adj(X509_get_notAfter(x509),
                    (long)60 * 60 * 24 * 365); /* 1 year */
    X509_set_pubkey(x509, leaf_pkey);

    /* Leaf subject: CN = hostname */
    name = X509_get_subject_name(x509);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
        (const unsigned char *)hostname, -1, -1, 0);
    /* Issuer = CA's subject */
    X509_set_issuer_name(x509, X509_get_subject_name(ca_cert));

    /* Extensions */
    {
        X509V3_CTX ctx;
        X509V3_set_ctx_nodb(&ctx);
        X509V3_set_ctx(&ctx, ca_cert, x509, NULL, NULL, 0);

        /* SAN: DNS:<hostname> */
        char san_buf[280];
        snprintf(san_buf, sizeof(san_buf), "DNS:%s", hostname);
        static const struct { int nid; const char *v; } fixed_exts[] = {
            { NID_key_usage,     "critical,digitalSignature" },
            { NID_ext_key_usage, "serverAuth"                },
        };
        for (int i = 0; i < 2; i++) {
            X509_EXTENSION *e = X509V3_EXT_conf_nid(NULL, &ctx,
                fixed_exts[i].nid, fixed_exts[i].v);
            if (!e) { X509_free(x509); return NULL; }
            int ok = X509_add_ext(x509, e, -1);
            X509_EXTENSION_free(e);
            if (!ok) { X509_free(x509); return NULL; }
        }
        X509_EXTENSION *san = X509V3_EXT_conf_nid(NULL, &ctx,
                                NID_subject_alt_name, san_buf);
        if (!san) { X509_free(x509); return NULL; }
        if (!X509_add_ext(x509, san, -1)) {
            X509_EXTENSION_free(san);
            X509_free(x509);
            return NULL;
        }
        X509_EXTENSION_free(san);
    }

    if (X509_sign(x509, ca_pkey, EVP_sha256()) == 0) {
        X509_free(x509);
        return NULL;
    }
    return x509;
}

/* ── Load CA cert+key from PEM files into memory ─────── */
static bool load_ca_into_memory(struct settings *s) {
    /* Load private key */
    FILE *kf = fopen(s->key_path, "rb");
    if (!kf) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE,
            "Cannot open key file: %s", s->key_path);
        return false;
    }
    s->ca_pkey = PEM_read_PrivateKey(kf, NULL, NULL, NULL);
    fclose(kf);
    if (!s->ca_pkey) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE, "PEM_read_PrivateKey failed");
        return false;
    }

    /* Load certificate */
    FILE *cf = fopen(s->cert_path, "rb");
    if (!cf) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE,
            "Cannot open cert file: %s", s->cert_path);
        EVP_PKEY_free(s->ca_pkey); s->ca_pkey = NULL;
        return false;
    }
    s->ca_cert = PEM_read_X509(cf, NULL, NULL, NULL);
    fclose(cf);
    if (!s->ca_cert) {
        __android_log_print(ANDROID_LOG_ERROR, THIS_FILE, "PEM_read_X509 failed");
        EVP_PKEY_free(s->ca_pkey); s->ca_pkey = NULL;
        return false;
    }
    return true;
}

/* ── CLI parameter parsing ──────────────────────────── */
static struct settings parse_cli_parameters(int argc, char *argv[]) {
    struct settings s;
    memset(&s, 0, sizeof(s));
    s.init = false;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--resources") == 0 && i < argc - 1) {
            const char *rp = argv[++i];

            snprintf(s.cert_path, sizeof(s.cert_path),
                     "%s/localhost-2410.crt", rp);
            snprintf(s.key_path,  sizeof(s.key_path),
                     "%s/localhost-2410.key", rp);
            snprintf(s.resource_dir, sizeof(s.resource_dir), "%s", rp);
            snprintf(s.test_path,    sizeof(s.test_path),
                     "%s/test.html", rp);

            /* Generate CA cert if missing */
            bool cert_ok = (access(s.cert_path, R_OK) == 0) &&
                           (access(s.key_path,  R_OK) == 0);
            if (!cert_ok) {
                __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
                    "Generating new CA certificate…");
                if (generate_self_signed_cert(s.cert_path, s.key_path,
                                              NULL, NULL) != EXIT_SUCCESS) {
                    __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
                        "CA cert generation failed.");
                    return s;
                }
            }

            /* Load CA into memory for SNI signing */
            if (!load_ca_into_memory(&s)) {
                __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
                    "Failed to load CA into memory.");
                return s;
            }

            /* TLS opts for Mongoose (localhost cert = CA cert) */
            s.tls_opts.cert = mg_file_read(&mg_fs_posix, s.cert_path);
            s.tls_opts.key  = mg_file_read(&mg_fs_posix, s.key_path);
            s.init = true;
        } else if (strcmp(argv[i], "--debug") == 0) {
            s.debug = true;
        }
    }
    return s;
}

/* ── main ───────────────────────────────────────────── */
int main(int argc, char *argv[]) {
    /* Standalone cert generation mode */
    if (argc == 3 && strcmp(argv[1], "--gen-cert") == 0) {
        char cp[PATH_MAX_LEN], kp[PATH_MAX_LEN];
        snprintf(cp, sizeof(cp), "%s/localhost-2410.crt", argv[2]);
        snprintf(kp, sizeof(kp), "%s/localhost-2410.key", argv[2]);
        return generate_self_signed_cert(cp, kp, NULL, NULL);
    }

    /*
     * Detach from the shell's process group so SIGHUP from LibSu
     * shell exit does not kill the server.
     */
    setsid();

    struct settings s = parse_cli_parameters(argc, argv);
    if (!s.init) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
            "Missing --resources parameter or cert generation failed.");
        return EXIT_FAILURE;
    }

    if (s.debug) mg_log_set(MG_LL_DEBUG);

    oom_adjust_setup();
    setup_signal_handler();

    /* Make CA available to SNI callback */
    s_global_settings = &s;

    struct mg_mgr mgr;
    mg_mgr_init(&mgr);

    struct mg_connection *http_c  = mg_http_listen(&mgr, HTTP_URL,  fn, &s);
    struct mg_connection *https_c = mg_http_listen(&mgr, HTTPS_URL, fn, &s);

    if (!http_c) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
            "Failed to bind port 80 (HTTP). Is another instance running?");
        mg_mgr_free(&mgr);
        return EXIT_FAILURE;
    }
    if (!https_c) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
            "Failed to bind port 443 (HTTPS). Is another instance running?");
        mg_mgr_free(&mgr);
        return EXIT_FAILURE;
    }

    __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
        "AdAway webserver started (Mongoose %s, arm64, SNI enabled).",
        MG_VERSION);

    while (s_sig_num == 0) {
        mg_mgr_poll(&mgr, 1000);
    }

    __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
        "Signal %d — shutting down.", s_sig_num);
    mg_mgr_free(&mgr);

    /* Free SNI cache */
    pthread_mutex_lock(&s_sni_mutex);
    for (int i = 0; i < SNI_CACHE_SIZE; i++) {
        if (s_sni_cache[i].cert)  X509_free(s_sni_cache[i].cert);
        if (s_sni_cache[i].pkey)  EVP_PKEY_free(s_sni_cache[i].pkey);
    }
    pthread_mutex_unlock(&s_sni_mutex);

    if (s.ca_cert)  X509_free(s.ca_cert);
    if (s.ca_pkey)  EVP_PKEY_free(s.ca_pkey);
    free((void *)s.tls_opts.cert.buf);
    free((void *)s.tls_opts.key.buf);

    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Clean exit.");
    return EXIT_SUCCESS;
}
