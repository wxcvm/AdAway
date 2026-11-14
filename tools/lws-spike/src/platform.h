// SPDX-License-Identifier: GPL-3.0-or-later
#ifndef SPIKE_PLATFORM_H
#define SPIKE_PLATFORM_H
#include <stdio.h>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#include <share.h>
#include <string.h>
#include <sys/stat.h>
#endif
static inline FILE *spike_open(const char *path, const char *mode) {
#ifdef _WIN32
  /* MSVCRT does not implement C11 fopen's x mode (UCRT does). Keep the
   * exclusive-create guarantee on both MinGW runtimes, never fall back to wb.
   */
  if (!strcmp(mode, "wbx")) {
    int fd = -1;
    if (_sopen_s(&fd, path, _O_CREAT | _O_EXCL | _O_WRONLY | _O_BINARY,
                 _SH_DENYRW, _S_IREAD | _S_IWRITE))
      return NULL;
    FILE *created = _fdopen(fd, "wb");
    if (!created)
      _close(fd);
    return created;
  }
  FILE *file = NULL;
  if (fopen_s(&file, path, mode))
    return NULL;
  return file;
#else
  return fopen(path, mode);
#endif
}
#endif
