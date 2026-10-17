#include "traffic_core_contract.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
    const char *path = argc > 1 ? argv[1] : "webserver/jni/traffic_core_vectors.txt";
    FILE *file = fopen(path, "r"); assert(file);
    char line[512], label[128], extra;
    unsigned count = 0;
    while (fgets(line, sizeof(line), file)) {
        if (line[0] == '#' || line[0] == '\n') continue;
        int type, qclass, question, rules, override, allow, block, family, mode, action, reason;
        int columns = sscanf(line, "%127s %d %d %d %d %d %d %d %d %d %d %d %c", label,
                &type, &qclass, &question, &rules, &override, &allow, &block, &family, &mode, &action, &reason, &extra);
        assert(columns == 12);
        struct core_rule_input input = {type, qclass, question != 0, rules != 0,
                override, allow != 0, block != 0, family, mode};
        struct core_decision actual = core_decide(&input);
        if ((int)actual.action != action || (int)actual.reason != reason) fprintf(stderr, "case failed: %s\n", label);
        assert((int)actual.action == action && (int)actual.reason == reason);
        count++;
    }
    fclose(file); assert(count == 23);
    struct core_connection_stats stats = {10, 2, 6, 5, 2};
    assert(core_stats_valid(&stats));
    stats.forward_confirmed = 7;
    assert(!core_stats_valid(&stats));
    for (int rcode = 0; rcode < 16; rcode++) {
        assert(core_cacheable(1, rcode, false, false, false) == (rcode == 0 || rcode == 3));
        assert(!core_cacheable(1, rcode, true, false, false));
        assert(!core_cacheable(1, rcode, false, true, false));
        assert(!core_cacheable(1, rcode, false, false, true));
    }
    puts("core-contract-tests: 23 shared domain decisions and cache eligibility passed");
    return 0;
}
