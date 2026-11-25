package org.adaway.broadcast;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class WatchdogCadenceTest {
    @Test public void onlyUnopposedLightModeReducesRecoveryCadence() {
        for (int flags = 0; flags < 8; flags++) {
            assertEquals("flags=" + flags, flags == 1,
                    ServerWatchdogWorker.usesLightCadence((flags & 1) != 0,
                            (flags & 2) != 0, (flags & 4) != 0));
        }
    }
}
