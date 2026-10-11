/*
 * 规则归一化的纯函数单一口。
 *
 * 为什么单独成文件：这些函数不依赖 mongoose，也不依赖任何服务端状态 —— 把它们放在这里，
 * 服务端（#include 本文件）与宿主侧测试（webserver/jni/rule_norm_test.c）共用同一份实现，
 * 测试才真正测到线上代码，而不是复制品。函数用 static inline，因此不需要改任何构建脚本。
 *
 * 行为与从 webserver.c 抽出之前逐字一致。
 */
#ifndef ADBLOCK_RULE_NORM_H
#define ADBLOCK_RULE_NORM_H

#include <stdbool.h>
#include <stddef.h>
#include <string.h>

/*
 * 把一行规则归一化成主机名（小写）。
 *
 * 处理顺序：去掉前导 @@ / | / 空白 -> hosts 格式取空白之后的字段 -> 去掉 scheme ->
 * 去掉前导 * 与 . -> 一直取到 / ? # : ^ 空白 之前。匹配语义是"该主机本身或其子域"。
 */
static inline void domain_rule_normalize(const char *in, char *out, size_t outsz) {
    size_t o = 0;
    const char *p = in;
    if (outsz == 0) return;
    out[0] = '\0';
    while (*p == '@' || *p == '|' || *p == ' ' || *p == '\t') p++;
    {
        const char *sp = strpbrk(p, " \t");
        if (sp != NULL) {
            const char *q = sp;
            while (*q == ' ' || *q == '\t') q++;
            if (*q != '\0') p = q;
        }
    }
    {
        const char *scheme = strstr(p, "://");
        if (scheme != NULL) p = scheme + 3;
    }
    while (*p == '*' || *p == '.') p++;
    while (*p != '\0' && *p != '/' && *p != '?' && *p != '#' && *p != ':' &&
           *p != '^' && *p != ' ' && *p != '\t' && *p != '\r' && *p != '\n') {
        char ch = *p;
        if (ch >= 'A' && ch <= 'Z') ch = (char)(ch - 'A' + 'a');
        if (o + 1 < outsz) out[o++] = ch;
        p++;
    }
    /*
     * ABP 的尾锚点 |（|ads.example.com|）：主机名不可能以 | 结尾，所以它是装饰而不是内容。
     * 不剥掉的话会得到 "ads.example.com|" —— 这样的主机名永远匹配不上，规则静默失效。
     * 这个缺陷是宿主机测试（rule_norm_test.c）跑出来的第一个真问题。
     */
    while (o > 0 && out[o - 1] == '|') o--;
    out[o] = '\0';
}

/*
 * 带资源类型修饰符的规则（例如 ||cdn.example.com^$script）不实现。
 *
 * 归一化只取主机名，会让这类规则变成"整个域名都命中"，属于静默改变规则含义 ——
 * 既漏拦（本该只拦脚本）又误拦（同域的正常资源）。返回 true 时调用方应跳过该规则。
 */
static inline bool rule_has_unsupported_modifier(const char *in) {
    const char *dollar = strchr(in, '$');
    if (dollar == NULL) return false;
    const char *caret = strchr(in, '^');
    return caret == NULL || dollar > caret;
}

#endif /* ADBLOCK_RULE_NORM_H */
