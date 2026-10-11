/*
 * 证书固定（pin）窗口的纯逻辑（G1）。
 *
 * 抽出来的目的：这段判定是"固定证书的应用必须被拒绝、且不能一直被反复探测"的核心，
 * 直接决定放行/拒绝，因此值得有宿主机测试；抽成 static inline 头文件后，
 * 服务端与宿主机测试共用同一份实现（与 rule_norm.h 同一手法，无需改构建脚本）。
 *
 * 语义必须与抽取前**逐字等价**：
 *   - 窗口：now - win_start > window_ms 才重置（严格大于）；
 *   - 计数：先判断是否重置，再自增；
 *   - 判据：计数 >= threshold 才算"认定固定证书"（原代码是 < threshold 就返回）；
 *   - bypass：uid 相同且 until_ms > now 才算命中（严格大于）。
 */
#ifndef ADBLOCK_PIN_WINDOW_H
#define ADBLOCK_PIN_WINDOW_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/* 单个 uid 的 bypass 记录：在 until_ms 之前不再拦截它的 CONNECT。 */
struct pin_bypass_entry {
    unsigned int uid;
    uint64_t until_ms;
};

/* bypass 表里是否有该 uid 的、仍然有效的记录。 */
static inline bool pin_bypass_hit(const struct pin_bypass_entry *entries, int count,
                                  unsigned int uid, uint64_t now) {
    if (uid == (unsigned int) -1) return false;
    for (int i = 0; i < count; i++) {
        if (entries[i].uid == uid && entries[i].until_ms > now) return true;
    }
    return false;
}

/* 滑动窗口推进一格，返回推进后的计数（并可能重置窗口）。 */
static inline unsigned int pin_fail_window_step(uint64_t *win_start, unsigned int count,
                                                uint64_t now, uint64_t window_ms) {
    if (now - *win_start > window_ms) {
        *win_start = now;
        count = 0;
    }
    return count + 1;
}

/* 计数是否已达到"认定该应用在固定证书"的阈值。 */
static inline bool pin_fail_window_reached(unsigned int count, unsigned int threshold) {
    return count >= threshold;
}

/* bypass 记录的到期时间。 */
static inline uint64_t pin_bypass_until(uint64_t now, uint64_t bypass_ms) {
    return now + bypass_ms;
}

#endif /* ADBLOCK_PIN_WINDOW_H */
