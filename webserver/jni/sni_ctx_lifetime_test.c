#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#include <stdio.h>
#include <string.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)
static unsigned destroyed;
static void on_ctx_free(void *parent, void *ptr, CRYPTO_EX_DATA *ad,
                        int idx, long argl, void *argp) {
    (void)parent; (void)ad; (void)idx; (void)argl; (void)argp;
    if (ptr == &destroyed) ++destroyed;
}
static int select_sni(SSL *ssl, int *alert, void *arg) {
    const char *name = SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name);
    (void)alert;
    return name && strcmp(name, "localhost") == 0 && SSL_set_SSL_CTX(ssl, arg)
        ? SSL_TLSEXT_ERR_OK : SSL_TLSEXT_ERR_ALERT_FATAL;
}
static int handshake_step(SSL *ssl) {
    if (SSL_is_init_finished(ssl)) return 1;
    int n = SSL_do_handshake(ssl);
    if (n == 1) return 1;
    int err = SSL_get_error(ssl, n);
    return err == SSL_ERROR_WANT_READ || err == SSL_ERROR_WANT_WRITE;
}
static int run_version(int version) {
    destroyed = 0;
    int idx = SSL_CTX_get_ex_new_index(0, NULL, NULL, NULL, on_ctx_free);
    CHECK(idx >= 0);
    SSL_CTX *base = SSL_CTX_new(TLS_server_method());
    SSL_CTX *cached = SSL_CTX_new(TLS_server_method());
    SSL_CTX *client = SSL_CTX_new(TLS_client_method());
    CHECK(base && cached && client);
    CHECK(SSL_CTX_set_min_proto_version(base, version) && SSL_CTX_set_max_proto_version(base, version));
    CHECK(SSL_CTX_set_min_proto_version(client, version) && SSL_CTX_set_max_proto_version(client, version));
    EVP_PKEY *key = EVP_RSA_gen(2048);
    X509 *cert = X509_new();
    CHECK(key && cert && X509_set_version(cert, 2));
    CHECK(ASN1_INTEGER_set(X509_get_serialNumber(cert), 1));
    CHECK(X509_gmtime_adj(X509_getm_notBefore(cert), -60) && X509_gmtime_adj(X509_getm_notAfter(cert), 3600));
    X509_NAME *name = X509_get_subject_name(cert);
    CHECK(X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC, (const unsigned char *)"localhost", -1, -1, 0));
    CHECK(X509_set_issuer_name(cert, name) && X509_set_pubkey(cert, key));
    X509_EXTENSION *san = X509V3_EXT_conf_nid(NULL, NULL, NID_subject_alt_name, "DNS:localhost");
    CHECK(san && X509_add_ext(cert, san, -1));
    X509_EXTENSION_free(san);
    CHECK(X509_sign(cert, key, EVP_sha256()) > 0);
    CHECK(SSL_CTX_use_certificate(cached, cert) && SSL_CTX_use_PrivateKey(cached, key));
    CHECK(SSL_CTX_set_ex_data(cached, idx, &destroyed));
    CHECK(X509_STORE_add_cert(SSL_CTX_get_cert_store(client), cert));
    SSL_CTX_set_verify(client, SSL_VERIFY_PEER, NULL);
    SSL_CTX_set_tlsext_servername_callback(base, select_sni);
    SSL_CTX_set_tlsext_servername_arg(base, cached);
    SSL *servers[2], *clients[2];
    for (int i = 0; i < 2; ++i) {
        servers[i] = SSL_new(base); clients[i] = SSL_new(client);
        CHECK(servers[i] && clients[i]);
        BIO *s = NULL, *c = NULL;
        CHECK(BIO_new_bio_pair(&s, 0, &c, 0));
        SSL_set_bio(servers[i], s, s); SSL_set_bio(clients[i], c, c);
        SSL_set_accept_state(servers[i]); SSL_set_connect_state(clients[i]);
        CHECK(SSL_set_tlsext_host_name(clients[i], "localhost") && SSL_set1_host(clients[i], "localhost"));
        for (int j = 0; j < 100 && (!SSL_is_init_finished(servers[i]) || !SSL_is_init_finished(clients[i])); ++j) {
            CHECK(handshake_step(clients[i]) && handshake_step(servers[i]));
        }
        CHECK(SSL_is_init_finished(servers[i]) && SSL_is_init_finished(clients[i]));
        CHECK(SSL_get_verify_result(clients[i]) == X509_V_OK && SSL_get_SSL_CTX(servers[i]) == cached);
        CHECK(SSL_version(clients[i]) == version);
    }
    SSL_CTX_set_tlsext_servername_callback(base, NULL);
    SSL_CTX_set_tlsext_servername_arg(base, NULL);
    SSL_CTX_free(cached); /* evict cache ownership while both SSLs are active */
    CHECK(destroyed == 0);
    for (int i = 0; i < 2; ++i) {
        char buf[8] = {0};
        CHECK(SSL_write(clients[i], "ping", 4) == 4 && SSL_read(servers[i], buf, sizeof(buf)) == 4 && memcmp(buf, "ping", 4) == 0);
        CHECK(SSL_write(servers[i], "pong", 4) == 4 && SSL_read(clients[i], buf, sizeof(buf)) == 4 && memcmp(buf, "pong", 4) == 0);
        SSL_free(clients[i]); SSL_free(servers[i]);
        CHECK(destroyed == (unsigned)i);
    }
    SSL_CTX_free(client); SSL_CTX_free(base); X509_free(cert); EVP_PKEY_free(key);
    printf("PASS: TLS 0x%x strict SNI handshake, eviction, live bidirectional data and final context destruction\n", version);
    return 0;
}
int main(void) {
    if (run_version(TLS1_2_VERSION)) return 1;
    return run_version(TLS1_3_VERSION);
}
