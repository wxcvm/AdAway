package org.adaway.model.traffic;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DiagnosticTcpSessionTest {
    @Test public void sharedSocks5ContractVectors() throws Exception {
        byte[] token=new byte[16];for(int i=0;i<16;i++)token[i]=(byte)i;
        int count=0;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/tcp/socks5_vectors.tsv"),StandardCharsets.US_ASCII))) {
            String line;
            while((line=reader.readLine())!=null) {
                if(line.startsWith("#"))continue;
                String[] parts=line.split("\t",-1);byte[] packet=DiagnosticDualStackTest.unhex(parts[1]);
                int code;
                if(parts[0].equals("greeting"))code=DiagnosticSocks5.greetingCode(packet);
                else if(parts[0].equals("auth"))code=DiagnosticSocks5.authenticationCode(packet,token);
                else if(parts[0].startsWith("reply"))code=DiagnosticSocks5.replyCode(packet,parts[0].equals("reply6"));
                else code=DiagnosticSocks5.requestCode(packet,parts[0].equals("connect6"));
                assertEquals(line,Integer.parseInt(parts[2]),code);count++;
            }
        }
        assertTrue(count>=24);
    }
    private static void clean(DiagnosticTcpSession.Result result) {
        assertTrue(result.status.toString(),result.cleanup);assertEquals(result.opened,result.closed);
    }
    @Test public void bothFamiliesReallyRelaySplitStreamsAndHalfCloseBeforeReply() {
        for(boolean ipv6:new boolean[]{false,true}) for(int cycle=0;cycle<3;cycle++) {
            DiagnosticTcpSession session=new DiagnosticTcpSession(ipv6,socket->{assertFalse(socket.isConnected());return true;});
            DiagnosticTcpSession.Result result=session.run();
            assertEquals(result.toString(),DiagnosticTcpSession.Status.OK,result.status);
            assertEquals(6,result.opened);assertEquals(1,result.protectedSockets);clean(result);
        }
    }
    @Test public void failedAuthenticationAndDestinationNeverOpenUpstream() {
        for(boolean ipv6:new boolean[]{false,true})
            for(DiagnosticTcpSession.Fault fault:new DiagnosticTcpSession.Fault[]{DiagnosticTcpSession.Fault.BAD_AUTH,DiagnosticTcpSession.Fault.BAD_TARGET}) {
                DiagnosticTcpSession.Result result=new DiagnosticTcpSession(ipv6,socket->{fail("Denied request protected upstream");return true;},1200,fault).run();
                assertEquals(DiagnosticTcpSession.Status.REJECTED,result.status);assertEquals(0,result.protectedSockets);clean(result);
            }
    }
    @Test public void corruptTruncatedAndOversizedStreamsCannotSucceed() {
        for(boolean ipv6:new boolean[]{false,true})
            for(DiagnosticTcpSession.Fault fault:new DiagnosticTcpSession.Fault[]{DiagnosticTcpSession.Fault.STALE,DiagnosticTcpSession.Fault.TRUNCATED,DiagnosticTcpSession.Fault.OVERSIZE}) {
                DiagnosticTcpSession.Result result=new DiagnosticTcpSession(ipv6,socket->true,1200,fault).run();
                assertEquals(DiagnosticTcpSession.Status.REJECTED,result.status);clean(result);
            }
    }
    @Test public void realReadTimeoutAndInFlightCancellationAllowFreshSession() throws Exception {
        for(boolean ipv6:new boolean[]{false,true}) {
            DiagnosticTcpSession.Result timed=new DiagnosticTcpSession(ipv6,socket->true,150,DiagnosticTcpSession.Fault.SILENT).run();
            assertEquals(DiagnosticTcpSession.Status.TIMEOUT,timed.status);clean(timed);
            DiagnosticTcpSession session=new DiagnosticTcpSession(ipv6,socket->true,1200,DiagnosticTcpSession.Fault.SILENT);
            AtomicReference<DiagnosticTcpSession.Result> result=new AtomicReference<>();
            Thread worker=new Thread(()->result.set(session.run()));worker.start();
            assertTrue(session.awaitBackend(1000));session.close();worker.join(1000);assertFalse(worker.isAlive());
            assertEquals(DiagnosticTcpSession.Status.CANCELLED,result.get().status);clean(result.get());
            assertEquals(DiagnosticTcpSession.Status.OK,new DiagnosticTcpSession(ipv6,socket->true).run().status);
        }
    }
}
