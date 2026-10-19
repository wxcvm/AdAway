#include "listen_address.h"
#include <assert.h>
int main(void) {
#ifdef _WIN32
    WSADATA data; assert(!WSAStartup(MAKEWORD(2,2),&data));
#endif
    char out[64], h[128],s[128],h6[128],s6[128];
    assert(la_normalize("127.0.0.1",out)==4 && !strcmp(out,"127.0.0.1"));
    assert(la_normalize("2001:DB8::1",out)==6 && !strcmp(out,"2001:db8::1"));
    const char *bad[]={"","localhost","127.1","127.0.0.1;id","[::1]","fe80::1%1","0.0.0.0/0"," 127.0.0.1","999.1.2.3"};
    for (size_t i=0;i<sizeof(bad)/sizeof(bad[0]);i++) assert(!la_normalize(bad[i],out));
    la_urls("::1",0,18080,18443,h,s,h6,s6);
    assert(!strcmp(h,"http://[::1]:18080") && !strcmp(s,"https://[::1]:18443") && !*h6 && !*s6);
    la_urls("127.0.0.2",1,18080,18443,h,s,h6,s6);
    assert(!strcmp(h,"http://127.0.0.2:18080") && !*h6);
    la_urls("",0,80,443,h,s,h6,s6); assert(!strcmp(h6,"http://[::1]:80"));
    assert(la_port("65535")==65535 && !la_port("65536") && !la_port("80x"));
    uint8_t peer[16];
    inet_pton(AF_INET,"127.0.0.2",peer); assert(la_loopback_peer(peer,0));
    inet_pton(AF_INET,"192.0.2.1",peer); assert(!la_loopback_peer(peer,0));
    inet_pton(AF_INET6,"::ffff:127.0.0.1",peer); assert(la_loopback_peer(peer,1));
    inet_pton(AF_INET6,"::1",peer); assert(la_loopback_peer(peer,1));
    inet_pton(AF_INET6,"2001:db8::1",peer); assert(!la_loopback_peer(peer,1));
    for (int version=4;version<=6;version+=2) {
        la_socket listener=socket(version==4 ? AF_INET : AF_INET6,SOCK_STREAM,IPPROTO_TCP); assert(listener!=LA_BAD);
        union { struct sockaddr_in v4; struct sockaddr_in6 v6; } address; memset(&address,0,sizeof(address));
        int length;
        if (version==4) { address.v4.sin_family=AF_INET; inet_pton(AF_INET,"127.0.0.1",&address.v4.sin_addr); length=sizeof(address.v4); }
        else { address.v6.sin6_family=AF_INET6; inet_pton(AF_INET6,"::1",&address.v6.sin6_addr); length=sizeof(address.v6); }
        assert(!bind(listener,(struct sockaddr *)&address,length) && !listen(listener,1));
#ifdef _WIN32
        int size=length;
#else
        socklen_t size=(socklen_t)length;
#endif
        assert(!getsockname(listener,(struct sockaddr *)&address,&size));
        int port=ntohs(version==4 ? address.v4.sin_port : address.v6.sin6_port), error;
        assert(la_probe(version==4 ? "127.0.0.1" : "::1",port,&error)==1 && error);
        LA_CLOSE(listener);
        assert(la_probe(version==4 ? "127.0.0.1" : "::1",port,&error)==0);
    }
#ifdef _WIN32
    WSACleanup();
#endif
    puts("Literal address validation, exact URL scope and IPv4/IPv6 real port conflicts passed"); return 0;
}
