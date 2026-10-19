#ifndef ADAWAY_LISTEN_ADDRESS_H
#define ADAWAY_LISTEN_ADDRESS_H
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#define LA_CLOSE closesocket
typedef SOCKET la_socket;
#define LA_BAD INVALID_SOCKET
#else
#include <arpa/inet.h>
#include <sys/socket.h>
#include <unistd.h>
#include <errno.h>
#define LA_CLOSE close
typedef int la_socket;
#define LA_BAD (-1)
#endif
/* Raw Mongoose peer bytes; mapped IPv4 must obey the same loopback boundary. */
static inline int la_loopback_peer(const uint8_t *ip, int ipv6) {
    if (!ipv6) return ip[0]==127;
    static const uint8_t loop[16]={0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1};
    static const uint8_t mapped[12]={0,0,0,0,0,0,0,0,0,0,255,255};
    return !memcmp(ip,loop,16) || (!memcmp(ip,mapped,12) && ip[12]==127);
}
/* Literal-only input: safe in URLs, INI and the Android launch argument. */
static inline int la_normalize(const char *input, char out[64]) {
    if (!input || !*input || strlen(input)>45) return 0;
    uint8_t raw[16]; int family=strchr(input,':') ? AF_INET6 : AF_INET;
    if (inet_pton(family,input,raw)!=1 || !inet_ntop(family,raw,out,64)) return 0;
    return family==AF_INET ? 4 : 6;
}
/* 0 free, 1 in use, 2 nonlocal address, 3 permission, 4 unavailable, 5 invalid. */
static inline int la_probe(const char *ip, int port, int *native_error) {
    char normalized[64]; int version=la_normalize(ip,normalized);
    *native_error=0;
    if (!version || port<1 || port>65535) return 5;
    int family=version==4 ? AF_INET : AF_INET6;
    la_socket value=socket(family,SOCK_STREAM,IPPROTO_TCP);
    if (value==LA_BAD) return 4;
    int enabled=1;
#ifdef _WIN32
    setsockopt(value,SOL_SOCKET,SO_EXCLUSIVEADDRUSE,(const char *)&enabled,sizeof(enabled));
#else
    setsockopt(value,SOL_SOCKET,SO_REUSEADDR,&enabled,sizeof(enabled));
#endif
    if (version==6) setsockopt(value,IPPROTO_IPV6,IPV6_V6ONLY,(const char *)&enabled,sizeof(enabled));
    union { struct sockaddr_in v4; struct sockaddr_in6 v6; } address;
    memset(&address,0,sizeof(address)); int length;
    if (version==4) {
        address.v4.sin_family=AF_INET; address.v4.sin_port=htons((uint16_t)port);
        inet_pton(AF_INET,normalized,&address.v4.sin_addr); length=sizeof(address.v4);
    } else {
        address.v6.sin6_family=AF_INET6; address.v6.sin6_port=htons((uint16_t)port);
        inet_pton(AF_INET6,normalized,&address.v6.sin6_addr); length=sizeof(address.v6);
    }
    int rc=bind(value,(struct sockaddr *)&address,length), result=0;
    if (rc) {
#ifdef _WIN32
        int error=WSAGetLastError(); *native_error=error;
        result=error==WSAEADDRINUSE ? 1 : error==WSAEADDRNOTAVAIL ? 2 : error==WSAEACCES ? 3 : 4;
#else
        int error=errno; *native_error=error;
        result=error==EADDRINUSE ? 1 : error==EADDRNOTAVAIL ? 2 : error==EACCES ? 3 : 4;
#endif
    }
    LA_CLOSE(value); return result;
}
static inline int la_port(const char *s) {
    if (!s || !*s) return 0;
    unsigned n=0;
    for (;*s;s++) { if (*s<'0'||*s>'9') return 0; n=n*10u+(unsigned)(*s-'0'); if (n>65535) return 0; }
    return (int)n;
}
/* -1 means ordinary start. A check never initializes resources/CA/listeners. */
static inline int la_check_cli(int argc,char **argv,int http,int https,int stats) {
    int requested=0, all=0; const char *ip=NULL;
    for (int i=1;i<argc;i++) if (!strcmp(argv[i],"--check-listen")) requested=1;
    if (!requested) return -1;
    for (int i=1;i<argc;i++) {
        if (!strcmp(argv[i],"--check-listen")) continue;
        if (i+1>=argc) return 2;
        const char *key=argv[i], *value=argv[++i];
        if (!strcmp(key,"--bind-ip")) ip=value;
        else if (!strcmp(key,"--bind")) {
            if (strcmp(value,"all") && strcmp(value,"loop") && strcmp(value,"loopback")) return 2;
            all=!strcmp(value,"all");
        } else if (!strcmp(key,"--http-port")) http=la_port(value);
        else if (!strcmp(key,"--https-port")) https=la_port(value);
        else if (!strcmp(key,"--stats-port")) stats=la_port(value);
        else return 2;
    }
    char normalized[64];
    if ((ip && !la_normalize(ip,normalized)) || http<1 || https<1 || stats<1
            || http==https || http==stats || https==stats) return 2;
#ifdef _WIN32
    WSADATA data; if (WSAStartup(MAKEWORD(2,2),&data)) return 3;
#endif
    int he=0,se=0;
    const char *target=ip ? normalized : all ? "0.0.0.0" : "127.0.0.1";
    int h=la_probe(target,http,&he), s=la_probe(target,https,&se);
    int h6=-1,s6=-1,ignored=0;
    if (!ip) {
        h6=la_probe(all ? "::" : "::1",http,&ignored);
        s6=la_probe(all ? "::" : "::1",https,&ignored);
    }
    printf("{\"http\":%d,\"https\":%d,\"http_error\":%d,\"https_error\":%d,\"http6\":%d,\"https6\":%d}\n",h,s,he,se,h6,s6);
#ifdef _WIN32
    WSACleanup();
#endif
    return h||s ? 3 : 0;
}
static inline void la_urls(const char *custom,int all,int http,int https,
    char h[128],char s[128],char h6[128],char s6[128]) {
    char normalized[64]; int version=custom && *custom ? la_normalize(custom,normalized) : 0;
    const char *v4=all ? "0.0.0.0" : "127.0.0.1", *v6=all ? "::" : "::1";
    if (version) {
        snprintf(h,128,version==6 ? "http://[%s]:%d" : "http://%s:%d",normalized,http);
        snprintf(s,128,version==6 ? "https://[%s]:%d" : "https://%s:%d",normalized,https);
        h6[0]=s6[0]=0;
    } else {
        snprintf(h,128,"http://%s:%d",v4,http); snprintf(s,128,"https://%s:%d",v4,https);
        snprintf(h6,128,"http://[%s]:%d",v6,http); snprintf(s6,128,"https://[%s]:%d",v6,https);
    }
}
#endif
