package org.adaway.model.traffic;

import static org.junit.Assert.*;
import java.net.*;
import java.util.*;
import org.junit.Test;

public class DiagnosticDnsResolverPolicyTest {
    private static InetAddress v4(int first, int last) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte)first,0,0,(byte)last});
    }
    private static Inet6Address v6(int prefix, int last, int scope) throws Exception {
        byte[] bytes = new byte[16]; bytes[0] = (byte)(prefix >>> 8); bytes[1] = (byte)prefix; bytes[15] = (byte)last;
        return Inet6Address.getByAddress(null,bytes,scope);
    }
    @Test public void existingIpv4PreferenceAndOrderArePreserved() throws Exception {
        InetAddress six = v6(0x2001,1,0), first = v4(10,53), second = v4(10,54);
        assertSame(first,DiagnosticDnsResolverPolicy.select(List.of(six,first,second)));
    }
    @Test public void ipv6OnlyNetworkUsesItsOriginalFirstUsableAddress() throws Exception {
        InetAddress first = v6(0x2001,1,0), second = v6(0xfd00,53,0);
        List<InetAddress> servers = List.of(first,second);
        assertSame(first,DiagnosticDnsResolverPolicy.select(servers));
        assertSame(first,servers.get(0));
    }
    @Test public void unusableEndpointsAndAmbiguousLinkLocalZonesAreRejected() throws Exception {
        assertNull(DiagnosticDnsResolverPolicy.select(null));
        assertNull(DiagnosticDnsResolverPolicy.select(Collections.emptyList()));
        assertNull(DiagnosticDnsResolverPolicy.select(Arrays.asList(null,v4(0,0),v4(127,1),v4(224,1),
            v6(0,0,0),v6(0,1,0),v6(0xff02,1,0),v6(0xfe80,53,0))));
    }
    @Test public void scopedLinkLocalResolverRetainsTheOsZone() throws Exception {
        Inet6Address scoped = v6(0xfe80,53,7);
        assertSame(scoped,DiagnosticDnsResolverPolicy.select(List.of(v6(0xfe80,53,0),scoped)));
        assertEquals(7,((Inet6Address)DiagnosticDnsResolverPolicy.select(List.of(scoped))).getScopeId());
    }
}
