#ifndef ADAWAY_DIAGNOSTIC_UDP_PACKET_H
#define ADAWAY_DIAGNOSTIC_UDP_PACKET_H
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#define DUDP_PORT 53536u
#define DUDP_PAYLOAD 256u
#define DUDP_MAX_PACKET 1500u
struct dudp_packet { unsigned family; size_t header, payload; uint16_t source_port; };
static uint16_t dudp_u16(const uint8_t *p) { return (uint16_t)((unsigned)p[0]*256u+p[1]); }
static void dudp_put(uint8_t *p,unsigned value) { p[0]=(uint8_t)(value>>8); p[1]=(uint8_t)value; }
static uint32_t dudp_sum(const uint8_t *p,size_t size) {
    uint32_t sum=0;
    for (size_t i=0;i<size;i+=2) sum+=(uint32_t)p[i]*256u+(i+1<size?p[i+1]:0u);
    return sum;
}
static uint16_t dudp_check(uint32_t sum) {
    while (sum>>16) sum=(sum&65535u)+(sum>>16);
    return (uint16_t)~sum;
}
static int dudp_v6_address(const uint8_t *p,unsigned last) {
    if (dudp_u16(p)!=0x2001 || dudp_u16(p+2)!=2 || p[15]!=last) return 0;
    for (size_t i=4;i<15;i++) if (p[i]) return 0;
    return 1;
}
static size_t dudp_header4(const uint8_t *p,size_t n) {
    size_t h=(size_t)(p[0]&15u)*4u;
    if (h<20 || h>60 || n<h+8 || dudp_u16(p+2)!=n || p[9]!=17
        || (dudp_u16(p+6)&0xbfffu) || dudp_check(dudp_sum(p,h))) return 0;
    const uint8_t addresses[8]={198,18,0,2,198,18,0,1};
    return memcmp(p+12,addresses,8)?0:h;
}
static size_t dudp_header6(const uint8_t *p,size_t n) {
    if (n<48 || dudp_u16(p+4)!=n-40 || p[6]!=17 || !p[7]
        || !dudp_v6_address(p+8,2) || !dudp_v6_address(p+24,1)) return 0;
    return 40;
}
static int dudp_parse(const uint8_t *p,size_t n,struct dudp_packet *out) {
    if (!p || !out || n<28 || n>DUDP_MAX_PACKET) return 0;
    unsigned family=p[0]>>4;
    size_t h=0;
    if (family==4) h=dudp_header4(p,n);
    if (family==6) h=dudp_header6(p,n);
    if (!h) return 0;
    size_t udp=n-h;
    if (dudp_u16(p+h+4)!=udp || !dudp_u16(p+h) || dudp_u16(p+h+2)!=DUDP_PORT
        || udp<=8 || udp>8+DUDP_PAYLOAD) return 0;
    uint16_t check=dudp_u16(p+h+6);
    if (family==6 && !check) return 0;
    uint32_t pseudo=dudp_sum(p+(family==4?12:8),family==4?8:32)+(uint32_t)udp+17u;
    if (check && dudp_check(pseudo+dudp_sum(p+h,udp))) return 0;
    out->family=family; out->header=h; out->payload=udp-8; out->source_port=dudp_u16(p+h);
    return 1;
}
static size_t dudp_reply(const uint8_t *p,size_t n,const uint8_t *payload,size_t bytes,uint8_t *out,size_t cap) {
    struct dudp_packet parsed;
    if (!payload || !out || !bytes || bytes>DUDP_PAYLOAD || !dudp_parse(p,n,&parsed)) return 0;
    size_t h=parsed.header,total=h+8+bytes;
    if (cap<total) return 0;
    memcpy(out,p,h+8); memcpy(out+h+8,payload,bytes);
    size_t at=parsed.family==4?12u:8u,width=parsed.family==4?4u:16u;
    memcpy(out+at,p+at+width,width); memcpy(out+at+width,p+at,width);
    dudp_put(out+h,DUDP_PORT); dudp_put(out+h+2,parsed.source_port); dudp_put(out+h+4,(unsigned)(8+bytes));
    dudp_put(out+h+6,0);
    if (parsed.family==4) {
        out[8]=64; dudp_put(out+2,(unsigned)total); dudp_put(out+10,0); dudp_put(out+10,dudp_check(dudp_sum(out,h)));
    } else { out[7]=64; dudp_put(out+4,(unsigned)(8+bytes)); }
    uint32_t pseudo=dudp_sum(out+at,2*width)+(uint32_t)(8+bytes)+17u;
    uint16_t check=dudp_check(pseudo+dudp_sum(out+h,8+bytes));
    dudp_put(out+h+6,check?check:65535u); return total;
}
static size_t dudp_request(unsigned family,uint16_t source,const uint8_t *payload,size_t bytes,uint8_t *out,size_t cap) {
    size_t h=family==4?20u:40u,total=h+8+bytes;
    if ((family!=4 && family!=6) || !out || !source || !payload || !bytes || bytes>DUDP_PAYLOAD || cap<total) return 0;
    memset(out,0,h+8); memcpy(out+h+8,payload,bytes);
    if (family==4) {
        const uint8_t ips[8]={198,18,0,2,198,18,0,1};
        out[0]=0x45; out[8]=64; out[9]=17; dudp_put(out+2,(unsigned)total); memcpy(out+12,ips,8);
        dudp_put(out+10,dudp_check(dudp_sum(out,h)));
    } else {
        out[0]=0x60; out[6]=17; out[7]=64; dudp_put(out+4,(unsigned)(8+bytes));
        dudp_put(out+8,0x2001); dudp_put(out+10,2); out[23]=2;
        dudp_put(out+24,0x2001); dudp_put(out+26,2); out[39]=1;
    }
    dudp_put(out+h,source); dudp_put(out+h+2,DUDP_PORT); dudp_put(out+h+4,(unsigned)(8+bytes));
    uint32_t pseudo=dudp_sum(out+(family==4?12:8),family==4?8:32)+(uint32_t)(8+bytes)+17u;
    uint16_t check=dudp_check(pseudo+dudp_sum(out+h,8+bytes)); dudp_put(out+h+6,check?check:65535u);
    return total;
}
#endif
