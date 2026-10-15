/*
 * cosmetic 规则的域名作用域匹配（D 项）。
 *
 * 背景：cosmetic.css 是全局文件，注入每一个被过滤的页面；而 AdGuard/ABP 的 ##selector
 * 规则经常是域名限定的（example.com##.ad）。若把域名限定的规则也全局注入，就会在所有
 * 站点上隐藏元素（用户可见的错误行为）。
 *
 * 约定（与 block_set_contains 的父域后缀语义一致）：
 *   - '*' 或空域名 → 全局规则（任何主机都命中）；
 *   - 'example.com' → 命中 example.com 本身，以及 a.example.com 这类子域；
 *   - 大小写不敏感；比较前去掉主机末尾的点（FQDN 写法）；
 *   - 不做前缀匹配（'notexample.com' 不命中 'example.com'）。
 *
 * 只做匹配判定，不做分配；解析留在 webserver.c。
 */
#ifndef ADBLOCK_COSMETIC_SCOPE_H
#define ADBLOCK_COSMETIC_SCOPE_H

#include <stdbool.h>
#include <stddef.h>
#include <string.h>

static inline int cs_lower(int c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }

/*
 * 无大小写比较，**两端都显式给长度**。
 *
 * 为什么不用 strlen(b)：主机末尾的点在调用处已经被剥掉，长度变了；如果这里再用
 * strlen 重新量一次，就会拿"带点的长度"去比"剥点后的长度"，必然不相等 ——
 * CI 实测就是这个错误（"example.com." 匹配 "example.com" 失败）。
 */
static inline bool cs_eq_nocase_n(const char *a, size_t alen, const char *b, size_t blen) {
    if (alen != blen) return false;
    for (size_t i = 0; i < alen; i++) {
        if (cs_lower((unsigned char) a[i]) != cs_lower((unsigned char) b[i])) return false;
    }
    return true;
}

/*
 * 规则域名 rule（长度 rlen）是否作用于主机 host（以 NUL 结尾）。
 */
static inline bool cosmetic_domain_match(const char *rule, size_t rlen, const char *host) {
    if (host == NULL || host[0] == '\0') return false;
    /* 去掉主机末尾的点 */
    size_t hlen = strlen(host);
    while (hlen > 0 && host[hlen - 1] == '.') hlen--;
    if (hlen == 0) return false;
    /* 空域名或 '*' → 全局 */
    if (rlen == 0) return true;
    if (rlen == 1 && rule[0] == '*') return true;
    if (cs_eq_nocase_n(rule, rlen, host, hlen)) return true;
    /* 子域：host = <sub>.rule（都用剥点后的 hlen） */
    if (hlen > rlen + 1 && host[hlen - rlen - 1] == '.') {
        if (cs_eq_nocase_n(rule, rlen, host + (hlen - rlen), rlen)) return true;
    }
    return false;
}

#endif /* ADBLOCK_COSMETIC_SCOPE_H */
