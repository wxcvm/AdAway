// SPDX-License-Identifier: GPL-3.0-or-later
#include "spike.h"
#include <libwebsockets.h>
#include <string.h>
struct session {
  struct spike_reply reply;
  size_t sent;
  size_t ws_length;
  bool forward_counted;
  unsigned char websocket[SPIKE_CHUNK];
};
static int handle_http(struct lws *wsi, struct spike_app *app,
                       struct session *session, void *in) {
  if (!session)
    return -1;
  char path[256] = {0};
  if (lws_hdr_copy(wsi, path, sizeof(path), WSI_TOKEN_GET_URI) <= 0)
    return -1; /* Same bounded URI allowance as the baseline adapter. */
  in = path;
  char etag[64] = {0};
  lws_hdr_copy(wsi, etag, sizeof(etag), WSI_TOKEN_HTTP_IF_NONE_MATCH);
  spike_reply(app, (const char *)in, !strcmp(etag, "\"spike-v1\""),
              &session->reply);
  session->sent = 0;
  unsigned char buffer[LWS_PRE + 1024];
  unsigned char *start = buffer + LWS_PRE;
  unsigned char *p = start;
  unsigned char *end = buffer + sizeof(buffer);
  if (lws_add_http_common_headers(
          wsi, session->reply.status, session->reply.type,
          (lws_filepos_t)session->reply.length, &p, end))
    return -1;
  if (!strcmp((const char *)in, "/image") &&
      lws_add_http_header_by_name(wsi, (const unsigned char *)"etag:",
                                  (const unsigned char *)"\"spike-v1\"", 10, &p,
                                  end))
    return -1;
  if (lws_finalize_write_http_header(wsi, start, &p, end))
    return -1;
  if (!session->reply.length)
    return lws_http_transaction_completed(wsi) ? -1 : 0;
  lws_callback_on_writable(wsi);
  return 0;
}
static int write_http(struct lws *wsi, struct session *session) {
  if (!session || session->sent >= session->reply.length)
    return 0;
  unsigned char buffer[LWS_PRE + SPIKE_CHUNK];
  size_t remaining = session->reply.length - session->sent;
  size_t n = remaining > SPIKE_CHUNK ? SPIKE_CHUNK : remaining;
  memcpy(buffer + LWS_PRE, session->reply.body + session->sent, n);
  int sent = lws_write(wsi, buffer + LWS_PRE, n,
                       n == remaining ? LWS_WRITE_HTTP_FINAL : LWS_WRITE_HTTP);
  if (sent != (int)n)
    return -1;
  session->sent += n;
  if (session->sent == session->reply.length)
    return lws_http_transaction_completed(wsi) ? -1 : 0;
  lws_callback_on_writable(wsi);
  return 0;
}
static int callback(struct lws *wsi, enum lws_callback_reasons reason,
                    void *user, void *in, size_t length) {
  struct spike_app *app = lws_context_user(lws_get_context(wsi));
  struct session *session = user;
  switch (reason) {
  case LWS_CALLBACK_FILTER_HTTP_CONNECTION: {
    char path[256] = {0};
    lws_hdr_copy(wsi, path, sizeof(path), WSI_TOKEN_GET_URI);
    if (!strncmp(path, "/forward", 8) &&
        (app->options.upstream_http || app->options.upstream_https) &&
        (strcmp(path, "/forward/text") && strcmp(path, "/forward-tls/text"))) {
      lws_return_http_status(wsi, 404, "fixture route not supported");
      return 1;
    }
    if (session &&
        ((!strcmp(path, "/forward/text") && app->options.upstream_http) ||
         (!strcmp(path, "/forward-tls/text") && app->options.upstream_https)) &&
        !session->forward_counted) {
      if (lws_hdr_total_length(wsi, WSI_TOKEN_HTTP_CONTENT_LENGTH) ||
          lws_hdr_total_length(wsi, WSI_TOKEN_HTTP_TRANSFER_ENCODING))
        return -1; /* This fixture deliberately supports bodyless GET only. */
      if (app->forwarding >= SPIKE_FORWARDS) {
        spike_forward_observed(app);
        lws_return_http_status(wsi, 503, "forward cap reached");
        return 1;
      }
      session->forward_counted = true;
      app->forwarding++;
      if (app->forwarding > app->forwarding_peak)
        app->forwarding_peak = app->forwarding;
      spike_forward_observed(app);
    }
    return 0;
  }
  case LWS_CALLBACK_CLOSED_HTTP:
  case LWS_CALLBACK_HTTP_DROP_PROTOCOL:
    if (session && session->forward_counted) {
      if (app->forwarding)
        app->forwarding--;
      session->forward_counted = false;
    }
    return 0;

  case LWS_CALLBACK_OPENSSL_LOAD_EXTRA_SERVER_VERIFY_CERTS:
    return spike_configure_tls(app, (SSL_CTX *)user) ? 0 : 1;
  case LWS_CALLBACK_FILTER_NETWORK_CONNECTION:
    if (app->active >= SPIKE_CONNECTIONS) {
      app->rejected++;
      return 1;
    }
    return 0;
  case LWS_CALLBACK_SERVER_NEW_CLIENT_INSTANTIATED:
    /* LWS uses this adoption reason for both sides of an HTTP proxy. Every
     * outbound connection in this fixture is a public parent-linked child;
     * it consumes the separate forward budget, not incoming admission. */
    if (lws_get_parent(wsi))
      return 0;
    lws_set_opaque_user_data(wsi, app);
    app->active++;
    if (app->active > app->peak)
      app->peak = app->active;
    lws_set_timeout(wsi, PENDING_TIMEOUT_ESTABLISH_WITH_SERVER,
                    (int)app->options.timeout_seconds);
    return 0;
  case LWS_CALLBACK_WSI_DESTROY:
    if (lws_get_opaque_user_data(wsi) == app) {
      if (app->active)
        app->active--;
      lws_set_opaque_user_data(wsi, NULL);
    }
    return 0;
  case LWS_CALLBACK_HTTP:
    return handle_http(wsi, app, session, in);
  case LWS_CALLBACK_HTTP_WRITEABLE:
    if (session && session->forward_counted)
      return lws_callback_http_dummy(wsi, reason, user, in, length);
    return write_http(wsi, session);
  case LWS_CALLBACK_RECEIVE:
    if (!session || length > SPIKE_CHUNK - session->ws_length)
      return -1;
    memcpy(session->websocket + session->ws_length, in, length);
    session->ws_length += length;
    if (lws_is_final_fragment(wsi) && !lws_remaining_packet_payload(wsi)) {
      app->ws_messages++;
      lws_callback_on_writable(wsi);
    }
    return 0;
  case LWS_CALLBACK_SERVER_WRITEABLE: {
    if (!session)
      return -1;
    unsigned char buffer[LWS_PRE + SPIKE_CHUNK];
    memcpy(buffer + LWS_PRE, session->websocket, session->ws_length);
    int n =
        lws_write(wsi, buffer + LWS_PRE, session->ws_length, LWS_WRITE_TEXT);
    if (n != (int)session->ws_length)
      return -1;
    session->ws_length = 0;
    return 0;
  }
  default:
    return lws_callback_http_dummy(wsi, reason, user, in, length);
  }
}
static const struct lws_protocols protocols[] = {
    {"http", callback, sizeof(struct session), SPIKE_CHUNK, 0, NULL, 0},
    {"spike", callback, sizeof(struct session), SPIKE_CHUNK, 0, NULL, 0},
    LWS_PROTOCOL_LIST_TERM};
struct ticker {
  lws_sorted_usec_list_t sul;
  struct lws_context *context;
};
static void tick(lws_sorted_usec_list_t *sul) {
  struct ticker *t = (struct ticker *)sul;
  lws_sul_schedule(t->context, 0, &t->sul, tick, 100000);
}
static bool listen_on(struct lws_context *context, struct spike_app *app,
                      const char *address, bool ipv6, bool tls,
                      const struct lws_http_mount *mounts) {
  if (!address)
    return true;
  struct lws_context_creation_info info;
  memset(&info, 0, sizeof(info));
  info.port = (int)(tls ? app->options.https : app->options.http);
  info.iface = address;
  info.protocols = protocols;
  info.mounts = mounts;
  /* Fixture upstream trust is explicit and identical to the baseline. Server
   * vhosts do not need four implicit copies of the operating system CA set. */
  info.options = LWS_SERVER_OPTION_DISABLE_OS_CA_CERTS;
  if (app->options.upstream_ca) {
    info.client_ssl_ca_filepath = app->options.upstream_ca;
  }
  if (ipv6)
    info.vhost_name = tls ? "tls6.spike.invalid" : "http6.spike.invalid";
  else
    info.vhost_name = tls ? "tls4.spike.invalid" : "http4.spike.invalid";
  info.options |= LWS_SERVER_OPTION_DO_SSL_GLOBAL_INIT;
  if (ipv6)
    info.options |= LWS_SERVER_OPTION_IPV6_V6ONLY_MODIFY |
                    LWS_SERVER_OPTION_IPV6_V6ONLY_VALUE;
  else
    info.options |= LWS_SERVER_OPTION_DISABLE_IPV6;
  if (tls)
    info.options |= LWS_SERVER_OPTION_CREATE_VHOST_SSL_CTX;
  info.timeout_secs = (unsigned short)app->options.timeout_seconds;
  info.timeout_secs_ah_idle = (unsigned short)app->options.timeout_seconds;
  info.keepalive_timeout = (int)app->options.timeout_seconds;
  return lws_create_vhost(context, &info) != NULL;
}
int spike_backend(struct spike_app *app) {
  lws_set_log_level(app->options.debug ? LLL_ERR | LLL_WARN | LLL_NOTICE |
                                             LLL_INFO | LLL_CLIENT
                                       : LLL_ERR | LLL_WARN,
                    NULL);
  struct lws_context_creation_info info;
  memset(&info, 0, sizeof(info));
  info.port = CONTEXT_PORT_NO_LISTEN;
  info.protocols = protocols;
  info.user = app;
  info.options =
      LWS_SERVER_OPTION_EXPLICIT_VHOSTS | LWS_SERVER_OPTION_DO_SSL_GLOBAL_INIT;
  info.fd_limit_per_thread = SPIKE_CONNECTIONS + SPIKE_FORWARDS + 16;
  info.max_http_header_data2 = SPIKE_BUFFER;
  info.max_http_header_pool2 = SPIKE_CONNECTIONS + SPIKE_FORWARDS;
  info.timeout_secs = (unsigned short)app->options.timeout_seconds;
  struct lws_context *context = lws_create_context(&info);
  if (!context)
    return 4;
  struct lws_http_mount root_mount;
  memset(&root_mount, 0, sizeof(root_mount));
  root_mount.mountpoint = "/";
  root_mount.protocol = "http";
  root_mount.origin_protocol = LWSMPRO_CALLBACK;
  root_mount.mountpoint_len = 1;
  struct lws_http_mount http_mount;
  memset(&http_mount, 0, sizeof(http_mount));
  struct lws_http_mount tls_mount;
  memset(&tls_mount, 0, sizeof(tls_mount));
  const struct lws_http_mount *mounts = NULL;
  char http_origin[80];
  char tls_origin[80];
  if (app->options.upstream_http) {
    snprintf(http_origin, sizeof(http_origin), "127.0.0.1:%u/",
             app->options.upstream_http);
    http_mount.mountpoint = "/forward";
    http_mount.mountpoint_len = 8;
    http_mount.origin = http_origin;
    http_mount.origin_protocol = LWSMPRO_HTTP;
    http_mount.mount_next = &root_mount;
    mounts = &http_mount;
  }
  if (app->options.upstream_https) {
    snprintf(tls_origin, sizeof(tls_origin), "127.0.0.1:%u/",
             app->options.upstream_https);
    tls_mount.mountpoint = "/forward-tls";
    tls_mount.mountpoint_len = 12;
    tls_mount.origin = tls_origin;
    tls_mount.origin_protocol = LWSMPRO_HTTPS;
    tls_mount.mount_next = mounts ? mounts : &root_mount;
    mounts = &tls_mount;
  }
  bool ready =
      listen_on(context, app, app->options.ipv4, false, false, mounts) &&
      listen_on(context, app, app->options.ipv6, true, false, mounts) &&
      listen_on(context, app, app->options.ipv4, false, true, mounts) &&
      listen_on(context, app, app->options.ipv6, true, true, mounts);
  if (!ready) {
    lws_context_destroy(context);
    return 4;
  }
  struct ticker ticker;
  memset(&ticker, 0, sizeof(ticker));
  ticker.context = context;
  lws_sul_schedule(context, 0, &ticker.sul, tick, 100000);
  puts("READY lws");
  fflush(stdout);
  uint64_t started = spike_now();
  while (!app->stop &&
         spike_now() - started < (uint64_t)app->options.lifetime * 1000)
    if (lws_service(context, 0) < 0)
      break;
  lws_sul_cancel(&ticker.sul);
  lws_context_destroy(context);
  return app->active ? 5 : 0;
}
