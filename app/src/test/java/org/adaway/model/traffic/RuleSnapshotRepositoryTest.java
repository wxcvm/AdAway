package org.adaway.model.traffic;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.HashMap;
import java.util.Map;

public class RuleSnapshotRepositoryTest {
    @Test public void malformedOrOlderUpdatesPreserveLastValidSnapshotAndCallerCannotMutateIt() {
        RuleSnapshotRepository repository = new RuleSnapshotRepository();
        Map<String, TrafficCoreContract.Action> input = new HashMap<>();
        input.put("EXAMPLE.TEST.", TrafficCoreContract.Action.NXDOMAIN);
        assertTrue(repository.publish(1, input)); input.clear();
        RuleSnapshotRepository.Snapshot valid = repository.read();
        assertEquals(TrafficCoreContract.Action.NXDOMAIN, valid.rules.get("example.test"));
        input.put("bad..test", TrafficCoreContract.Action.PASS);
        assertFalse(repository.publish(2, input)); assertSame(valid, repository.read());
        assertFalse(repository.publish(1, Map.of("other.test", TrafficCoreContract.Action.PASS)));
        try { valid.rules.clear(); fail("published rules were mutable"); }
        catch (UnsupportedOperationException expected) { }
    }
    @Test public void concurrentReadersNeverObserveADataGenerationMismatch() throws Exception {
        RuleSnapshotRepository repository = new RuleSnapshotRepository();
        Thread writer = new Thread(() -> {
            for (int i = 1; i <= 1000; i++)
                repository.publish(i, Map.of("v" + i + ".test", TrafficCoreContract.Action.PASS));
        });
        writer.start();
        for (int i = 0; i < 10000; i++) {
            RuleSnapshotRepository.Snapshot snapshot = repository.read();
            if (snapshot.generation != 0) assertTrue(snapshot.rules.containsKey("v" + snapshot.generation + ".test"));
        }
        writer.join(3000); assertFalse(writer.isAlive()); assertEquals(1000, repository.read().generation);
    }
}
