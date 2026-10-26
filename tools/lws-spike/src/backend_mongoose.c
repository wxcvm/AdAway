// SPDX-License-Identifier: GPL-3.0-or-later
// Baseline adapter uses the vendored public Mongoose header, never copies its
// implementation.
#include "mongoose.h"
#include "spike.h"
#include <openssl/pem.h>
#include <stdlib.h>
#include <string.h>
struct forward;
struct connection {
  struct spike_reply reply;
  size_t sent;
  bool pending;
  bool tracked;
  uint64_t born;
  unsigned char *owned_body;
  struct forward *forward;
};
struct baseline {
  struct spike_app *app;
  struct mg_str cert;
  struct mg_str key;
  struct mg_str ca;
};
static void send_body(struct mg_connection *c, struct connection *state) {
  if (!state->pending || c->send.len > SPIKE_CHUNK)
    return;
  size_t n = state->reply.length - state->sent;
  if (n > SPIKE_CHUNK)
    n = SPIKE_CHUNK;
  if (n)
    mg_send(c, state->reply.body + state->sent, n);
  state->sent += n;
  if (state->sent == state->reply.length) {
    state->pending = false;
    c->is_resp = 0;
    free(state->owned_body);
    state->owned_body = NULL;
  }
}

struct forward {
  struct baseline *base;
  struct mg_connection *client;
  struct mg_connection *upstream;
  uint64_t started;
  bool tls;
};
static void send_headers(struct mg_connection *c, struct connection *state,
                         bool image) {
  state->sent = 0;
  state->pending = state->reply.length > 0;
  state->born = spike_now();
  mg_printf(c,
            "HTTP/1.1 %u Fixture\r\nContent-Type: %s\r\nContent-Length: "
            "%zu\r\n%s\r\n",
            state->reply.status, state->reply.type, state->reply.length,
            image ? "ETag: \"spike-v1\"\r\n" : "");
  send_body(c, state);
  if (!state->pending)
    c->is_resp = 0;
}
static struct connection *client_state(const struct mg_connection *c) {
  struct connection *state = NULL;
  memcpy(&state, c->data, sizeof(state));
  return state;
}
static void forward_result(struct forward *task, unsigned status,
                           const void *bytes, size_t length) {
  if (!task->client)
    return;
  struct connection *state = client_state(task->client);
  if (!state)
    return;
  state->forward = NULL;
  free(state->owned_body);
  state->owned_body = NULL;
  if (length) {
    state->owned_body = malloc(length);
    if (!state->owned_body) {
      task->client->is_closing = 1;
      task->client = NULL;
      return;
    }
    memcpy(state->owned_body, bytes, length);
  }
  state->reply = (struct spike_reply){.status = status,
                                      .type = "text/plain",
                                      .body = state->owned_body,
                                      .length = length};
  send_headers(task->client, state, false);
  task->client = NULL;
}
static void upstream_callback(struct mg_connection *c, int event, void *data) {
  struct forward *task = c->fn_data;
  if (event == MG_EV_CONNECT) {
    if (task->tls) {
      struct mg_tls_opts options = {0};
      options.ca = task->base->ca;
      options.name = mg_str("127.0.0.1");
      mg_tls_init(c, &options);
      struct mg_tls *tls = c->tls;
      if (!tls ||
          X509_VERIFY_PARAM_set1_host(SSL_get0_param(tls->ssl), NULL, 0) != 1 ||
          X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(tls->ssl),
                                        "127.0.0.1") != 1) {
        c->is_closing = 1;
        return;
      }
    }
    mg_printf(
        c,
        "GET /text HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
  } else if (event == MG_EV_HTTP_MSG) {
    struct mg_http_message *message = data;
    char status[4] = {0};
    if (message->uri.len != 3 || message->body.len > SPIKE_BUFFER) {
      c->is_closing = 1;
      return;
    }
    memcpy(status, message->uri.buf, 3);
    forward_result(task, (unsigned)strtoul(status, NULL, 10), message->body.buf,
                   message->body.len);
    c->is_closing = 1;
  } else if (event == MG_EV_POLL) {
    if (spike_now() - task->started >
        task->base->app->options.timeout_seconds * 1000)
      c->is_closing = 1;
  } else if (event == MG_EV_CLOSE) {
    if (task->client)
      forward_result(task, 502, "upstream failed", 15);
    if (task->base->app->forwarding)
      task->base->app->forwarding--;
    free(task);
  }
}
static void forward_start(struct mg_connection *client, struct baseline *base,
                          struct connection *state, bool tls) {
  spike_forward_observed(base->app);
  unsigned port = tls ? base->app->options.upstream_https
                      : base->app->options.upstream_http;
  if (!port || (tls && !base->ca.len) ||
      base->app->forwarding >= SPIKE_FORWARDS) {
    state->reply = (struct spike_reply){
        .status = 503,
        .type = "text/plain",
        .body = (const unsigned char *)"upstream unavailable",
        .length = 20};
    send_headers(client, state, false);
    return;
  }
  struct forward *task = calloc(1, sizeof(*task));
  if (!task) {
    client->is_closing = 1;
    return;
  }
  task->base = base;
  task->client = client;
  task->tls = tls;
  task->started = spike_now();
  char url[80];
  snprintf(url, sizeof(url), "%s://127.0.0.1:%u", tls ? "https" : "http", port);
  base->app->forwarding++;
  if (base->app->forwarding > base->app->forwarding_peak)
    base->app->forwarding_peak = base->app->forwarding;
  state->forward = task;
  task->upstream = mg_http_connect(client->mgr, url, upstream_callback, task);
  if (!task->upstream) {
    forward_result(task, 502, "upstream failed", 15);
    base->app->forwarding--;
    free(task);
  }
}
static void accepted(struct mg_connection *c, struct baseline *base) {
  struct spike_app *app = base->app;
  struct connection *state;
  if (app->active >= SPIKE_CONNECTIONS) {
    app->rejected++;
    c->is_closing = 1;
    return;
  }
  state = calloc(1, sizeof(*state));
  if (!state) {
    c->is_closing = 1;
    return;
  }
  memcpy(c->data, &state, sizeof(state));
  state->tracked = true;
  state->born = spike_now();
  app->active++;
  if (app->active > app->peak)
    app->peak = app->active;
  if (c->is_tls) {
    struct mg_tls_opts options = {0};
    options.cert = base->cert;
    options.key = base->key;
    mg_tls_init(c, &options);
    struct mg_tls *tls = c->tls;
    if (!tls || !spike_configure_tls(app, tls->ctx))
      c->is_closing = 1;
    else
      spike_configure_ssl(app, tls->ssl);
  }
}
static void handle_http(struct mg_connection *c, struct baseline *base,
                        struct connection *state, void *data) {
  struct spike_app *app = base->app;
  struct mg_http_message *message = data;
  if (mg_match(message->uri, mg_str("/ws"), NULL)) {
    mg_ws_upgrade(c, message, "Sec-WebSocket-Protocol: spike\r\n");
    return;
  }
  char path[256];
  if (message->uri.len >= sizeof(path)) {
    c->is_closing = 1;
    return;
  }
  memcpy(path, message->uri.buf, message->uri.len);
  path[message->uri.len] = 0;
  if (!strcmp(path, "/forward/text") || !strcmp(path, "/forward-tls/text")) {
    forward_start(c, base, state, !strcmp(path, "/forward-tls/text"));
    return;
  }
  const struct mg_str *etag = mg_http_get_header(message, "If-None-Match");
  bool conditional = etag && mg_strcmp(*etag, mg_str("\"spike-v1\"")) == 0;
  spike_reply(app, path, conditional, &state->reply);
  send_headers(c, state, !strcmp(path, "/image"));
}
static void callback(struct mg_connection *c, int event, void *data) {
  struct baseline *base = c->fn_data;
  struct spike_app *app = base->app;
  struct connection *state;
  memcpy(&state, c->data, sizeof(state));
  if (event == MG_EV_ACCEPT) {
    accepted(c, base);
  } else if (event == MG_EV_HTTP_MSG && state) {
    handle_http(c, base, state, data);
  } else if (event == MG_EV_WS_MSG) {
    struct mg_ws_message *message = data;
    if (message->data.len > SPIKE_CHUNK) {
      c->is_closing = 1;
      return;
    }
    app->ws_messages++;
    mg_ws_send(c, message->data.buf, message->data.len, WEBSOCKET_OP_TEXT);
  } else if ((event == MG_EV_POLL || event == MG_EV_WRITE) && state) {
    send_body(c, state);
    if (spike_now() - state->born > app->options.timeout_seconds * 1000)
      c->is_draining = 1;
  } else if (event == MG_EV_CLOSE && state) {
    if (state->tracked && app->active)
      app->active--;
    if (state->forward) {
      state->forward->client = NULL;
      if (state->forward->upstream)
        state->forward->upstream->is_closing = 1;
    }
    free(state->owned_body);
    free(state);
    state = NULL;
    memcpy(c->data, &state, sizeof(state));
  }
}
static bool listen_on(struct mg_mgr *mgr, struct baseline *base,
                      const char *address, unsigned port, bool tls) {
  if (!address)
    return true;
  char url[160];
  snprintf(url, sizeof(url), "%s://%s%s%s:%u", tls ? "https" : "http",
           strchr(address, ':') ? "[" : "", address,
           strchr(address, ':') ? "]" : "", port);
  return mg_http_listen(mgr, url, callback, base) != NULL;
}
int spike_backend(struct spike_app *app) {
  BIO *cert = BIO_new(BIO_s_mem());
  BIO *key = BIO_new(BIO_s_mem());
  if (!cert || !key) {
    BIO_free(cert);
    BIO_free(key);
    return 4;
  }
  if (!PEM_write_bio_X509(cert, app->certificates.fallback) ||
      !PEM_write_bio_PrivateKey(key, app->certificates.leaf_key, NULL, NULL, 0,
                                NULL, NULL)) {
    BIO_free(cert);
    BIO_free(key);
    return 4;
  }
  char *cert_data;
  char *key_data;
  long cert_size = BIO_get_mem_data(cert, &cert_data);
  long key_size = BIO_get_mem_data(key, &key_data);
  size_t trust_length = 0;
  char *trust = NULL;
  if (app->options.upstream_ca) {
    struct mg_str data = mg_file_read(&mg_fs_posix, app->options.upstream_ca);
    trust = data.buf;
    trust_length = data.len;
  }
  struct baseline base = {.app = app,
                          .cert = mg_str_n(cert_data, (size_t)cert_size),
                          .key = mg_str_n(key_data, (size_t)key_size),
                          .ca = mg_str_n(trust, trust_length)};
  struct mg_mgr mgr;
  mg_mgr_init(&mgr);
  mg_log_set(MG_LL_ERROR);
  bool ready =
      listen_on(&mgr, &base, app->options.ipv4, app->options.http, false) &&
      listen_on(&mgr, &base, app->options.ipv6, app->options.http, false) &&
      listen_on(&mgr, &base, app->options.ipv4, app->options.https, true) &&
      listen_on(&mgr, &base, app->options.ipv6, app->options.https, true);
  int result = 4;
  if (ready) {
    puts("READY mongoose");
    fflush(stdout);
    uint64_t started = spike_now();
    while (!app->stop &&
           spike_now() - started < (uint64_t)app->options.lifetime * 1000)
      mg_mgr_poll(&mgr, 1);
    result = 0;
  }
  mg_mgr_free(&mgr);
  free(trust);
  BIO_free(cert);
  BIO_free(key);
  return app->active ? 5 : result;
}
