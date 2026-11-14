// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef ADAWAY_SPIKE_H
#define ADAWAY_SPIKE_H
#include "platform.h"
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#define SPIKE_CONNECTIONS 128
#define SPIKE_CERTS 256
#define SPIKE_CHUNK 4096
#define SPIKE_BUFFER 16384
#define SPIKE_FORWARDS 64
struct spike_options {
  const char *ipv4;
  const char *ipv6;
  const char *directory;
  const char *image;
  unsigned http;
  unsigned https;
  unsigned lifetime;
  unsigned cert_age_ms;
  unsigned leaf_seconds;
  unsigned timeout_seconds;
  bool logging;
  bool debug;
  unsigned upstream_http;
  unsigned upstream_https;
  const char *upstream_ca;
};
struct spike_leaf {
  char host[254];
  X509 *certificate;
  uint64_t born;
};
struct spike_certificates {
  X509 *ca;
  X509 *fallback;
  EVP_PKEY *ca_key;
  EVP_PKEY *leaf_key;
  struct spike_leaf leaves[SPIKE_CERTS];
  unsigned next;
  unsigned hits;
  unsigned misses;
  unsigned evictions;
  unsigned renewals;
  unsigned age_ms;
  unsigned leaf_seconds;
};
struct spike_app {
  struct spike_options options;
  struct spike_certificates certificates;
  unsigned char *image;
  size_t image_length;
  unsigned char text[4096];
  FILE *log;
  uint64_t requests;
  uint64_t ws_messages;
  unsigned active;
  unsigned peak;
  unsigned rejected;
  unsigned forwarding;
  unsigned forwarding_peak;
  volatile sig_atomic_t stop;
};
struct spike_reply {
  unsigned status;
  const char *type;
  const unsigned char *body;
  size_t length;
  char scratch[1024];
};
uint64_t spike_now(void);
int spike_certificates_init(struct spike_app *app);
void spike_certificates_free(struct spike_app *app);
int spike_configure_tls(struct spike_app *app, SSL_CTX *context);
void spike_configure_ssl(struct spike_app *app, SSL *ssl);
int spike_init(struct spike_app *app);
void spike_forward_observed(struct spike_app *app);
void spike_free(struct spike_app *app);
void spike_reply(struct spike_app *app, const char *path, bool conditional,
                 struct spike_reply *reply);
int spike_backend(struct spike_app *app);
#endif
