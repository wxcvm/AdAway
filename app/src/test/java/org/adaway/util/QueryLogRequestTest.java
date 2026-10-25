package org.adaway.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class QueryLogRequestTest {
 @Test public void encodesActualManagementRequestAndKeepsNumericUnknownSeparateFromAll() {
  String p=QueryLogRequest.path(0,300,null,null,null,3,6,-1,-1,"A+B 中文 /403");
  assertEquals("/internal-qlog?filterVersion=1&offset=0&limit=300&action=3&family=6&type=-1&uid=-1&q=A%2BB+%E4%B8%AD%E6%96%87+%2F403",p);
  String next=QueryLogRequest.path(300,300,"a".repeat(32),"900","b".repeat(64),null,null,null,null,"");
  assertTrue(next.endsWith("&generation="+"a".repeat(32)+"&anchor=900&queryKey="+"b".repeat(64)));
 }
 @Test public void rejectsPartialCursorAndOversizedOrInvalidUtf8RatherThanTruncating() {
  assertThrows(IllegalArgumentException.class,()->QueryLogRequest.path(0,300,"a".repeat(32),"2",null,null,null,null,null,""));
  for(String q:new String[]{"中".repeat(64),"bad\ud800","bad\n","bad\u0085"})
   assertThrows(IllegalArgumentException.class,()->QueryLogRequest.path(0,300,null,null,null,null,null,null,null,q));
  assertTrue(QueryLogRequest.path(0,300,null,null,null,null,null,null,null,"中".repeat(63)+"ab").contains("&q="));
 }
}
