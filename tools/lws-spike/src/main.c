// SPDX-License-Identifier: GPL-3.0-or-later
#include "spike.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
static struct spike_app app;
static void stop(int signal_number) {
  (void)signal_number;
  app.stop = 1;
}
static unsigned number(const char *value, unsigned max) {
  char *end = NULL;
  errno = 0;
  unsigned long n = strtoul(value, &end, 10);
  if (errno || !value[0] || *end || n < 1 || n > max) {
    fprintf(stderr, "invalid positive argument\n");
    exit(2);
  }
  return (unsigned)n;
}
static bool value_option(struct spike_options *options, const char *key,
                         const char *value) {
  if (!strcmp(key, "--ipv4"))
    options->ipv4 = !strcmp(value, "-") ? NULL : value;
  else if (!strcmp(key, "--ipv6"))
    options->ipv6 = !strcmp(value, "-") ? NULL : value;
  else if (!strcmp(key, "--directory"))
    options->directory = value;
  else if (!strcmp(key, "--image"))
    options->image = value;
  else if (!strcmp(key, "--http"))
    options->http = number(value, 65535);
  else if (!strcmp(key, "--https"))
    options->https = number(value, 65535);
  else if (!strcmp(key, "--lifetime"))
    options->lifetime = number(value, 86400);
  else if (!strcmp(key, "--upstream-http"))
    options->upstream_http = number(value, 65535);
  else if (!strcmp(key, "--upstream-https"))
    options->upstream_https = number(value, 65535);
  else if (!strcmp(key, "--upstream-ca"))
    options->upstream_ca = value;
  else if (!strcmp(key, "--cert-age-ms"))
    options->cert_age_ms = number(value, 86400000);
  else if (!strcmp(key, "--leaf-seconds"))
    options->leaf_seconds = number(value, 86400 * 30);
  else if (!strcmp(key, "--timeout-seconds"))
    options->timeout_seconds = number(value, 30);
  else
    return false;
  return true;
}
int main(int argc, char **argv) {
  app.options = (struct spike_options){.ipv4 = "127.0.0.1",
                                       .ipv6 = "::1",
                                       .lifetime = 3600,
                                       .cert_age_ms = 3600000,
                                       .leaf_seconds = 86400 * 30,
                                       .timeout_seconds = 2};
  int i = 1;
  while (i < argc) {
    if (!strcmp(argv[i], "--debug")) {
      app.options.debug = true;
      i++;
      continue;
    }
    if (!strcmp(argv[i], "--logging")) {
      app.options.logging = true;
      i++;
      continue;
    }
    if (i + 1 == argc) {
      fprintf(stderr, "missing argument\n");
      return 2;
    }
    const char *key = argv[i];
    const char *value = argv[i + 1];
    i += 2;
    if (!value_option(&app.options, key, value)) {
      fprintf(stderr, "unknown argument\n");
      return 2;
    }
  }
  if (!app.options.directory || !app.options.image || !app.options.http ||
      !app.options.https || app.options.http == app.options.https ||
      (app.options.upstream_https && !app.options.upstream_ca) ||
      (!app.options.ipv4 && !app.options.ipv6))
    return 2;
  signal(SIGINT, stop);
  signal(SIGTERM, stop);
#ifndef _WIN32
  signal(SIGPIPE, SIG_IGN);
#endif
  if (!spike_init(&app)) {
    fprintf(stderr, "fixture initialization failed\n");
    spike_free(&app);
    return 3;
  }
  int result = spike_backend(&app);
  printf("{\"exit\":%d,\"requests\":%llu,\"openssl\":\"%s\"}\n", result,
         (unsigned long long)app.requests, OpenSSL_version(OPENSSL_VERSION));
  spike_free(&app);
  return result;
}
