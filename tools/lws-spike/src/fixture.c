// SPDX-License-Identifier: GPL-3.0-or-later
#include "spike.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>
#ifdef _WIN32
#ifdef _MSC_VER
#include <Windows.h>
#else
#include <windows.h>
#endif
#else
#include <unistd.h>
#endif
uint64_t spike_now(void) {
#ifdef _WIN32
  return GetTickCount64();
#else
  struct timespec now;
  clock_gettime(CLOCK_MONOTONIC, &now);
  return (uint64_t)now.tv_sec * 1000 + (uint64_t)now.tv_nsec / 1000000;
#endif
}
int spike_init(struct spike_app *app) {
  memset(app->text, 'x', sizeof(app->text));
  FILE *image = spike_open(app->options.image, "rb");
  if (!image)
    return 0;
  bool ok = fseek(image, 0, SEEK_END) == 0;
  long length = ok ? ftell(image) : -1;
  if (length <= 0 || length > 1048576) {
    fclose(image);
    return 0;
  }
  rewind(image);
  app->image = malloc((size_t)length);
  app->image_length = (size_t)length;
  ok = app->image &&
       fread(app->image, 1, app->image_length, image) == app->image_length;
  if (fclose(image))
    ok = false;
  if (!ok || !spike_certificates_init(app))
    return 0;
  if (app->options.logging) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/requests.log", app->options.directory);
    app->log = spike_open(path, "ab");
    if (!app->log)
      return 0;
  }
  return 1;
}
void spike_free(struct spike_app *app) {
  if (app->log && fclose(app->log))
    fprintf(stderr, "test log close failed\n");
  free(app->image);
  spike_certificates_free(app);
}
void spike_reply(struct spike_app *app, const char *path, bool conditional,
                 struct spike_reply *reply) {
  memset(reply, 0, sizeof(*reply));
  reply->type = "text/plain";
  reply->status = 200;
  if (!strcmp(path, "/stats")) {
    int n = snprintf(reply->scratch, sizeof(reply->scratch),
                     "{\"requests\":%llu,\"ws\":%llu,\"active\":%u,\"peak\":%u,"
                     "\"rejected\":%u,\"cert_hits\":%u,\"cert_misses\":%u,"
                     "\"cert_evictions\":%u,\"cert_renewals\":%u}",
                     (unsigned long long)app->requests,
                     (unsigned long long)app->ws_messages, app->active,
                     app->peak, app->rejected, app->certificates.hits,
                     app->certificates.misses, app->certificates.evictions,
                     app->certificates.renewals);
    reply->body = (unsigned char *)reply->scratch;
    reply->length = (size_t)n;
    reply->type = "application/json";
    return;
  }
  if (!strcmp(path, "/stop")) {
    app->stop = 1;
    return;
  }
  app->requests++;
  if (!strcmp(path, "/empty")) {
    reply->status = 204;
  } else if (!strcmp(path, "/") || !strcmp(path, "/text")) {
    reply->body = app->text;
    reply->length = sizeof(app->text);
  } else if (!strcmp(path, "/image")) {
    reply->type = "image/webp";
    if (conditional)
      reply->status = 304;
    else {
      reply->body = app->image;
      reply->length = app->image_length;
    }
  } else {
    reply->status = !strcmp(path, "/denied") ? 403 : 404;
    reply->body =
        (const unsigned char *)(reply->status == 403 ? "forbidden" : "missing");
    reply->length = strlen((const char *)reply->body);
  }
  if (app->log &&
      fprintf(app->log, "%llu,%s,%u,%zu\n", (unsigned long long)app->requests,
              path, reply->status, reply->length) < 0)
    app->stop = 1;
}
