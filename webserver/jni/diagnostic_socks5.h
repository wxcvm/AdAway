#ifndef ADAWAY_DIAGNOSTIC_SOCKS5_H
#define ADAWAY_DIAGNOSTIC_SOCKS5_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#define DS_LIMIT 256u
#define DS_PORT 53539u
static inline size_t ds_request(unsigned family,uint8_t out[22]) {
    size_t n=family==6?22u:10u;
    memset(out,0,22);out[0]=5;out[1]=1;out[3]=(uint8_t)(family==6?4:1);
    if(family==6){out[4]=0x20;out[5]=1;out[7]=2;out[19]=1;}
    else{out[4]=198;out[5]=18;out[7]=1;}
    out[n-2]=(uint8_t)(DS_PORT>>8);out[n-1]=(uint8_t)DS_PORT;return n;
}
static inline unsigned ds_greeting(const uint8_t *p,size_t n) {
    return p && n==3 && p[0]==5 && p[1]==1 && p[2]==2?0u:255u;
}
static inline void ds_auth_frame(const uint8_t token[16],uint8_t out[25]) {
    out[0]=1;out[1]=6;memcpy(out+2,"adaway",6);out[8]=16;memcpy(out+9,token,16);
}
static inline unsigned ds_auth(const uint8_t *p,size_t n,const uint8_t token[16]) {
    if(!p || !token || n!=25)return 1;
    uint8_t expected[25];ds_auth_frame(token,expected);unsigned diff=0;
    for(size_t i=0;i<25;i++)diff|=(unsigned)(p[i]^expected[i]);
    return diff?1u:0u;
}
static inline unsigned ds_connect(const uint8_t *p,size_t n,unsigned family) {
    if(!p || n<4 || p[0]!=5 || p[2]!=0)return 1;
    if(p[1]!=1)return 7;
    if(p[3]!=1 && p[3]!=4)return 8;
    uint8_t expected[22];size_t wanted=ds_request(family,expected);
    return n==wanted && memcmp(p,expected,n)==0?0u:2u;
}
#endif
