#include "listener_pair.h"
#include <assert.h>
int main(void) {
#ifdef _WIN32
    WSADATA data; assert(!WSAStartup(MAKEWORD(2,2),&data));
#endif
    lp_pair pair={0};
    assert(lp_set(&pair,"--listen-ipv4","127.0.0.2"));
    assert(lp_set(&pair,"--listen-ipv6","::1"));
    assert(!lp_set(&pair,"--listen-ipv4","::1"));
    assert(!lp_set(&pair,"--listen-ipv6","127.0.0.1"));
    char h[128],s[128],h6[128],s6[128];
    lp_urls(&pair,"",0,18080,18443,h,s,h6,s6);
    assert(!strcmp(h,"http://127.0.0.2:18080") && !strcmp(h6,"http://[::1]:18080"));
    assert(lp_set(&pair,"--listen-ipv4","-"));
    lp_urls(&pair,"",0,18080,18443,h,s,h6,s6);
    assert(!strcmp(h,"http://[::1]:18080") && !*h6);
    char *bad[]={"test","--check-listen","--listen-ipv4","-","--listen-ipv6","-"};
    assert(lp_check_cli(6,bad,80,443,8686)==2);
    la_socket occupied=socket(AF_INET6,SOCK_STREAM,IPPROTO_TCP); assert(occupied!=LA_BAD);
    struct sockaddr_in6 address; memset(&address,0,sizeof(address)); address.sin6_family=AF_INET6;
    inet_pton(AF_INET6,"::1",&address.sin6_addr);
    assert(!bind(occupied,(struct sockaddr *)&address,sizeof(address)) && !listen(occupied,1));
#ifdef _WIN32
    int size=sizeof(address);
#else
    socklen_t size=sizeof(address);
#endif
    assert(!getsockname(occupied,(struct sockaddr *)&address,&size));
    assert(lp_check_pair(&pair,ntohs(address.sin6_port),65535,8686)==3);
    LA_CLOSE(occupied);
#ifdef _WIN32
    WSACleanup();
#endif
    puts("Independent families, disabled family, dual URLs and IPv6 conflict rejection passed");
    return 0;
}
