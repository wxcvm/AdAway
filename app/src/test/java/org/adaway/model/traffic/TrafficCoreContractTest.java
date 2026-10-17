package org.adaway.model.traffic;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class TrafficCoreContractTest {
    @Test public void permittedRulesCannotInventForwardingSuccess() {
        new TrafficCoreContract.ConnectionStats(10, 2, 6, 5, 2);
        try { new TrafficCoreContract.ConnectionStats(10, 2, 6, 7, 2); fail("invented confirmations accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void javaDecisionsMatchTheSameNativeFixture() throws Exception {
        Path path = Path.of("../webserver/jni/traffic_core_vectors.txt");
        if (!Files.isRegularFile(path)) path = Path.of("webserver/jni/traffic_core_vectors.txt");
        int count = 0;
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.trim().split("\\s+"); assertEquals(12, fields.length);
            int[] values = new int[11]; for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(fields[i + 1]);
            TrafficCoreContract.Input input = new TrafficCoreContract.Input(values[0], values[1], values[2] != 0,
                    values[3] != 0, values[4], values[5] != 0, values[6] != 0, values[7], values[8]);
            TrafficCoreContract.Decision actual = TrafficCoreContract.decide(input);
            assertEquals(fields[0], values[9], actual.action.ordinal());
            assertEquals(fields[0], values[10], actual.reason.ordinal()); count++;
        }
        assertEquals(23, count);
    }
    @Test public void personalizedEdnsDnssecAndFailuresCannotShareCacheEntries() {
        for (int code = 0; code < 16; code++) {
            assertEquals(code == 0 || code == 3, TrafficCoreContract.cacheable(1, code, false, false, false));
            assertFalse(TrafficCoreContract.cacheable(1, code, true, false, false));
            assertFalse(TrafficCoreContract.cacheable(1, code, false, true, false));
            assertFalse(TrafficCoreContract.cacheable(1, code, false, false, true));
        }
    }
    @Test public void flowKeysOwnAddressBytesAndRequireARealSession() {
        byte[] source = {1,2,3,4}, destination = {5,6,7,8};
        TrafficCoreContract.FlowKey key = new TrafficCoreContract.FlowKey(4, 17, source, destination, 1, 2, 3);
        source[0] = 9; key.source()[0] = 9;
        assertArrayEquals(new byte[]{1,2,3,4}, key.source());
        try { new TrafficCoreContract.FlowKey(6, 17, source, destination, 1, 2, 0); fail("bad flow accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
