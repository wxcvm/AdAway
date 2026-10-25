// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef SPIKE_PLATFORM_H
#define SPIKE_PLATFORM_H
#include <stdio.h>
static inline FILE *spike_open(const char *path, const char *mode) {
#ifdef _WIN32
  FILE *file = NULL;
  if (fopen_s(&file, path, mode))
    return NULL;
  return file;
#else
  return fopen(path, mode);
#endif
}
#endif
