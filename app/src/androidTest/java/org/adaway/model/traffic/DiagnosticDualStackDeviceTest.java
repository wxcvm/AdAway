package org.adaway.model.traffic;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DiagnosticDualStackDeviceTest {
    private static byte[] unhex(String value) {
        byte[] bytes=new byte[value.length()/2];
        for (int i=0;i<bytes.length;i++) bytes[i]=(byte)Integer.parseInt(value.substring(2*i,2*i+2),16);
        return bytes;
    }
    private static List<String[]> vectors() throws Exception {
        List<String[]> result=new ArrayList<>();
        try (BufferedReader input=new BufferedReader(new InputStreamReader(
                InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("packet_vectors.tsv"),java.nio.charset.StandardCharsets.US_ASCII))) {
            String line;
            while ((line=input.readLine())!=null) if (!line.startsWith("#")) result.add(line.split("\t"));
        }
        return result;
    }
    @Test public void actualPacketPumpAndLoopbackRelayUseBothIpFamilies() throws Exception {
        for (int cycle=0;cycle<3;cycle++) {
            DiagnosticLocalRelay relay=new DiagnosticLocalRelay(socket->{assertFalse(socket.isConnected());return true;});
            ArrayBlockingQueue<byte[]> replies=new ArrayBlockingQueue<>(8);
            DiagnosticPacketPump pump=new DiagnosticPacketPump(packet->DiagnosticUdpRelay.process(packet,packet.length,
                    payload->relay.exchange(DiagnosticUdpEcho.ipv6(packet,packet.length),payload)),replies::add,relay::close,()->fail("TUN writer failed"));
            try {
                for (String[] vector:vectors()) {
                    if ("0".equals(vector[2])) continue;
                    byte[] request=unhex(vector[1]); assertTrue(pump.submit(request,request.length));
                    assertArrayEquals(vector[0],unhex(vector[3]),replies.poll(2,java.util.concurrent.TimeUnit.SECONDS));
                }
            } finally { pump.close(); }
            assertTrue(pump.awaitStopped(1000)); assertTrue(relay.awaitStopped(1000));
            assertFalse(pump.submit(new byte[]{1},1)); assertTrue(replies.isEmpty());
        }
    }
    @Test public void closingInFlightReceiveDropsOldSessionReplyAndAllowsFreshSession() throws Exception {
        for (boolean ipv6:new boolean[]{false,true}) {
            DiagnosticLoopbackUdpServer four=new DiagnosticLoopbackUdpServer(false,false);
            DiagnosticLoopbackUdpServer six=new DiagnosticLoopbackUdpServer(true,false);
            DiagnosticLocalRelay relay=new DiagnosticLocalRelay(socket->true,four,six);
            AtomicInteger writes=new AtomicInteger();
            byte[] request=null;
            for (String[] vector:vectors()) if (vector[0].equals(ipv6?"v6-valid-1":"v4-valid-1")) request=unhex(vector[1]);
            assertNotNull(request);
            DiagnosticPacketPump pump=new DiagnosticPacketPump(packet->DiagnosticUdpRelay.process(packet,packet.length,
                    payload->relay.exchange(ipv6,payload)),packet->writes.incrementAndGet(),relay::close,()->fail("Writer failed"));
            try {
                assertTrue(pump.submit(request,request.length));
                assertTrue((ipv6?six:four).awaitRequest(1000));
                pump.close(); assertTrue(pump.awaitStopped(1000)); assertEquals(0,writes.get());
            } finally { pump.close(); }
            assertTrue(relay.awaitStopped(1000));
            DiagnosticLocalRelay fresh=new DiagnosticLocalRelay(socket->true);
            try { assertArrayEquals(new byte[]{0x5b},fresh.exchange(ipv6,new byte[]{1})); }
            finally { fresh.close(); assertTrue(fresh.awaitStopped(1000)); }
        }
    }
    @Test public void actualReceiveTimeoutLeavesSessionRecoverable() throws Exception {
        DiagnosticLocalRelay relay=new DiagnosticLocalRelay(socket->true,
                new DiagnosticLoopbackUdpServer(false,false),new DiagnosticLoopbackUdpServer(true,true));
        try {
            long begin=System.nanoTime();
            try { relay.exchange(false,new byte[]{1});fail("Silent backend accepted"); }
            catch (java.net.SocketTimeoutException expected) { /* Real receive deadline, not a mocked exception. */ }
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin)<2000);
            assertArrayEquals(new byte[]{0x5b},relay.exchange(true,new byte[]{1}));
        } finally { relay.close(); assertTrue(relay.awaitStopped(1000)); }
    }
}
