package org.adaway.model.traffic;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class EffectiveRuleSnapshotTest {
    @Test public void replacementAndRuleGenerationPublishTogetherAndInvalidUpdatePreservesOld() {
        RuleSnapshotRepository store = new RuleSnapshotRepository();
        assertTrue(store.publishEffective(1, Map.of("redirect.test", TrafficCoreContract.Action.REPLACE), Map.of("redirect.test", "2001:db8::8")));
        RuleSnapshotRepository.Snapshot first = store.read();
        assertEquals(TrafficCoreContract.Action.REPLACE, first.decide("REDIRECT.TEST.", 28, 1).action);
        assertEquals(TrafficCoreContract.Reason.INVALID_DATA, first.decide("redirect.test", 1, 1).reason);
        assertEquals(TrafficCoreContract.Action.PASS, first.decide("redirect.test", 65, 1).action);
        assertFalse(store.publishEffective(2, Map.of("redirect.test", TrafficCoreContract.Action.REPLACE), Map.of("redirect.test", "not-an-ip.test")));
        assertSame(first, store.read());
        assertFalse(store.publish(2, Map.of("redirect.test", TrafficCoreContract.Action.REPLACE)));
        assertFalse(store.publishEffective(2, Map.of("other.test", TrafficCoreContract.Action.PASS), Map.of("redirect.test", "192.0.2.8")));
    }
    @Test public void concurrentReplacementReadsNeverMixVersions() throws Exception {
        RuleSnapshotRepository store = new RuleSnapshotRepository();
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 1000; i++) assertTrue(store.publishEffective(i,
                    Map.of("redirect.test", TrafficCoreContract.Action.REPLACE),
                    Map.of("redirect.test", i % 2 == 0 ? "192.0.2.8" : "2001:db8::8")));
        });
        writer.start();
        for (int i = 0; i < 10000; i++) {
            RuleSnapshotRepository.Snapshot snapshot = store.read();
            if (snapshot.generation != 0) assertEquals(snapshot.generation % 2 == 0 ? TrafficCoreContract.Action.REPLACE : TrafficCoreContract.Action.PASS,
                    snapshot.decide("redirect.test", 1, 1).action);
        }
        writer.join(5000); assertFalse(writer.isAlive()); assertEquals(1000, store.read().generation);
    }
}
