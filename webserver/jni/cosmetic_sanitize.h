/*
 * 注入页面的 cosmetic 规则净化（D 项）。
 *
 * 背景（实测得出的两个缺陷）：
 *   1. Java 侧 CosmeticRules.toCss() 写出的是**纯 CSS 规则**（selector{display:none !important;}），
 *      而 webserver.c 原来把这段追加在 kHideCss 的 "</style>" **之后** —— 于是这些规则从来没被
 *      浏览器当作 CSS 应用过（等于功能不可见）。修法是改成注入到 <style> 内部（见 kHideCssHead/Tail）。
 *   2. 规则一旦落进 <style> 内部，含 '<' 的内容可以提前闭合样式元素（HTML 逃逸，可注入标记），
 *      所以含 '<' 的整条丢弃。**只拒绝 '<'**：'{'/'}' 是每一条真实规则都需要的字符
 *      （selector{display:none}），而 '}' 在 <style> 内部只能提前结束一条规则，无法离开样式元素，
 *      因此无害 —— 第一版把 '{'/'}' 也拒了，等于把所有规则都丢掉，属于设计错误，已改正。
 *
 * 行为细节（明确写出，避免变成"偶然行为"）：
 *   - 空行与**纯空白行**会被规范成一个空行（对 CSS 无影响，CI 实测如此）；
 *   - 只按 '\n' 切分，保留行序；不做任何动态分配。
 *
 * 净化粒度是"一条规则"（以换行分隔的块）：含 '<' 的整条丢弃并计数，不静默。
 */
#ifndef ADBLOCK_COSMETIC_SANITIZE_H
#define ADBLOCK_COSMETIC_SANITIZE_H

#include <stddef.h>
#include <stdbool.h>

struct cosmetic_stats {
    size_t kept;      /* 保留的规则条数 */
    size_t dropped;   /* 因危险字符被丢弃的条数 */
    size_t bytes_in;  /* 输入字节数 */
};

/* 该条规则是否可安全注入 <style> 内部。 */
static inline bool cosmetic_rule_is_safe(const char *rule, size_t len) {
    for (size_t i = 0; i < len; i++) {
        if (rule[i] == '<') return false;   /* 唯一能逃出 <style> 元素的字符 */
    }
    return true;
}

/*
 * 就地净化：把不安全/空的规则行从 buffer 中剔除，写回同一块内存。
 * 返回净化后的长度；stats（可传 NULL）填入计数。
 * 只按 '\n' 切分，保留原有换行结构；不引入任何动态分配。
 */
static inline size_t cosmetic_sanitize(char *buffer, size_t len, struct cosmetic_stats *stats) {
    size_t in = 0, out = 0;
    size_t kept = 0, dropped = 0;
    if (stats != NULL) { stats->bytes_in = len; stats->kept = 0; stats->dropped = 0; }
    while (in < len) {
        size_t line_start = in;
        while (in < len && buffer[in] != '\n') in++;
        size_t line_len = in - line_start;
        bool blank = true;
        for (size_t k = 0; k < line_len; k++) {
            char c = buffer[line_start + k];
            if (c != ' ' && c != '\t' && c != '\r') { blank = false; break; }
        }
        if (blank) {
            if (in < len) buffer[out++] = '\n';
            in++;
            continue;
        }
        if (cosmetic_rule_is_safe(buffer + line_start, line_len)) {
            for (size_t k = 0; k < line_len; k++) buffer[out++] = buffer[line_start + k];
            if (in < len) buffer[out++] = '\n';
            kept++;
        } else {
            dropped++;
        }
        in++;
    }
    buffer[out] = '\0';
    if (stats != NULL) { stats->kept = kept; stats->dropped = dropped; }
    return out;
}

#endif /* ADBLOCK_COSMETIC_SANITIZE_H */
