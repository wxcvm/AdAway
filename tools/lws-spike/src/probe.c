// SPDX-License-Identifier: GPL-3.0-or-later
// Loopback-only native client: used inside disposable Android emulators (no adb
// forwarding).
#include <ctype.h>
#include <errno.h>
#include <openssl/ssl.h>
#include <openssl/x509v3.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#ifdef _MSC_VER
#include <WS2tcpip.h>
#include <WinSock2.h>
#else
#include <winsock2.h>
#include <ws2tcpip.h>
#endif
typedef SOCKET socket_type;
#define close_socket closesocket
#else
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>
typedef int socket_type;
#define INVALID_SOCKET (-1)
#define close_socket close
#endif
struct client {
  socket_type socket;
  SSL_CTX *context;
  SSL *tls;
};
static void finish(struct client *c) {
  SSL_free(c->tls);
  SSL_CTX_free(c->context);
  if (c->socket != INVALID_SOCKET)
    close_socket(c->socket);
}
static int connect_to(struct client *c, const char *ip, int port,
                      const char *ca, const char *identity) {
  memset(c, 0, sizeof(*c));
  c->socket = INVALID_SOCKET;
  bool ipv6 = strchr(ip, ':') != NULL;
  c->socket = socket(ipv6 ? AF_INET6 : AF_INET, SOCK_STREAM, 0);
  if (c->socket == INVALID_SOCKET)
    return 0;
#ifdef _WIN32
  DWORD timeout = 2500;
  setsockopt(c->socket, SOL_SOCKET, SO_RCVTIMEO, (char *)&timeout,
             sizeof(timeout));
#else
  struct timeval timeout = {2, 500000};
  setsockopt(c->socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
#endif
  struct sockaddr_storage storage;
  memset(&storage, 0, sizeof(storage));
  int length;
  if (ipv6) {
    struct sockaddr_in6 *a = (struct sockaddr_in6 *)&storage;
    a->sin6_family = AF_INET6;
    a->sin6_port = htons((unsigned short)port);
    inet_pton(AF_INET6, ip, &a->sin6_addr);
    length = sizeof(*a);
  } else {
    struct sockaddr_in *a = (struct sockaddr_in *)&storage;
    a->sin_family = AF_INET;
    a->sin_port = htons((unsigned short)port);
    inet_pton(AF_INET, ip, &a->sin_addr);
    length = sizeof(*a);
  }
  if (connect(c->socket, (struct sockaddr *)&storage, length))
    return 0;
  if (!identity)
    return 1;
  c->context = SSL_CTX_new(TLS_client_method());
  if (!c->context)
    return 0;
  if (!SSL_CTX_set_min_proto_version(c->context, TLS1_2_VERSION))
    return 0;
  SSL_CTX_set_verify(c->context, SSL_VERIFY_PEER, NULL);
  if (ca) {
    if (SSL_CTX_load_verify_locations(c->context, ca, NULL) != 1)
      return 0;
  } else if (SSL_CTX_set_default_verify_paths(c->context) != 1)
    return 0;
  c->tls = SSL_new(c->context);
  if (!c->tls)
    return 0;
  if (SSL_set_fd(c->tls, (int)c->socket) != 1 ||
      SSL_set1_host(c->tls, identity) != 1)
    return 0;
  unsigned char binary[16];
  if (inet_pton(AF_INET, identity, binary) == 1 ||
      inet_pton(AF_INET6, identity, binary) == 1) {
    if (X509_VERIFY_PARAM_set1_host(SSL_get0_param(c->tls), NULL, 0) != 1 ||
        X509_VERIFY_PARAM_set1_ip_asc(SSL_get0_param(c->tls), identity) != 1)
      return 0;
  } else if (SSL_set_tlsext_host_name(c->tls, identity) != 1 ||
             SSL_set1_host(c->tls, identity) != 1)
    return 0;
  return SSL_connect(c->tls) == 1 && SSL_get_verify_result(c->tls) == X509_V_OK;
}
static int parse_length(const char *value, const char *end, size_t *length) {
  while (*value == ' ' || *value == '\t')
    value++;
  char *tail;
  errno = 0;
  unsigned long number = strtoul(value, &tail, 10);
  if (value == tail || tail != end || errno || number > 262144)
    return 0;
  *length = (size_t)number;
  return 1;
}
static int response_lengths(const char *buffer, size_t used, size_t *headers,
                            size_t *body) {
  const char *end = strstr(buffer, "\r\n\r\n");
  if (!end)
    return 0;
  unsigned found = 0;
  const char *line = strstr(buffer, "\r\n");
  while (line && line < end) {
    line += 2;
    const char *next = strstr(line, "\r\n");
    if (!next || next > end)
      return -1;
    const char *prefix = "content-length:";
    size_t i = 0;
    while (prefix[i] && line + i < next &&
           tolower((unsigned char)line[i]) == prefix[i])
      i++;
    if (!prefix[i]) {
      if (++found != 1 || !parse_length(line + i, next, body))
        return -1;
    }
    line = next;
  }
  *headers = (size_t)(end + 4 - buffer);
  return found == 1 && used >= *headers ? 1 : -1;
}
static int verify_body(const char *body, size_t length, const char *path,
                       unsigned expected) {
  if (expected == 204 || !strcmp(path, "/stop"))
    return length == 0;
  if (expected == 403)
    return length == 9 && !memcmp(body, "forbidden", 9);
  if (expected == 404)
    return length == 7 && !memcmp(body, "missing", 7);
  if (!strcmp(path, "/image")) {
    unsigned char digest[EVP_MAX_MD_SIZE];
    unsigned count = 0;
    char hex[65];
    if (!EVP_Digest(body, length, digest, &count, EVP_sha256(), NULL) ||
        count != 32)
      return 0;
    for (unsigned i = 0; i < count; i++)
      snprintf(hex + 2 * i, 3, "%02x", digest[i]);
    return !strcmp(
        hex,
        "bea27c9599dd85c22aa4fd94a08441015d74bb6f80e2ccfb0ca307dc02223976");
  }
  if (length != 4096)
    return 0;
  for (size_t i = 0; i < length; i++)
    if (body[i] != 'x')
      return 0;
  return 1;
}
static int request(struct client *c, const char *path, unsigned expected) {
  char request[256];
  int n = snprintf(
      request, sizeof(request),
      "GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n", path);
  int sent =
      c->tls ? SSL_write(c->tls, request, n) : send(c->socket, request, n, 0);
  if (sent != n)
    return 0;
  char *buffer = malloc(262145);
  if (!buffer)
    return 0;
  size_t used = 0;
  size_t headers = 0;
  size_t body = 0;
  while (used < 262144) {
    int got = c->tls ? SSL_read(c->tls, buffer + used, (int)(262144 - used))
                     : recv(c->socket, buffer + used, (int)(262144 - used), 0);
    if (got <= 0)
      break;
    if ((size_t)got > 262144 - used) {
      free(buffer);
      return 0;
    }
    used += (size_t)got;
    buffer[used] = 0;
    int parsed = response_lengths(buffer, used, &headers, &body);
    if (parsed < 0)
      break;
    if (parsed && used >= headers + body)
      break;
  }
  buffer[used] = 0;
  unsigned long status = used >= 12 ? strtoul(buffer + 9, NULL, 10) : 0;
  int ok = used >= 12 && !strncmp(buffer, "HTTP/1.", 7) && status == expected &&
           headers && used == headers + body &&
           verify_body(buffer + headers, body, path, expected);
  free(buffer);
  return ok;
}
int main(int argc, char **argv) {
#ifdef _WIN32
  WSADATA winsock;
  if (WSAStartup(MAKEWORD(2, 2), &winsock))
    return 2;
#endif
  if (argc != 4)
    return 2;
  int http = atoi(argv[1]);
  int https = atoi(argv[2]);
  const char *ca = argv[3];
  if (http < 1 || http > 65535 || https < 1 || https > 65535)
    return 2;
  unsigned checks = 0;
  const char *ips[] = {"127.0.0.1", "::1"};
  const char *paths[] = {"/empty", "/text", "/image", "/denied", "/missing"};
  unsigned codes[] = {204, 200, 200, 403, 404};
  for (unsigned family = 0; family < 2; ++family) {
    for (unsigned i = 0; i < 5; ++i) {
      struct client c;
      int ok = connect_to(&c, ips[family], http, NULL, NULL) &&
               request(&c, paths[i], codes[i]);
      finish(&c);
      if (!ok) {
        fprintf(stderr, "native HTTP failed: address=%s path=%s\n", ips[family],
                paths[i]);
        return 3;
      }
      checks++;
    }
    for (unsigned i = 0; i < 3; ++i) {
      struct client c;
      const char *name = i ? "native.spike.invalid" : ips[family];
      int ok = connect_to(&c, ips[family], https, ca, name) &&
               request(&c, "/text", 200);
      finish(&c);
      if (!ok)
        return 4;
      checks++;
    }
  }
  struct client bad;
  int accepted = connect_to(&bad, "127.0.0.1", https, ca, "127.0.0.2");
  finish(&bad);
  if (accepted)
    return 5;
  checks++;
  accepted = connect_to(&bad, "127.0.0.1", https, NULL, "native.spike.invalid");
  finish(&bad);
  if (accepted)
    return 5;
  checks++;
  struct client stopping;
  if (connect_to(&stopping, "127.0.0.1", http, NULL, NULL))
    request(&stopping, "/stop", 200);
  finish(&stopping);
  printf("{\"native_loopback_checks\":%u,\"families\":[4,6],\"body_hashes_"
         "verified\":true,\"strict_tls\":"
         "true,\"openssl\":\"%s\"}\n",
         checks, OpenSSL_version(OPENSSL_VERSION));
#ifdef _WIN32
  WSACleanup();
#endif
  return 0;
}
