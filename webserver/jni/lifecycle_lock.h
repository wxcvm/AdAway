#ifndef ADAWAY_LIFECYCLE_LOCK_H
#define ADAWAY_LIFECYCLE_LOCK_H
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#ifndef _WIN32
#include <sys/file.h>
#endif

/* flock operates on the inherited open file description. The parent shell
 * retains its descriptor and the lock after this one-shot helper exits. */
static inline int lifecycle_lock_cli(int argc, char **argv) {
    if (argc < 2 || (strcmp(argv[1], "--lifecycle-lock") && strcmp(argv[1], "--lifecycle-unlock"))) return -1;
#ifndef _WIN32
    if (argc != 3) return 2;
    char *end = NULL;
    errno = 0;
    long fd = strtol(argv[2], &end, 10);
    if (errno || !end || *end || end == argv[2] || fd < 3 || fd > 1023) return 2;
    int operation = !strcmp(argv[1], "--lifecycle-lock") ? LOCK_EX | LOCK_NB : LOCK_UN;
    if (!flock((int) fd, operation)) return 0;
    return errno == EWOULDBLOCK || errno == EAGAIN ? 75 : 2;
#else
    return 2;
#endif
}
#endif
