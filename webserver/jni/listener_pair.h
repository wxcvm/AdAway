#ifndef ADAWAY_LISTENER_PAIR_H
#define ADAWAY_LISTENER_PAIR_H
#include "listen_address.h"

typedef struct { char ipv4[64], ipv6[64]; int explicit_pair; } lp_pair;

static inline int lp_set(lp_pair *pair, const char *key, const char *value) {
    char normalized[64];
    int family = !strcmp(key,"--listen-ipv4") ? 4 : 6;
    char *target = family == 4 ? pair->ipv4 : pair->ipv6;
    pair->explicit_pair = 1;
    if (!strcmp(value,"-")) { *target=0; return 1; }
    if (la_normalize(value,normalized) != family) return 0;
    snprintf(target,64,"%s",normalized);
    return 1;
}

static inline int lp_check_pair(lp_pair *pair,int http,int https,int stats) {
    if ((!*pair->ipv4 && !*pair->ipv6) || !http || !https || !stats ||
            http==https || http==stats || https==stats) return 2;
    int ignored=0;
    int h4=*pair->ipv4 ? la_probe(pair->ipv4,http,&ignored) : -1;
    int s4=*pair->ipv4 ? la_probe(pair->ipv4,https,&ignored) : -1;
    int h6=*pair->ipv6 ? la_probe(pair->ipv6,http,&ignored) : -1;
    int s6=*pair->ipv6 ? la_probe(pair->ipv6,https,&ignored) : -1;
    printf("{\"http\":%d,\"https\":%d,\"http4\":%d,\"https4\":%d,\"http6\":%d,\"https6\":%d,\"explicit\":1}\n",
        *pair->ipv4 ? h4 : h6,*pair->ipv4 ? s4 : s6,h4,s4,h6,s6);
    return h4>0 || s4>0 || h6>0 || s6>0 ? 3 : 0;
}

/* Legacy callers retain their optional IPv6 behavior. Explicit families must all succeed. */
static inline int lp_check_cli(int argc,char **argv,int http,int https,int stats) {
    int check=0,pair_requested=0;
    for (int i=1;i<argc;i++) {
        if (!strcmp(argv[i],"--check-listen")) check=1;
        if (!strcmp(argv[i],"--listen-ipv4") || !strcmp(argv[i],"--listen-ipv6")) pair_requested=1;
    }
    if (!check) return -1;
    if (!pair_requested) return la_check_cli(argc,argv,http,https,stats);
    lp_pair pair={0};
    for (int i=1;i<argc;i++) {
        if (!strcmp(argv[i],"--check-listen")) continue;
        if (i+1>=argc) return 2;
        const char *key=argv[i], *value=argv[++i];
        if (!strcmp(key,"--listen-ipv4") || !strcmp(key,"--listen-ipv6")) {
            if (!lp_set(&pair,key,value)) return 2;
        } else if (!strcmp(key,"--http-port")) http=la_port(value);
        else if (!strcmp(key,"--https-port")) https=la_port(value);
        else if (!strcmp(key,"--stats-port")) stats=la_port(value);
        else if (strcmp(key,"--bind") || strcmp(value,"loop")) return 2;
    }
    return lp_check_pair(&pair,http,https,stats);
}

static inline void lp_urls(const lp_pair *pair,const char *legacy,int all,int http,int https,
        char h[128],char s[128],char h6[128],char s6[128]) {
    if (!pair->explicit_pair) { la_urls(legacy,all,http,https,h,s,h6,s6); return; }
    const char *primary=*pair->ipv4 ? pair->ipv4 : pair->ipv6;
    la_urls(primary,0,http,https,h,s,h6,s6);
    if (*pair->ipv4 && *pair->ipv6) {
        char unused_h[128],unused_s[128];
        la_urls(pair->ipv6,0,http,https,h6,s6,unused_h,unused_s);
    }
}
#endif
