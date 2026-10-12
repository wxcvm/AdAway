package org.adaway.model.traffic;
import org.junit.Test;
import static org.junit.Assert.*;

public class VpnOwnershipEvidenceTest {
    @Test public void transportOrServiceBitCannotInventOwnership() {
        assertEquals(TrafficModePolicy.VpnOwner.UNKNOWN, VpnOwnershipEvidence.decide(true,true,false,false,false));
        assertEquals(TrafficModePolicy.VpnOwner.OURS, VpnOwnershipEvidence.decide(true,true,true,false,false));
        assertEquals(TrafficModePolicy.VpnOwner.OTHER, VpnOwnershipEvidence.decide(true,true,true,true,false));
        assertEquals(TrafficModePolicy.VpnOwner.UNKNOWN, VpnOwnershipEvidence.decide(false,true,false,false,false));
        assertEquals(TrafficModePolicy.VpnOwner.NONE, VpnOwnershipEvidence.decide(false,false,false,false,false));
        assertEquals(TrafficModePolicy.VpnOwner.UNKNOWN, VpnOwnershipEvidence.decide(false,false,false,false,true));
    }
}
