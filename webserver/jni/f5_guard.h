/*
 * F5 降载"重试护栏"的纯逻辑（G1）：窗口 + 阈值 + 冷却。
 *
 * 语义与抽取前**逐字等价**（webserver.c 的 f5_guard_tripped 原实现）：
 *   1. 命中已有条目且冷却未过期（until_ms > now）→ 本请求快速失败（返回 true），不改动条目；
 *   2. 命中已有条目 → now - win_start > window_ms 时重置窗口（严格大于），再自增计数；
 *      计数 >= threshold 时设置 until_ms = now + cooldown_ms（本次仍返回 false，从下一次起快速失败）；
 *   3. 未命中 → 取第一个空位（win_start == 0），没有空位就覆盖 win_start 最小的（最久未更新）；
 *      新条目 count = 1、until_ms = 0。
 *
 * 抽出来的目的：这段决定"是否对一个正在死循环的客户端快速失败"，属于用户可见行为，
 * 因此边界必须有宿主机测试；数据由调用方持有，函数自身不记日志（日志留在调用点）。
 *
 * 注意：按域熔断（f5_break_*）结构相同但**键不同**（只看域名、不看 uid），本轮未迁移，
 * 仍在 webserver.c 里保持原样 —— 迁移前需要先有与这里等价的测试覆盖。
 */
#ifndef ADBLOCK_F5_GUARD_H
#define ADBLOCK_F5_GUARD_H

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

/* 与 webserver.c 中 s_f5_guards 的字段一一对应（顺序与类型必须一致）。 */
struct f5_guard_entry {
    unsigned int uid;
    uint32_t host_hash;
    uint64_t win_start;
    unsigned int count;
    uint64_t until_ms;
};

struct f5_guard_cfg {
    uint64_t window_ms;
    unsigned int threshold;
    uint64_t cooldown_ms;
};

/*
 * 推进一次判定。返回 true 表示"本请求应快速失败（冷却中）"。
 * just_tripped（可传 NULL）在"本次刚好触发冷却"时置 true，供调用点记日志。
 */
static inline bool f5_guard_step(struct f5_guard_entry *entries, int n,
                                 unsigned int uid, uint32_t host_hash,
                                 uint64_t now, const struct f5_guard_cfg *cfg,
                                 bool *just_tripped) {
    if (just_tripped != NULL) *just_tripped = false;
    for (int i = 0; i < n; i++) {
        if (entries[i].uid == uid && entries[i].host_hash == host_hash &&
            entries[i].win_start != 0) {
            if (entries[i].until_ms > now) return true;
            if (now - entries[i].win_start > cfg->window_ms) {
                entries[i].win_start = now;
                entries[i].count = 0;
            }
            entries[i].count++;
            if (entries[i].count >= cfg->threshold) {
                entries[i].until_ms = now + cfg->cooldown_ms;
                if (just_tripped != NULL) *just_tripped = true;
            }
            return false;
        }
    }
    /* 新条目：优先空位，否则覆盖最久未更新的那个（有界） */
    int slot = -1, oldest = 0;
    for (int i = 0; i < n; i++) {
        if (entries[i].win_start == 0) { slot = i; break; }
        if (entries[i].win_start < entries[oldest].win_start) oldest = i;
    }
    if (slot < 0) slot = oldest;
    entries[slot].uid = uid;
    entries[slot].host_hash = host_hash;
    entries[slot].win_start = now;
    entries[slot].count = 1;
    entries[slot].until_ms = 0;
    return false;
}

#endif /* ADBLOCK_F5_GUARD_H */
