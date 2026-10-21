package org.adaway.model.traffic;

import java.security.MessageDigest;
import java.util.Arrays;

/** Fixed authenticated CONNECT subset for isolated diagnostics, never an arbitrary proxy. */
public final class DiagnosticSocks5 {
    public static final int PORT = 53539;
    public static final int LIMIT = 256;
    private static final byte[] USER = {'a','d','a','w','a','y'};
    private DiagnosticSocks5() { }
    public static byte[] greeting() { return new byte[]{5,1,2}; }
    public static int greetingCode(byte[] bytes) { return Arrays.equals(bytes, greeting()) ? 0 : 255; }
    public static byte[] authentication(byte[] token) {
        if (token == null || token.length != 16) throw new IllegalArgumentException("Invalid session token");
        byte[] out = new byte[25]; out[0]=1;out[1]=6;
        System.arraycopy(USER,0,out,2,6);out[8]=16;System.arraycopy(token,0,out,9,16);return out;
    }
    public static int authenticationCode(byte[] bytes, byte[] token) {
        return token != null && token.length == 16 && bytes != null
                && MessageDigest.isEqual(bytes, authentication(token)) ? 0 : 1;
    }
    public static byte[] request(boolean ipv6) {
        byte[] out = new byte[ipv6 ? 22 : 10];out[0]=5;out[1]=1;out[3]=(byte)(ipv6?4:1);
        if (ipv6) { out[4]=0x20;out[5]=1;out[7]=2;out[19]=1; }
        else { out[4]=(byte)198;out[5]=18;out[7]=1; }
        out[out.length-2]=(byte)(PORT>>>8);out[out.length-1]=(byte)PORT;return out;
    }
    public static int requestCode(byte[] bytes, boolean ipv6) {
        if (bytes == null || bytes.length < 4 || bytes[0]!=5 || bytes[2]!=0) return 1;
        if (bytes[1]!=1) return 7;
        if (bytes[3]!=1 && bytes[3]!=4) return 8;
        return Arrays.equals(bytes,request(ipv6)) ? 0 : 2;
    }
    public static byte[] reply(int code) { return new byte[]{5,(byte)code,0,1,127,0,0,1,0,0}; }
}
