/* Disposable emulator fixture: invokes the same no-side-effect CLI used by webserver main(). */
#include "listen_address.h"
int main(int argc,char **argv) {
    int result=la_check_cli(argc,argv,80,443,8686);
    return result<0 ? 2 : result;
}
