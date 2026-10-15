/*
 * 请求类型映射（G1 / D 项）：把 URL 路径的扩展名映射到「这是什么资源」。
 *
 * 为什么单独抽出来：
 *   - 原实现把扩展名清单内联在 webserver.c 的判定链里（uri_ends_with_ci(u, ".jpg") || ...），
 *     没有测试；清单很长（媒体 16 个后缀），改一个字符就可能让某类资源走错策略。
 *   - 抽出后是纯函数 + 宿主测试，且不依赖 mongoose：原函数收 struct mg_str，
 *     这里只收 (buf, len)，调用处用 u.buf/u.len 适配。
 *
 * 语义严格保持与抽出前一致（不顺手「改进」）：
 *   - 只做大小写不敏感的后缀比较，比较对象是完整的 URI（含查询串）；
 *   - 因此 "a.jpg?v=1" 不匹配 .jpg —— 这与抽出前的 uri_ends_with_ci 行为相同。
 *     这是一处已知的覆盖缺口（这类请求会落到别的分支，而不是走图片策略），
 *     修它会改变线上行为，故本项只记录不修改。
 */
#ifndef ADBLOCK_LT_MAP_H
#define ADBLOCK_LT_MAP_H

#include <stdbool.h>
#include <stddef.h>

static inline char lt_lower(int c) { return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : (char) c; }

/* s[0..n) 是否以 suffix 结尾（大小写不敏感）。suffix 以 NUL 结尾。 */
static inline bool lt_ends_with_ci(const char *s, size_t n, const char *suffix) {
    size_t sl = 0;
    while (suffix[sl] != '\0') sl++;
    if (sl == 0 || n < sl) return false;
    for (size_t i = 0; i < sl; i++) {
        if (lt_lower((unsigned char) s[n - sl + i]) != lt_lower((unsigned char) suffix[i])) return false;
    }
    return true;
}

/* 以下五组清单照抄自 webserver.c 抽取前的判定链，逐项一致。 */
static inline bool lt_is_image(const char *s, size_t n) {
    return lt_ends_with_ci(s, n, ".jpg") || lt_ends_with_ci(s, n, ".jpeg") ||
           lt_ends_with_ci(s, n, ".png") || lt_ends_with_ci(s, n, ".gif") ||
           lt_ends_with_ci(s, n, ".webp") || lt_ends_with_ci(s, n, ".avif") ||
           lt_ends_with_ci(s, n, ".svg") || lt_ends_with_ci(s, n, ".ico") ||
           lt_ends_with_ci(s, n, ".bmp");
}

static inline bool lt_is_script(const char *s, size_t n) {
    return lt_ends_with_ci(s, n, ".js") || lt_ends_with_ci(s, n, ".mjs");
}

static inline bool lt_is_style(const char *s, size_t n) {
    return lt_ends_with_ci(s, n, ".css");
}

static inline bool lt_is_font(const char *s, size_t n) {
    return lt_ends_with_ci(s, n, ".woff") || lt_ends_with_ci(s, n, ".woff2") ||
           lt_ends_with_ci(s, n, ".ttf") || lt_ends_with_ci(s, n, ".otf") ||
           lt_ends_with_ci(s, n, ".eot");
}

static inline bool lt_is_media(const char *s, size_t n) {
    return lt_ends_with_ci(s, n, ".mp4") || lt_ends_with_ci(s, n, ".webm") ||
           lt_ends_with_ci(s, n, ".m4a") || lt_ends_with_ci(s, n, ".m4v") ||
           lt_ends_with_ci(s, n, ".mp3") || lt_ends_with_ci(s, n, ".aac") ||
           lt_ends_with_ci(s, n, ".ogg") || lt_ends_with_ci(s, n, ".oga") ||
           lt_ends_with_ci(s, n, ".opus") || lt_ends_with_ci(s, n, ".flac") ||
           lt_ends_with_ci(s, n, ".ts") || lt_ends_with_ci(s, n, ".m3u8") ||
           lt_ends_with_ci(s, n, ".mpd") || lt_ends_with_ci(s, n, ".flv") ||
           lt_ends_with_ci(s, n, ".mov") || lt_ends_with_ci(s, n, ".wav");
}

#endif /* ADBLOCK_LT_MAP_H */
