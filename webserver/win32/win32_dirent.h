#ifndef ADBLOCK_WIN32_DIRENT_H
#define ADBLOCK_WIN32_DIRENT_H
/*
 * Minimal POSIX <dirent.h> emulation for Windows (mingw-w64), used by the
 * standalone Win11 build of the ADBlock web server. Only the functions the
 * server uses are provided: opendir/readdir/closedir/rewinddir.
 */
#ifdef _WIN32
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define DT_DIR 4
#define DT_REG 8

struct dirent {
    char d_name[512];        /* generous; POSIX callers just read d_name */
    unsigned char d_type;
    long d_ino;              /* not meaningful on Windows */
};

typedef struct DIR {
    HANDLE hFind;
    WIN32_FIND_DATAA ffd;
    struct dirent de;
    int first;
    char pattern[MAX_PATH + 8];
} DIR;

static inline DIR *opendir(const char *name) {
    DIR *d = (DIR *) calloc(1, sizeof(DIR));
    if (!d) return NULL;
    snprintf(d->pattern, sizeof(d->pattern), "%s\\*", name);
    d->hFind = FindFirstFileA(d->pattern, &d->ffd);
    if (d->hFind == INVALID_HANDLE_VALUE) {
        free(d);
        return NULL;
    }
    d->first = 1;
    return d;
}

static inline struct dirent *readdir(DIR *d) {
    if (!d || d->hFind == INVALID_HANDLE_VALUE) return NULL;
    if (d->first) {
        d->first = 0;
    } else if (!FindNextFileA(d->hFind, &d->ffd)) {
        return NULL;
    }
    strncpy(d->de.d_name, d->ffd.cFileName, sizeof(d->de.d_name) - 1);
    d->de.d_name[sizeof(d->de.d_name) - 1] = '\0';
    d->de.d_type = (d->ffd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) ? DT_DIR : DT_REG;
    d->de.d_ino = 0;
    return &d->de;
}

static inline void rewinddir(DIR *d) {
    if (!d || d->hFind == INVALID_HANDLE_VALUE) return;
    FindClose(d->hFind);
    d->hFind = FindFirstFileA(d->pattern, &d->ffd);
    d->first = 1;
}

static inline int closedir(DIR *d) {
    if (!d) return -1;
    if (d->hFind != INVALID_HANDLE_VALUE) FindClose(d->hFind);
    free(d);
    return 0;
}
#endif /* _WIN32 */
#endif /* ADBLOCK_WIN32_DIRENT_H */
