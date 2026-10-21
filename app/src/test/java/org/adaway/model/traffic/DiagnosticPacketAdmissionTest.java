package org.adaway.model.traffic;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class DiagnosticPacketAdmissionTest {
    private List<String[]> vectors() throws Exception {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/transport/packet_vectors.tsv"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.startsWith("#")) out.add(line.split("\t"));
        }
        return out;
    }

    @Test public void everyRejectedVectorLeavesBudgetForAnImmediateValidProbe() throws Exception {
        List<String[]> vectors = vectors();
        byte[] valid = DiagnosticDualStackTest.unhex(vectors.stream().filter(v -> !v[2].equals("0")).findFirst().get()[1]);
        int rejected = 0;
        for (String[] row : vectors) if (row[2].equals("0")) {
            DiagnosticPacketAdmission admission = new DiagnosticPacketAdmission();
            byte[] packet = DiagnosticDualStackTest.unhex(row[1]);
            assertFalse(row[0], admission.allow(packet, packet.length, 1000));
            assertTrue(row[0], admission.allow(valid, valid.length, 1001));
            rejected++;
        }
        assertTrue(rejected >= 20);
    }

    @Test public void ipv6ControlPacketsDoNotStarveEitherFamilyButValidRequestsRemainLimited() throws Exception {
        byte[] ipv4 = null, ipv6 = null;
        for (String[] row : vectors()) if (!row[2].equals("0")) {
            byte[] packet = DiagnosticDualStackTest.unhex(row[1]);
            if (DiagnosticUdpEcho.ipv6(packet, packet.length)) ipv6 = packet;
            else ipv4 = packet;
        }
        assertNotNull(ipv4); assertNotNull(ipv6);
        byte[] control = ipv6.clone(); control[6] = 58; // ICMPv6 is not a permitted diagnostic.
        DiagnosticPacketAdmission admission = new DiagnosticPacketAdmission();
        assertFalse(admission.allow(control, control.length, 1000));
        assertTrue(admission.allow(ipv4, ipv4.length, 1001));
        assertFalse(admission.allow(ipv6, ipv6.length, 1200));
        assertTrue(admission.allow(ipv6, ipv6.length, 1201));
        assertEquals("accepted=2, unsupported=1, limited=1", admission.counts());
    }
}
