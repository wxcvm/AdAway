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

    @Test public void fixedDnsAAndAaaaStillEnterWithoutBroadeningTheQuestionWhitelist() {
        DiagnosticPacketAdmission admission = new DiagnosticPacketAdmission();
        byte[] bad = dnsPacket(DiagnosticDnsCodec.TYPE_A); bad[41] = 'x';
        assertFalse(admission.allow(bad, bad.length, 1000));
        byte[] a = dnsPacket(DiagnosticDnsCodec.TYPE_A), aaaa = dnsPacket(DiagnosticDnsCodec.TYPE_AAAA);
        assertTrue(admission.allow(a, a.length, 1001));
        assertTrue(admission.allow(aaaa, aaaa.length, 1201));
    }

    private static byte[] dnsPacket(int type) {
        byte[] query = DiagnosticDnsCodec.query(123, type);
        byte[] packet = new byte[28 + query.length];
        packet[0]=0x45;packet[2]=(byte)(packet.length>>>8);packet[3]=(byte)packet.length;
        packet[8]=64;packet[9]=17;
        packet[12]=(byte)198;packet[13]=18;packet[15]=2;
        packet[16]=(byte)198;packet[17]=18;packet[19]=1;
        packet[20]=0x12;packet[21]=0x34;
        packet[22]=(byte)(DiagnosticDnsCodec.PORT>>>8);packet[23]=(byte)DiagnosticDnsCodec.PORT;
        packet[24]=(byte)((query.length+8)>>>8);packet[25]=(byte)(query.length+8);
        System.arraycopy(query,0,packet,28,query.length);
        return DiagnosticPacketFixture.checksum(packet);
    }
}
