#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <android/log.h>
#include <errno.h>
#include <sys/stat.h>
#include <openssl/evp.h>
#include <openssl/x509.h>
#include <openssl/x509v3.h>
#include <openssl/pem.h>
#include <openssl/rand.h>
#include "mongoose/mongoose.h"

#define THIS_FILE "WebServer"

// TODO Test difference between 127.0.0.1 + [::1] and localhost
#define HTTP_URL "http://localhost:80"
#define HTTPS_URL "https://localhost:443"

#define OOM_ADJ_PATH    "/proc/self/oom_score_adj"
#define OOM_ADJ_NOKILL  -17

// The web server only ever needs to sink one-off requests for blocked ad and
// tracker domains: it has no legitimate reason to keep a connection open
// after answering it, nor to let an accepted connection sit idle forever.
// Without bounds on this, connections accumulate without limit over the
// uptime of the process (e.g. a stalled TLS handshake, or a client that
// opens a socket and never sends a request), and servicing a growing
// connection list on every poll cycle is what causes the abnormal CPU usage
// reported in https://github.com/AdAway/AdAway/issues/4174.
#define MAX_CONNECTIONS 256
#define IDLE_TIMEOUT_MS 10000

static volatile sig_atomic_t s_sig_num = 0;
static int s_active_connections = 0;

struct settings {
    bool init;
    struct mg_tls_opts tls_opts;
    char test_path[100];
    char icon_path[100];
    bool icon;
    bool debug;
};

static void fn(struct mg_connection *c, int ev, void *ev_data) {
    if (ev == MG_EV_ACCEPT) {
        // Shed load before doing any expensive work (in particular, before
        // the TLS handshake, which is the costliest part): once the cap is
        // reached, just drop the connection.
        if (s_active_connections >= MAX_CONNECTIONS) {
            c->is_closing = 1;
            return;
        }
        s_active_connections++;
        // Remember when this connection was accepted, so MG_EV_POLL below
        // can enforce an idle timeout on it. The byte right after the
        // timestamp marks this connection as counted, so MG_EV_CLOSE below
        // only decrements s_active_connections for connections that were
        // actually counted here (and not ones rejected above for being over
        // the cap).
        uint64_t accepted_at = mg_millis();
        memcpy(c->data, &accepted_at, sizeof(accepted_at));
        c->data[sizeof(accepted_at)] = 1;
        if (c->is_tls && c->fn_data != NULL) {
            struct settings *s = (struct settings *) c->fn_data;
            mg_tls_init(c, &s->tls_opts);
        }
    } else if (ev == MG_EV_CLOSE) {
        if (c->data[sizeof(uint64_t)] == 1) {
            s_active_connections--;
        }
    } else if (ev == MG_EV_POLL && c->is_accepted) {
        // Safety net for connections that never reach MG_EV_HTTP_MSG, e.g. a
        // TLS handshake that never completes, or a client that opens a
        // socket and never sends a request: close them once they have been
        // open for too long instead of letting them linger forever. Guarded
        // by is_accepted so this never touches the two listening sockets.
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
        if (mg_strcmp(hm->uri, mg_str("/internal-test")) == 0) {
            struct mg_http_serve_opts opts;
            memset(&opts, 0, sizeof(opts));
            opts.extra_headers = "Connection: close\r\n";
            mg_http_serve_file(c, hm, s->test_path, &opts);
        } else if (s->icon) {
            struct mg_http_serve_opts opts;
            memset(&opts, 0, sizeof(opts));
            opts.extra_headers = "Connection: close\r\n";
            mg_http_serve_file(c, hm, s->icon_path, &opts);
        } else {
            mg_http_reply(c, 200, "Connection: close\r\n", "");
        }
        // Never keep a connection alive after answering it: this used to
        // default to HTTP keep-alive, which is how connections piled up
        // without bound in the first place.
        c->is_draining = 1;
    }
}

static void signal_handler(int sig_num) {
    s_sig_num = sig_num;
}

void setup_signal_handler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sigaction(SIGINT, &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
}

/*
 * Tells the kernel's out-of-memory killer to avoid this process.
 */
void oom_adjust_setup(void) {
    FILE *fp;
    char buf[32];
    if ((fp = fopen(OOM_ADJ_PATH, "r+")) == NULL) {
        __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "error opening %s: %s", OOM_ADJ_PATH,
                            strerror(errno));
        return;
    }
    if (fgets(buf, sizeof(buf), fp) == NULL) {
        __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "error reading %s: %s", OOM_ADJ_PATH,
                            strerror(errno));
        return;
    }
    char *end_ptr;
    errno = 0;
    int oom_score = (int) strtol(buf, &end_ptr, 10);
    if (end_ptr == buf || errno != 0) {
        __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "error reading %s: %s", OOM_ADJ_PATH,
                            strerror(errno));
    } else if (OOM_ADJ_NOKILL < oom_score) {
        rewind(fp);
        if (fprintf(fp, "%d\n", OOM_ADJ_NOKILL) <= 0) {
            __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "error writing %s: %s",
                                OOM_ADJ_PATH, strerror(errno));
        } else {
            __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Set %s from %d to %d",
                                OOM_ADJ_PATH, oom_score, OOM_ADJ_NOKILL);
        }
    }
    fclose(fp);
}

/*
 * Generates a fresh, unique RSA key pair and a self-signed "localhost"
 * certificate, and writes them out as PEM files at cert_path/key_path.
 *
 * This replaces what used to be a single static certificate (and its
 * private key) bundled as a plain asset inside every copy of the APK and
 * checked into the public source repository: that meant every single
 * install of AdAway shared the exact same TLS private key, which anyone
 * could extract from the repo or the APK and use to impersonate any site
 * to any device that had the certificate installed in its trust store.
 * Generating a unique key pair locally, at first use, means the private
 * key never leaves the device and is never shared across installs.
 */
static int generate_self_signed_cert(const char *cert_path, const char *key_path) {
    int ret = EXIT_FAILURE;
    EVP_PKEY_CTX *pctx = NULL;
    EVP_PKEY *pkey = NULL;
    X509 *x509 = NULL;
    X509_NAME *name = NULL;
    FILE *key_file = NULL;
    FILE *cert_file = NULL;
    uint64_t serial = 0;

    pctx = EVP_PKEY_CTX_new_id(EVP_PKEY_RSA, NULL);
    if (pctx == NULL || EVP_PKEY_keygen_init(pctx) <= 0 ||
        EVP_PKEY_CTX_set_rsa_keygen_bits(pctx, 2048) <= 0 ||
        EVP_PKEY_keygen(pctx, &pkey) <= 0) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to generate RSA key pair.");
        goto cleanup;
    }

    x509 = X509_new();
    if (x509 == NULL) {
        goto cleanup;
    }
    X509_set_version(x509, 2);  // 0=v1, 1=v2, 2=v3; extensions require v3

    if (RAND_bytes((unsigned char *) &serial, sizeof(serial)) != 1) {
        // Not security-critical (the serial only needs to be unlikely to
        // collide, not unpredictable), so fall back rather than fail.
        serial = (uint64_t) mg_millis();
    }
    serial &= 0x7FFFFFFFFFFFFFFFULL;  // keep it a positive ASN1 integer
    ASN1_INTEGER_set_uint64(X509_get_serialNumber(x509), serial);

    X509_gmtime_adj(X509_get_notBefore(x509), 0);
    X509_gmtime_adj(X509_get_notAfter(x509), (long) 60 * 60 * 24 * 3650);  // 10 years
    X509_set_pubkey(x509, pkey);

    name = X509_get_subject_name(x509);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                                (const unsigned char *) "localhost", -1, -1, 0);
    X509_set_issuer_name(x509, name);  // self-signed: issuer == subject

    // X.509v3 extensions required for Android to recognise this as an
    // installable CA certificate (without them Android treats it as a
    // client/user certificate and demands the private key):
    //
    //   BasicConstraints  critical, CA:TRUE
    //     - Marks the certificate as a Certificate Authority.
    //     - Without this, KeyChain's install flow shows "private key
    //       required" because the system categorises it as a user cert.
    //
    //   SubjectKeyIdentifier  hash
    //     - Standard extension for CA certificates; used by chains to
    //       identify the issuing key.
    //
    //   KeyUsage  critical, keyCertSign, cRLSign
    //     - Explicitly restricts the key's allowed uses to signing
    //       certificates and CRLs, which is what a root CA key does.
    {
        X509V3_CTX ext_ctx;
        X509V3_set_ctx_nodb(&ext_ctx);
        // Self-signed: both issuer and subject are this certificate.
        X509V3_set_ctx(&ext_ctx, x509, x509, NULL, NULL, 0);

        static const struct { int nid; const char *value; } exts[] = {
            { NID_basic_constraints,      "critical,CA:TRUE"              },
            { NID_subject_key_identifier, "hash"                          },
            { NID_key_usage,              "critical,keyCertSign,cRLSign"  },
        };
        for (int i = 0; i < (int)(sizeof(exts) / sizeof(exts[0])); i++) {
            X509_EXTENSION *ext = X509V3_EXT_conf_nid(
                    NULL, &ext_ctx, exts[i].nid, exts[i].value);
            if (ext == NULL) {
                __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
                        "Failed to build certificate extension %d.", exts[i].nid);
                goto cleanup;
            }
            int ok = X509_add_ext(x509, ext, -1);
            X509_EXTENSION_free(ext);
            if (!ok) {
                __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
                        "Failed to add certificate extension %d.", exts[i].nid);
                goto cleanup;
            }
        }
    }

    if (X509_sign(x509, pkey, EVP_sha256()) == 0) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to sign certificate.");
        goto cleanup;
    }

    key_file = fopen(key_path, "wb");
    if (key_file == NULL ||
        PEM_write_PrivateKey(key_file, pkey, NULL, NULL, 0, NULL, NULL) == 0) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to write private key.");
        goto cleanup;
    }
    fclose(key_file);
    key_file = NULL;
    chmod(key_path, S_IRUSR | S_IWUSR);  // private key: owner read/write only

    cert_file = fopen(cert_path, "wb");
    if (cert_file == NULL || PEM_write_X509(cert_file, x509) == 0) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to write certificate.");
        goto cleanup;
    }

    ret = EXIT_SUCCESS;

cleanup:
    if (key_file != NULL) fclose(key_file);
    if (cert_file != NULL) fclose(cert_file);
    if (x509 != NULL) X509_free(x509);
    if (pkey != NULL) EVP_PKEY_free(pkey);
    if (pctx != NULL) EVP_PKEY_CTX_free(pctx);
    return ret;
}

struct settings parse_cli_parameters(int argc, char *argv[]) {
    struct settings s = {
            .init = false,
            .tls_opts = { 0 },
            .icon = false,
            .debug = false
    };
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--resources") == 0 && i < argc - 1) {
            char *resource_path = argv[++i];
            char cert_path[100];
            char key_path[100];
            snprintf(cert_path, sizeof(cert_path),
                     "%s/localhost-2410.crt", resource_path);
            snprintf(key_path, sizeof(key_path),
                     "%s/localhost-2410.key", resource_path);

            // Auto-generate the TLS certificate/key on first start, or if
            // either file has gone missing. This runs inside the server
            // process (which is already running as root via the root shell),
            // so no blocking shell invocation is needed from the Java UI
            // thread - the app just starts the server and the server takes
            // care of provisioning its own certificate.
            FILE *cert_probe = fopen(cert_path, "rb");
            FILE *key_probe  = fopen(key_path,  "rb");
            bool cert_missing = (cert_probe == NULL || key_probe == NULL);
            if (cert_probe != NULL) fclose(cert_probe);
            if (key_probe  != NULL) fclose(key_probe);

            if (cert_missing) {
                __android_log_print(ANDROID_LOG_INFO, THIS_FILE,
                        "Certificate not found, generating...");
                if (generate_self_signed_cert(cert_path, key_path) != EXIT_SUCCESS) {
                    __android_log_print(ANDROID_LOG_FATAL, THIS_FILE,
                            "Failed to generate certificate, aborting.");
                    return s;   // s.init stays false → main() exits cleanly
                }
            }

            // Initialize TLS options from the (now guaranteed-to-exist) files
            s.tls_opts.cert = mg_file_read(&mg_fs_posix, cert_path);
            s.tls_opts.key  = mg_file_read(&mg_fs_posix, key_path);
            // Initialize resource paths
            strcpy(s.icon_path, resource_path);
            strcat(s.icon_path, "/icon.svg");
            strcpy(s.test_path, resource_path);
            strcat(s.test_path, "/test.html");
            s.init = true;
        } else if (strcmp(argv[i], "--icon") == 0) {
            s.icon = true;
        } else if (strcmp(argv[i], "--debug") == 0) {
            s.debug = true;
        }
    }
    return s;
}

int main(int argc, char *argv[]) {
    // --gen-cert <dir> is a standalone mode: generate a fresh self-signed
    // "localhost" certificate and private key into <dir>/localhost-2410.{crt,key}
    // and exit, without starting the web server. This is invoked once, on
    // first use, from the Android app, before the server itself is ever
    // started with --resources <dir>.
    if (argc == 3 && strcmp(argv[1], "--gen-cert") == 0) {
        char cert_path[100];
        char key_path[100];
        snprintf(cert_path, sizeof(cert_path), "%s/localhost-2410.crt", argv[2]);
        snprintf(key_path, sizeof(key_path), "%s/localhost-2410.key", argv[2]);
        return generate_self_signed_cert(cert_path, key_path);
    }

    struct mg_mgr mgr;
    struct mg_connection *http_connection;
    struct mg_connection *https_connection;

    struct settings s = parse_cli_parameters(argc, argv);
    if (!s.init) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Missing parameters.");
        return EXIT_FAILURE;
    }

    if (s.debug) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Debug mode activated.");
        mg_log_set(MG_LL_DEBUG);
    }

    oom_adjust_setup();

    mg_mgr_init(&mgr);
    http_connection = mg_http_listen(&mgr, HTTP_URL, fn, &s);
    if (http_connection == NULL) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to listen on http port.");
        return EXIT_FAILURE;
    }
    https_connection = mg_http_listen(&mgr, HTTPS_URL, fn, &s);
    if (https_connection == NULL) {
        __android_log_print(ANDROID_LOG_FATAL, THIS_FILE, "Failed to listen on https port.");
        return EXIT_FAILURE;
    }

    setup_signal_handler();
    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Starting server.");
    while (s_sig_num == 0) {
        mg_mgr_poll(&mgr, 1000);
    }

    mg_mgr_free(&mgr);
    __android_log_print(ANDROID_LOG_INFO, THIS_FILE, "Stopping server on signal %d.", s_sig_num);
    return EXIT_SUCCESS;
}
