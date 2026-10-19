/* Disposable emulator fixture: invokes the same no-side-effect CLI used by webserver main(). */
#include "listener_pair.h"
int main(int argc,char **argv) {
    int result=lp_check_cli(argc,argv,80,443,8686);
    return result<0 ? 2 : result;
}
