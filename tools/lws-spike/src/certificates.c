// SPDX-License-Identifier: GPL-3.0-or-later
#include "spike.h"
#include <openssl/pem.h>
#include <openssl/rand.h>
#include <stdlib.h>
#include <string.h>

static int extension(X509 *cert, X509 *issuer, int nid, const char *value) {
  X509V3_CTX context;
  X509V3_set_ctx(&context, issuer, cert, NULL, NULL, 0);
  X509_EXTENSION *ext = X509V3_EXT_conf_nid(NULL, &context, nid, value);
  if (!ext)
    return 0;
  int ok = X509_add_ext(cert, ext, -1);
  X509_EXTENSION_free(ext);
  return ok;
}
static bool identity_fields(X509 *cert, const char *name, X509 *issuer,
                            EVP_PKEY *key) {
  unsigned char serial[8];
  if (RAND_bytes(serial, sizeof(serial)) != 1)
    return false;
  serial[0] &= 0x7f;
  uint64_t number = 0;
  for (unsigned i = 0; i < sizeof(serial); ++i)
    number = number * 256 + serial[i];
  if (!ASN1_INTEGER_set_uint64(X509_get_serialNumber(cert), number) ||
      !X509_set_version(cert, 2) || !X509_set_pubkey(cert, key))
    return false;
  if (!X509_gmtime_adj(X509_getm_notBefore(cert), -3600) ||
      !X509_gmtime_adj(X509_getm_notAfter(cert), 86400L * 30))
    return false;
  X509_NAME *subject = X509_get_subject_name(cert);
  return X509_NAME_add_entry_by_txt(subject, "CN", MBSTRING_ASC,
                                    (const unsigned char *)name, -1, -1, 0) &&
         X509_set_issuer_name(cert,
                              issuer ? X509_get_subject_name(issuer) : subject);
}
static bool certificate_extensions(X509 *cert, const char *name, X509 *issuer,
                                   bool ca) {
  X509 *authority = issuer ? issuer : cert;
  if (!extension(cert, authority, NID_basic_constraints,
                 ca ? "critical,CA:TRUE" : "critical,CA:FALSE") ||
      !extension(cert, authority, NID_key_usage,
                 ca ? "critical,keyCertSign,cRLSign"
                    : "critical,digitalSignature"))
    return false;
  if (!extension(cert, authority, NID_subject_key_identifier, "hash") ||
      !extension(cert, authority, NID_authority_key_identifier, "keyid:always"))
    return false;
  if (ca)
    return true;
  char san[512];
  if (!strcmp(name, "localhost"))
    snprintf(san, sizeof(san), "DNS:localhost,IP:127.0.0.1,IP:::1");
  else
    snprintf(san, sizeof(san), "DNS:%s", name);
  return extension(cert, issuer, NID_subject_alt_name, san) &&
         extension(cert, issuer, NID_ext_key_usage, "serverAuth");
}
static X509 *issue(const char *name, X509 *issuer, EVP_PKEY *signer,
                   EVP_PKEY *key, bool ca) {
  X509 *cert = X509_new();
  if (cert && identity_fields(cert, name, issuer, key) &&
      certificate_extensions(cert, name, issuer, ca) &&
      X509_sign(cert, signer, EVP_sha256()))
    return cert;
  X509_free(cert);
  return NULL;
}
static bool safe_name(const char *host) {
  size_t n = strlen(host);
  if (!n || n > 253 || host[0] == '-' || host[n - 1] == '-')
    return false;
  for (size_t i = 0; i < n; ++i) {
    unsigned char c = (unsigned char)host[i];
    if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
          (c >= '0' && c <= '9') || c == '-' || c == '.'))
      return false;
    if (c == '.' && (!i || i + 1 == n || host[i - 1] == '.'))
      return false;
  }
  return true;
}
/* OpenSSL's public certificate callback runs after LWS's untouched SNI routing.
 * Per-SSL certificate/key references avoid replacing LWS-owned SSL_CTX objects.
 */
static int find_leaf(const struct spike_certificates *cache, const char *host) {
  for (unsigned i = 0; i < SPIKE_CERTS; ++i) {
    if (cache->leaves[i].certificate && !strcmp(cache->leaves[i].host, host))
      return (int)i;
  }
  return -1;
}
static X509 *select_leaf(struct spike_certificates *cache, const char *host) {
  if (!host || !strcmp(host, "localhost"))
    return cache->fallback;
  if (!safe_name(host))
    return NULL;
  uint64_t now = spike_now();
  int slot = find_leaf(cache, host);
  if (slot >= 0 && now - cache->leaves[slot].born < cache->age_ms) {
    cache->hits++;
    return cache->leaves[slot].certificate;
  }
  X509 *fresh = issue(host, cache->ca, cache->ca_key, cache->leaf_key, false);
  if (!fresh)
    return NULL;
  if (slot >= 0)
    cache->renewals++;
  else
    slot = (int)(cache->next++ % SPIKE_CERTS);
  if (cache->leaves[slot].certificate)
    cache->evictions++;
  X509_free(cache->leaves[slot].certificate);
  cache->leaves[slot].certificate = fresh;
  cache->leaves[slot].born = now;
  snprintf(cache->leaves[slot].host, sizeof(cache->leaves[slot].host), "%s",
           host);
  cache->misses++;
  return fresh;
}
static int choose(SSL *ssl, void *opaque) {
  struct spike_app *app = opaque;
  X509 *cert = select_leaf(&app->certificates,
                           SSL_get_servername(ssl, TLSEXT_NAMETYPE_host_name));
  return cert && SSL_use_certificate(ssl, cert) == 1 &&
         SSL_use_PrivateKey(ssl, app->certificates.leaf_key) == 1 &&
         SSL_check_private_key(ssl) == 1;
}
void spike_configure_ssl(struct spike_app *app, SSL *ssl) {
  SSL_set_cert_cb(ssl, choose, app);
}
int spike_configure_tls(struct spike_app *app, SSL_CTX *context) {
  if (!context || !SSL_CTX_set_min_proto_version(context, TLS1_2_VERSION) ||
      SSL_CTX_use_certificate(context, app->certificates.fallback) != 1 ||
      SSL_CTX_use_PrivateKey(context, app->certificates.leaf_key) != 1)
    return 0;
  SSL_CTX_set_cert_cb(context, choose, app);
  return 1;
}
static bool load_or_create_ca(struct spike_certificates *cache,
                              const char *cert_path, const char *key_path) {
  FILE *cert_file = spike_open(cert_path, "rb");
  FILE *key_file = spike_open(key_path, "rb");
  bool ok = false;
  if (cert_file && key_file) {
    cache->ca = PEM_read_X509(cert_file, NULL, NULL, NULL);
    cache->ca_key = PEM_read_PrivateKey(key_file, NULL, NULL, NULL);
    ok = cache->ca && cache->ca_key;
  } else if (!cert_file && !key_file) {
    cache->ca_key = EVP_PKEY_Q_keygen(NULL, NULL, "EC", "prime256v1");
    if (cache->ca_key)
      cache->ca = issue("AdAway isolated test CA", NULL, cache->ca_key,
                        cache->ca_key, true);
    if (cache->ca) {
      cert_file = spike_open(cert_path, "wbx");
      key_file = spike_open(key_path, "wbx");
      ok = cert_file && key_file && PEM_write_X509(cert_file, cache->ca) &&
           PEM_write_PrivateKey(key_file, cache->ca_key, NULL, NULL, 0, NULL,
                                NULL);
    }
  }
  if (cert_file && fclose(cert_file))
    ok = false;
  if (key_file && fclose(key_file))
    ok = false;
  return ok;
}
int spike_certificates_init(struct spike_app *app) {
  struct spike_certificates *cache = &app->certificates;
  char cert_path[1024];
  char key_path[1024];
  if (snprintf(cert_path, sizeof(cert_path), "%s/ca.pem",
               app->options.directory) >= (int)sizeof(cert_path) ||
      snprintf(key_path, sizeof(key_path), "%s/ca-key.pem",
               app->options.directory) >= (int)sizeof(key_path))
    return 0;
  if (!load_or_create_ca(cache, cert_path, key_path))
    return 0;
  if (X509_check_private_key(cache->ca, cache->ca_key) != 1 ||
      X509_check_issued(cache->ca, cache->ca) != X509_V_OK ||
      X509_verify(cache->ca, cache->ca_key) != 1 ||
      X509_check_ca(cache->ca) <= 0)
    return 0;
  cache->leaf_key = EVP_PKEY_Q_keygen(NULL, NULL, "EC", "prime256v1");
  if (cache->leaf_key)
    cache->fallback =
        issue("localhost", cache->ca, cache->ca_key, cache->leaf_key, false);
  cache->age_ms = app->options.cert_age_ms;
  return cache->fallback != NULL;
}
void spike_certificates_free(struct spike_app *app) {
  for (unsigned i = 0; i < SPIKE_CERTS; ++i)
    X509_free(app->certificates.leaves[i].certificate);
  X509_free(app->certificates.fallback);
  X509_free(app->certificates.ca);
  EVP_PKEY_free(app->certificates.leaf_key);
  EVP_PKEY_free(app->certificates.ca_key);
}
