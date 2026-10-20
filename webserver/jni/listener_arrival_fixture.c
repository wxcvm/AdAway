#include "listener_arrival_probe.h"
#include "listen_address.h"
#include <signal.h>
static volatile sig_atomic_t stopped;
static void stop(int number) { (void)number; stopped=1; }
static void respond(struct mg_connection *connection,int event,void *data) {
    if (event==MG_EV_HTTP_MSG && !ap_reply(connection,(struct mg_http_message *)data))
        mg_http_reply(connection,404,"", "not found");
}
int main(int argc,char **argv) {
    char normalized[64],url[128];
    if (argc!=3 || !la_normalize(argv[1],normalized) || !la_port(argv[2])) return 2;
    snprintf(url,sizeof(url),strchr(normalized,':') ? "http://[%s]:%d" : "http://%s:%d",normalized,la_port(argv[2]));
    struct mg_mgr manager; mg_mgr_init(&manager);
    if (!mg_http_listen(&manager,url,respond,NULL)) { mg_mgr_free(&manager); return 3; }
    signal(SIGTERM,stop); signal(SIGINT,stop);
    while (!stopped) mg_mgr_poll(&manager,20);
    mg_mgr_free(&manager); return 0;
}
