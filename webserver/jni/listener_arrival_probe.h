#ifndef ADAWAY_LISTENER_ARRIVAL_PROBE_H
#define ADAWAY_LISTENER_ARRIVAL_PROBE_H
#include "mongoose/mongoose.h"
#include <string.h>

/* Public, stateless receipt. No domains, counters, configuration, forwarding or
 * authentication data. A fresh 128-bit nonce distinguishes a real sink reply
 * from a TCP handshake, generic block page, redirect or cached response. */
#define AP_PREFIX "/adaway-listener-probe/"
#define AP_BODY "adaway-listener:"
static inline int ap_nonce(struct mg_str uri,char out[33]) {
    size_t prefix=sizeof(AP_PREFIX)-1;
    if (uri.len<prefix || memcmp(uri.buf,AP_PREFIX,prefix)) return 0;
    if (uri.len!=prefix+32) return -1;
    for (size_t i=0;i<32;i++) {
        char ch=uri.buf[prefix+i];
        if (!((ch>='0' && ch<='9') || (ch>='a' && ch<='f'))) return -1;
        out[i]=ch;
    }
    out[32]=0; return 1;
}
static inline int ap_reply(struct mg_connection *connection,const struct mg_http_message *message) {
    char nonce[33]; int match=ap_nonce(message->uri,nonce);
    if (!match) return 0;
    if (match<0) mg_http_reply(connection,400,"Cache-Control: no-store\r\n","invalid probe");
    else if (mg_strcmp(message->method,mg_str("GET")))
        mg_http_reply(connection,405,"Allow: GET\r\nCache-Control: no-store\r\n","GET required");
    else {
        char headers[160];
        snprintf(headers,sizeof(headers),"Content-Type: text/plain\r\nCache-Control: no-store\r\nX-AdAway-Listener-Probe: %s\r\n",nonce);
        mg_http_reply(connection,200,headers,AP_BODY "%s",nonce);
    }
    return 1;
}
#endif
