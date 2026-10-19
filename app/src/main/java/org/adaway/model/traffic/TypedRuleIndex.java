package org.adaway.model.traffic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Built once before publication. Queries visit labels and wildcard candidates, not all rules. */
final class TypedRuleIndex {
    private final Map<String, DomainRule> exact = new HashMap<>(), suffix = new HashMap<>(), wildcard = new HashMap<>();
    private final WildcardRuleIndex wildcardIndex;
    TypedRuleIndex(List<DomainRule> records) {
        for (DomainRule rule : records) {
            Map<String, DomainRule> target = rule.scope == DomainRule.Scope.EXACT ? exact
                    : rule.scope == DomainRule.Scope.SUFFIX ? suffix : wildcard;
            DomainRule before = target.get(rule.domain);
            if (DomainRule.compare(rule, before) > 0) target.put(rule.domain, rule);
        }
        wildcardIndex = new WildcardRuleIndex(wildcard.keySet());
    }
    DomainRule find(String host) {
        DomainRule best = exact.get(host);
        for (String name = host; name != null;) {
            DomainRule rule = suffix.get(name);
            if (rule != null && DomainRule.compare(rule, best) > 0) best = rule;
            int dot = name.indexOf('.');
            name = dot < 0 ? null : name.substring(dot + 1);
        }
        for (String name : wildcardIndex.findMatches(host)) {
            DomainRule rule = wildcard.get(name);
            if (DomainRule.compare(rule, best) > 0) best = rule;
        }
        return best;
    }
}
