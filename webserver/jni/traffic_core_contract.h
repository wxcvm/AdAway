#ifndef ADBLOCK_TRAFFIC_CORE_CONTRACT_H
#define ADBLOCK_TRAFFIC_CORE_CONTRACT_H
#include <stdbool.h>
#include <stdint.h>
#define TRAFFIC_CORE_CONTRACT_VERSION 1
enum core_action { CORE_PASS, CORE_NXDOMAIN, CORE_REFUSED, CORE_ZERO_ADDRESS, CORE_REPLACE };
enum core_reason { CORE_UNCLASSIFIED, CORE_USER_OVERRIDE, CORE_ALLOW_EXCEPTION,
    CORE_REPLACEMENT_RULE, CORE_BLOCK_RULE, CORE_INVALID_DATA, CORE_UNSUPPORTED_CLASS };
struct core_rule_input {
    int type, qclass;
    bool question_valid, rules_valid;
    int user_override;
    bool allow, block;
    int replacement_family, block_mode;
};
struct core_decision { enum core_action action; enum core_reason reason; };
struct core_flow_key {
    uint8_t family, protocol;
    uint8_t source[16], destination[16];
    uint16_t source_port, destination_port;
    uint64_t session_generation;
};
struct core_connection_stats {
    uint64_t seen, blocked, forward_attempted, forward_confirmed, unclassified;
};
static inline bool core_stats_valid(const struct core_connection_stats *p) {
    return p && p->seen <= INT64_MAX && p->blocked <= p->seen && p->forward_attempted <= p->seen
            && p->forward_confirmed <= p->forward_attempted && p->unclassified <= p->seen;
}
static inline struct core_decision core_blocked(const struct core_rule_input *p, enum core_reason reason) {
    enum core_action action = p->block_mode == 0 ? CORE_NXDOMAIN : CORE_REFUSED;
    if (p->block_mode == 2 && (p->type == 1 || p->type == 28)) action = CORE_ZERO_ADDRESS;
    return (struct core_decision){action, reason};
}
static inline struct core_decision core_decide(const struct core_rule_input *p) {
    if (!p || !p->question_valid || !p->rules_valid || p->type < 1 || p->type > 65535
            || p->user_override < -1 || p->user_override > 1 || p->block_mode < 0 || p->block_mode > 2)
        return (struct core_decision){CORE_PASS, CORE_INVALID_DATA};
    if (p->qclass != 1) return (struct core_decision){CORE_PASS, CORE_UNSUPPORTED_CLASS};
    if (p->user_override == 1) return (struct core_decision){CORE_PASS, CORE_USER_OVERRIDE};
    if (p->user_override == -1) return core_blocked(p, CORE_USER_OVERRIDE);
    if (p->allow) return (struct core_decision){CORE_PASS, CORE_ALLOW_EXCEPTION};
    if (p->replacement_family != 0) {
        int family = p->type == 1 ? 4 : p->type == 28 ? 6 : 0;
        if (family && p->replacement_family == family)
            return (struct core_decision){CORE_REPLACE, CORE_REPLACEMENT_RULE};
        return (struct core_decision){CORE_PASS, CORE_INVALID_DATA};
    }
    if (p->block) return core_blocked(p, CORE_BLOCK_RULE);
    return (struct core_decision){CORE_PASS, CORE_UNCLASSIFIED};
}
static inline bool core_cacheable(int qclass, int rcode, bool dnssec, bool edns, bool personalized) {
    return qclass == 1 && (rcode == 0 || rcode == 3) && !dnssec && !edns && !personalized;
}
#endif
