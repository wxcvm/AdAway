/* Real bundled Mongoose sockets, using the same IPv6-only build flag as Android. */
#include "listener_pair.h"
#include "mongoose/mongoose.h"
#include <assert.h>

int main(void) {
    struct mg_mgr manager;
    mg_mgr_init(&manager);
    struct mg_connection *http=mg_http_listen(&manager,"http://0.0.0.0:0",NULL,NULL); /* NOSONAR(c:S5332): disposable socket fixture, no requests or credentials. */
    struct mg_connection *https=mg_http_listen(&manager,"https://0.0.0.0:0",NULL,NULL);
    assert(http && https);
    int hp=ntohs(http->loc.port),sp=ntohs(https->loc.port);
    assert(hp && sp && hp!=sp);
    lp_pair pair={0};
    assert(lp_set(&pair,"--listen-ipv4","0.0.0.0"));
    assert(lp_set(&pair,"--listen-ipv6","::"));
    char h[128],s[128],h6[128],s6[128];
    lp_urls(&pair,"",0,hp,sp,h,s,h6,s6);
    assert(mg_http_listen(&manager,h6,NULL,NULL));
    assert(mg_http_listen(&manager,s6,NULL,NULL));
    int error=0;
    assert(la_probe("127.0.0.1",hp,&error)>0);
    assert(la_probe("::1",hp,&error)>0);
    mg_mgr_free(&manager);
    assert(la_probe("127.0.0.1",hp,&error)==0);
    assert(la_probe("::1",hp,&error)==0);
    puts("Bundled Mongoose: simultaneous wildcard IPv4 + IPv6 HTTP/TLS listeners and release passed");
    return 0;
}
