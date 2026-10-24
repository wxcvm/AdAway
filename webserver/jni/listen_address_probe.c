/* Disposable emulator fixture: invokes the same no-side-effect CLI used by webserver main(). */
#include "listener_pair.h"
#include "lifecycle_lock.h"
int main(int argc,char **argv) {
    int lifecycle=lifecycle_lock_cli(argc,argv);
    if (lifecycle>=0) return lifecycle;
    int result=lp_check_cli(argc,argv,80,443,8686);
    return result<0 ? 2 : result;
}
