#define _POSIX_C_SOURCE 200809L
#include "lifecycle_lock.h"
#include <assert.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <sys/wait.h>
#include <unistd.h>

static int call_lock(void) {
    char *args[] = {"guard", "--lifecycle-lock", "9"};
    return lifecycle_lock_cli(3, args);
}

static int competitor(const char *path) {
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        int descriptor = open(path, O_RDWR);
        if (descriptor < 0 || dup2(descriptor, 9) < 0) _exit(2);
        if (descriptor != 9) close(descriptor);
        _exit(call_lock());
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status));
    return WEXITSTATUS(status);
}

int main(void) {
    char path[] = "/tmp/adaway-lifecycle-XXXXXX"; /* NOSONAR: isolated test, mkstemp atomically creates a random 0600 file; no predictable open. */
    int descriptor = mkstemp(path);
    assert(descriptor >= 0 && dup2(descriptor, 9) == 9);
    if (descriptor != 9) close(descriptor);
    /* The one-shot child exits, but its parent's shared descriptor retains the lock. */
    pid_t helper = fork();
    assert(helper >= 0);
    if (!helper) _exit(call_lock());
    int status = 0;
    assert(waitpid(helper, &status, 0) == helper && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(competitor(path) == 75);

    /* A long-lived daemon closes its inherited descriptor before serving requests. */
    int ready[2];
    assert(pipe(ready) == 0);
    pid_t daemon = fork();
    assert(daemon >= 0);
    if (!daemon) {
        close(ready[0]);
        close(9);
        if (write(ready[1], "R", 1) != 1) _exit(2);
        close(ready[1]);
        sleep(30);
        _exit(0);
    }
    close(ready[1]);
    char receipt = 0;
    assert(read(ready[0], &receipt, 1) == 1 && receipt == 'R');
    close(ready[0]);
    close(9);
    assert(competitor(path) == 0);
    assert(kill(daemon, SIGTERM) == 0);
    assert(waitpid(daemon, &status, 0) == daemon);

    char *invalid[] = {"guard", "--lifecycle-lock", "1"};
    assert(lifecycle_lock_cli(3, invalid) == 2);
    invalid[2] = "9junk";
    assert(lifecycle_lock_cli(3, invalid) == 2);
    invalid[2] = "997";
    assert(lifecycle_lock_cli(3, invalid) == 2);
    assert(unlink(path) == 0);
    puts("lifecycle lock inherited descriptor, exclusion, owner exit and daemon close passed");
    return 0;
}
