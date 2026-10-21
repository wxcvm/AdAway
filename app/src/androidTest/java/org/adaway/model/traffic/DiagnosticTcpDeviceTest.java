package org.adaway.model.traffic;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Actual Android stream sockets; protected active-VPN integration is tested separately. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticTcpDeviceTest {
    @Test public void rejectsCredentialsAndTargetsBeforeUpstreamThenRecovers() {
        for(boolean ipv6:new boolean[]{false,true}) {
            for(DiagnosticTcpSession.Fault fault:new DiagnosticTcpSession.Fault[]{DiagnosticTcpSession.Fault.BAD_AUTH,DiagnosticTcpSession.Fault.BAD_TARGET}) {
                DiagnosticTcpSession.Result result=new DiagnosticTcpSession(ipv6,socket->{fail("Rejected request opened upstream");return false;},1200,fault).run();
                assertEquals(result.toString(),DiagnosticTcpSession.Status.REJECTED,result.status);
                assertEquals(0,result.protectedSockets);assertTrue(result.cleanup);assertEquals(result.opened,result.closed);
            }
            assertEquals(DiagnosticTcpSession.Status.OK,new DiagnosticTcpSession(ipv6,socket->true).run().status);
        }
    }
    @Test public void boundedStreamsRejectCorruptTruncatedOversizedAndTimeout() {
        for(boolean ipv6:new boolean[]{false,true}) {
            for(DiagnosticTcpSession.Fault fault:new DiagnosticTcpSession.Fault[]{DiagnosticTcpSession.Fault.STALE,DiagnosticTcpSession.Fault.TRUNCATED,DiagnosticTcpSession.Fault.OVERSIZE,DiagnosticTcpSession.Fault.SILENT}) {
                DiagnosticTcpSession.Result result=new DiagnosticTcpSession(ipv6,socket->true,300,fault).run();
                DiagnosticTcpSession.Status expected=fault==DiagnosticTcpSession.Fault.SILENT?DiagnosticTcpSession.Status.TIMEOUT:DiagnosticTcpSession.Status.REJECTED;
                assertEquals(result.toString(),expected,result.status);assertTrue(result.cleanup);assertEquals(result.opened,result.closed);
            }
        }
    }
    @Test public void stoppingARealPendingReadClosesBothWorkersAndAllowsFreshSession() throws Exception {
        for(boolean ipv6:new boolean[]{false,true}) {
            DiagnosticTcpSession session=new DiagnosticTcpSession(ipv6,socket->true,1200,DiagnosticTcpSession.Fault.SILENT);
            AtomicReference<DiagnosticTcpSession.Result> result=new AtomicReference<>();
            Thread worker=new Thread(()->result.set(session.run()));worker.start();
            try { assertTrue(session.awaitBackend(1000)); }
            finally { session.close();worker.join(1000); }
            assertFalse(worker.isAlive());assertNotNull(result.get());
            assertEquals(result.get().toString(),DiagnosticTcpSession.Status.CANCELLED,result.get().status);
            assertTrue(result.get().cleanup);assertEquals(result.get().opened,result.get().closed);
            assertEquals(DiagnosticTcpSession.Status.OK,new DiagnosticTcpSession(ipv6,socket->true).run().status);
        }
    }
}
