package org.adaway.model.source;

import java.util.ArrayList;
import java.util.List;
import org.adaway.db.dao.HostListItemDao;
import org.adaway.db.entity.HostListItem;
import org.adaway.db.entity.ListType;
import org.adaway.model.traffic.DomainRule;
import org.adaway.model.traffic.RuleSnapshotRepository;
import org.adaway.model.traffic.TrafficCoreContract;

/** Called inside the same Room read/write transaction as effective Hosts synchronization. */
public final class PersistedRuleSnapshots {
    private PersistedRuleSnapshots() { }
    public static RuleSnapshotRepository.Snapshot prepare(HostListItemDao dao, long generation) {
        return prepare(dao.getEnabledRules(), generation);
    }
    public static RuleSnapshotRepository.Snapshot prepare(List<HostListItem> rows, long generation) {
        List<DomainRule> records = new ArrayList<>();
        for (HostListItem row : rows) {
            boolean legacy = row.getMatchScope() == -1;
            int scope = row.getMatchScope();
            if (legacy) scope = row.getHost().contains("*") || row.getHost().contains("?") ? 2 : 0;
            if (scope < 0 || scope > 2) return null;
            records.add(new DomainRule(row.getHost(), row.getType() == ListType.ALLOWED
                    ? TrafficCoreContract.Action.PASS : row.getType() == ListType.REDIRECTED
                    ? TrafficCoreContract.Action.REPLACE : TrafficCoreContract.Action.NXDOMAIN,
                    DomainRule.Scope.values()[scope], row.getRedirection(), row.getSourceId(),
                    legacy || row.getSourceId() == 1 ? row.getId() : row.getSourceOrder(), legacy));
        }
        return RuleSnapshotRepository.prepareRules(generation, records);
    }
}
