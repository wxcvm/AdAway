package org.adaway.model.traffic;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticDualStackTest {
    static byte[] unhex(String text) {
        byte[] out=new byte[text.length()/2];
        for (int i=0;i<out.length;i++) out[i]=(byte)Integer.parseInt(text.substring(2*i,2*i+2),16);
        return out;
    }
    @Test public void sameSharedRawPacketsProduceExactRepliesAndRejectBeforeExchange() throws Exception {
        try (BufferedReader reader=new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/transport/packet_vectors.tsv"),StandardCharsets.US_ASCII))) {
            String line; int count=0;
            while ((line=reader.readLine())!=null) {
                if (line.startsWith("#")) continue;
                String[] parts=line.split("\t"); byte[] request=unhex(parts[1]); AtomicInteger calls=new AtomicInteger();
                byte[] reply=DiagnosticUdpRelay.process(request,request.length,payload->{calls.incrementAndGet();payload[0]^=0x5a;return payload;});
                if ("0".equals(parts[2])) { assertNull(parts[0],reply); assertEquals(parts[0],0,calls.get()); }
                else { assertArrayEquals(parts[0],unhex(parts[3]),reply); assertEquals(1,calls.get()); }
                count++;
            }
            assertTrue(count>=30);
        }
    }
    @Test public void ipv6IsAnExactHostRouteOnly() {
        assertEquals(128,DiagnosticTunPlan.IPV6_PREFIX);
        assertTrue(DiagnosticTunPlan.isSafeRoute("2001:2::1",128));
        assertFalse(DiagnosticTunPlan.isSafeRoute("2001:2::1",64));
        assertFalse(DiagnosticTunPlan.isSafeRoute("2001:2::2",128));
        assertFalse(DiagnosticTunPlan.isSafeRoute("::",0));
    }
    @Test public void actualLocalBackendsBothFamiliesStopAndRestart() throws Exception {
        for (int iteration=0;iteration<4;iteration++) {
            DiagnosticLocalRelay relay=new DiagnosticLocalRelay(socket->{assertFalse(socket.isConnected());return true;});
            try {
                for (boolean ipv6:new boolean[]{false,true})
                    assertArrayEquals(new byte[]{0x5b,2,3},relay.exchange(ipv6,new byte[]{1,2,3}));
            } finally { relay.close(); }
            assertTrue(relay.awaitStopped(1000));
            try { relay.exchange(true,new byte[]{1});fail("Closed session accepted an exchange"); }
            catch (java.io.IOException expected) { /* Session socket registry remains closed. */ }
        }
    }
}
