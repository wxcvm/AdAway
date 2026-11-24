#include "mongoose.h"

#include <openssl/pem.h>
#include <openssl/rsa.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 0; } } while (0)
#define MAIN_CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); return 1; } } while (0)

struct identity {
  EVP_PKEY *key;
  X509 *cert;
  char *cert_pem;
  char *key_pem;
  struct mg_tls_opts opts;
  char host[256];
};

struct tls_pair {
  SSL_CTX *client_ctx;
  SSL *client;
  SSL *server;
};

static unsigned shared_ctx_destroyed;
static void on_shared_ctx_free(void *parent, void *ptr, CRYPTO_EX_DATA *ad,
                               int idx, long argl, void *argp) {
  (void) parent; (void) ad; (void) idx; (void) argl; (void) argp;
  if (ptr == &shared_ctx_destroyed) shared_ctx_destroyed++;
}

static int bio_copy(BIO *bio, char **out, size_t *len) {
  char *data = NULL;
  long n = BIO_get_mem_data(bio, &data);
  if (n <= 0 || data == NULL) return 0;
  *out = (char *) malloc((size_t) n);
  if (*out == NULL) return 0;
  memcpy(*out, data, (size_t) n);
  *len = (size_t) n;
  return 1;
}

static int identity_init(struct identity *id, const char *host) {
  memset(id, 0, sizeof(*id));
  if (snprintf(id->host, sizeof(id->host), "%s", host) >= (int) sizeof(id->host)) return 0;
  id->key = EVP_RSA_gen(2048);
  id->cert = X509_new();
  CHECK(id->key != NULL && id->cert != NULL);
  CHECK(X509_set_version(id->cert, 2) == 1);
  CHECK(ASN1_INTEGER_set(X509_get_serialNumber(id->cert), 1) == 1);
  CHECK(X509_gmtime_adj(X509_getm_notBefore(id->cert), -60) != NULL);
  CHECK(X509_gmtime_adj(X509_getm_notAfter(id->cert), 3600) != NULL);
  X509_NAME *name = X509_get_subject_name(id->cert);
  CHECK(name != NULL);
  CHECK(X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                                   (const unsigned char *) host, -1, -1, 0) == 1);
  CHECK(X509_set_issuer_name(id->cert, name) == 1);
  CHECK(X509_set_pubkey(id->cert, id->key) == 1);
  char san[256];
  CHECK(snprintf(san, sizeof(san), "DNS:%s", host) > 0);
  X509_EXTENSION *ext = X509V3_EXT_conf_nid(NULL, NULL, NID_subject_alt_name, san);
  CHECK(ext != NULL);
  CHECK(X509_add_ext(id->cert, ext, -1) == 1);
  X509_EXTENSION_free(ext);
  CHECK(X509_sign(id->cert, id->key, EVP_sha256()) > 0);

  BIO *cert_bio = BIO_new(BIO_s_mem());
  BIO *key_bio = BIO_new(BIO_s_mem());
  CHECK(cert_bio != NULL && key_bio != NULL);
  CHECK(PEM_write_bio_X509(cert_bio, id->cert) == 1);
  CHECK(PEM_write_bio_PrivateKey(key_bio, id->key, NULL, NULL, 0, NULL, NULL) == 1);
  size_t cert_len = 0, key_len = 0;
  int ok = bio_copy(cert_bio, &id->cert_pem, &cert_len) &&
           bio_copy(key_bio, &id->key_pem, &key_len);
  BIO_free(cert_bio);
  BIO_free(key_bio);
  CHECK(ok);
  id->opts.cert.buf = id->cert_pem;
  id->opts.cert.len = cert_len;
  id->opts.key.buf = id->key_pem;
  id->opts.key.len = key_len;
  return 1;
}

static void identity_free(struct identity *id) {
  free(id->cert_pem);
  free(id->key_pem);
  X509_free(id->cert);
  EVP_PKEY_free(id->key);
  memset(id, 0, sizeof(*id));
}

static int pair_start(struct mg_connection *server_conn,
                      const struct identity *id, int version,
                      struct tls_pair *pair) {
  struct mg_tls *tls = (struct mg_tls *) server_conn->tls;
  BIO *server_bio = NULL, *client_bio = NULL;
  memset(pair, 0, sizeof(*pair));
  pair->client_ctx = SSL_CTX_new(TLS_client_method());
  CHECK(pair->client_ctx != NULL);
  CHECK(X509_STORE_add_cert(SSL_CTX_get_cert_store(pair->client_ctx), id->cert) == 1);
  SSL_CTX_set_verify(pair->client_ctx, SSL_VERIFY_PEER, NULL);
  pair->client = SSL_new(pair->client_ctx);
  CHECK(pair->client != NULL);
  CHECK(BIO_new_bio_pair(&server_bio, 0, &client_bio, 0) == 1);
  SSL_set_bio(tls->ssl, server_bio, server_bio);
  SSL_set_bio(pair->client, client_bio, client_bio);
  SSL_set_accept_state(tls->ssl);
  SSL_set_connect_state(pair->client);
  CHECK(SSL_set_tlsext_host_name(pair->client, id->host) == 1);
  CHECK(SSL_set1_host(pair->client, id->host) == 1);
  CHECK(SSL_set_min_proto_version(tls->ssl, version) == 1);
  CHECK(SSL_set_max_proto_version(tls->ssl, version) == 1);
  CHECK(SSL_set_min_proto_version(pair->client, version) == 1);
  CHECK(SSL_set_max_proto_version(pair->client, version) == 1);
  pair->server = tls->ssl;
  return 1;
}

static int pair_handshake(struct tls_pair *pair) {
  for (int i = 0; i < 1000; ++i) {
    if (!SSL_is_init_finished(pair->client)) {
      int rc = SSL_do_handshake(pair->client);
      if (rc != 1) {
        int err = SSL_get_error(pair->client, rc);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) return 0;
      }
    }
    if (!SSL_is_init_finished(pair->server)) {
      int rc = SSL_do_handshake(pair->server);
      if (rc != 1) {
        int err = SSL_get_error(pair->server, rc);
        if (err != SSL_ERROR_WANT_READ && err != SSL_ERROR_WANT_WRITE) return 0;
      }
    }
    if (SSL_is_init_finished(pair->client) &&
        SSL_is_init_finished(pair->server))
      return SSL_get_verify_result(pair->client) == X509_V_OK;
  }
  return 0;
}

static int pair_exchange(struct tls_pair *pair) {
  char buf[8] = {0};
  CHECK(SSL_write(pair->client, "ping", 4) == 4);
  CHECK(SSL_read(pair->server, buf, sizeof(buf)) == 4);
  CHECK(memcmp(buf, "ping", 4) == 0);
  memset(buf, 0, sizeof(buf));
  CHECK(SSL_write(pair->server, "pong", 4) == 4);
  CHECK(SSL_read(pair->client, buf, sizeof(buf)) == 4);
  CHECK(memcmp(buf, "pong", 4) == 0);
  return 1;
}

static void pair_free(struct tls_pair *pair) {
  SSL_free(pair->client);
  SSL_CTX_free(pair->client_ctx);
  memset(pair, 0, sizeof(*pair));
}

static double init_benchmark(struct mg_mgr *mgr, const struct identity *id,
                             int expect_shared, int iterations) {
  clock_t start = clock();
  if (start == (clock_t) -1) return -1.0;
  for (int i = 0; i < iterations; ++i) {
    struct mg_connection conn;
    memset(&conn, 0, sizeof(conn));
    conn.mgr = mgr;
    conn.id = (unsigned long) i + 1;
    mg_tls_init(&conn, &id->opts);
    if (conn.tls == NULL) return -1.0;
    if (((struct mg_tls *) conn.tls)->ctx_shared != (expect_shared != 0)) {
      mg_tls_free(&conn);
      return -1.0;
    }
    mg_tls_free(&conn);
  }
  clock_t end = clock();
  if (end == (clock_t) -1 || end < start) return -1.0;
  return (double) (end - start) / CLOCKS_PER_SEC;
}

int main(void) {
  struct identity first, second;
  MAIN_CHECK(identity_init(&first, "localhost"));
  MAIN_CHECK(identity_init(&second, "other.test"));

  struct mg_mgr mgr;
  mg_mgr_init(&mgr);
  struct mg_connection c1, c2, c3, c4;
  memset(&c1, 0, sizeof(c1));
  memset(&c2, 0, sizeof(c2));
  memset(&c3, 0, sizeof(c3));
  memset(&c4, 0, sizeof(c4));
  c1.mgr = c2.mgr = c3.mgr = c4.mgr = &mgr;
  c1.id = 1; c2.id = 2; c3.id = 3; c4.id = 4;

  mg_tls_init(&c1, &first.opts);
  struct mg_tls_opts same_opts = first.opts;
  char *same_cert = (char *) malloc(first.opts.cert.len);
  char *same_key = (char *) malloc(first.opts.key.len);
  MAIN_CHECK(same_cert != NULL && same_key != NULL);
  memcpy(same_cert, first.opts.cert.buf, first.opts.cert.len);
  memcpy(same_key, first.opts.key.buf, first.opts.key.len);
  same_opts.cert.buf = same_cert;
  same_opts.key.buf = same_key;
  mg_tls_init(&c2, &same_opts);
  free(same_cert);
  free(same_key);
  MAIN_CHECK(c1.tls != NULL && c2.tls != NULL);
  struct mg_tls *t1 = (struct mg_tls *) c1.tls;
  struct mg_tls *t2 = (struct mg_tls *) c2.tls;
  MAIN_CHECK(t1->ctx_shared && t2->ctx_shared && t1->ctx == t2->ctx);
  SSL_CTX *shared_ctx = t1->ctx;
  int ex_index = SSL_CTX_get_ex_new_index(0, NULL, NULL, NULL, on_shared_ctx_free);
  MAIN_CHECK(ex_index >= 0);
  MAIN_CHECK(SSL_CTX_set_ex_data(shared_ctx, ex_index, &shared_ctx_destroyed) == 1);
  MAIN_CHECK(X509_cmp(SSL_CTX_get0_certificate(shared_ctx), first.cert) == 0);

  struct tls_pair p1, p2;
  MAIN_CHECK(pair_start(&c1, &first, TLS1_2_VERSION, &p1));
  MAIN_CHECK(pair_start(&c2, &first, TLS1_3_VERSION, &p2));
  MAIN_CHECK(pair_handshake(&p1) && pair_handshake(&p2));
  mg_tls_free(&c1);
  pair_free(&p1);
  MAIN_CHECK(pair_exchange(&p2));
  mg_tls_free(&c2);
  pair_free(&p2);

  mg_tls_init(&c3, &second.opts);
  MAIN_CHECK(c3.tls != NULL);
  struct mg_tls *t3 = (struct mg_tls *) c3.tls;
  MAIN_CHECK(!t3->ctx_shared && t3->ctx != shared_ctx);
  MAIN_CHECK(X509_cmp(SSL_get_certificate(t3->ssl), second.cert) == 0);
  struct tls_pair p3;
  MAIN_CHECK(pair_start(&c3, &second, TLS1_3_VERSION, &p3));
  MAIN_CHECK(pair_handshake(&p3) && pair_exchange(&p3));
  mg_tls_free(&c3);
  pair_free(&p3);

  int other_listener = 1;
  c4.fn_data = &other_listener;
  mg_tls_init(&c4, &first.opts);
  MAIN_CHECK(c4.tls != NULL);
  struct mg_tls *t4 = (struct mg_tls *) c4.tls;
  MAIN_CHECK(!t4->ctx_shared && t4->ctx != shared_ctx);
  mg_tls_free(&c4);

  double shared_seconds = init_benchmark(&mgr, &first, 1, 300);
  MAIN_CHECK(shared_seconds >= 0.0);
  struct mg_mgr uncached_mgr;
  mg_mgr_init(&uncached_mgr);
  mg_tls_ctx_free(&uncached_mgr);
  double uncached_seconds = init_benchmark(&uncached_mgr, &first, 0, 300);
  MAIN_CHECK(uncached_seconds >= 0.0);
  mg_mgr_free(&uncached_mgr);
  mg_mgr_free(&mgr);
  MAIN_CHECK(shared_ctx_destroyed == 1);
  identity_free(&first);
  identity_free(&second);

  printf("OpenSSL: %s\n", OpenSSL_version(OPENSSL_VERSION));
  printf("PASS: same identity shares manager SSL_CTX; different identity falls back\n");
  printf("PASS: TLS 1.2/1.3 strict hostname verification and live bidirectional data\n");
  printf("300 init cycles: shared=%.6fs uncached=%.6fs (initialization only)\n",
         shared_seconds, uncached_seconds);
  return 0;
}
