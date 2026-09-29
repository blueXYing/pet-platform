package com.petplatform.verification.biz.application;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
class CredentialProtectionTest {
 @Test void oldKeyRemainsReadableAfterRotationButMissingOldKeyFails(){
  byte[] old=new byte[32],fresh=new byte[32];Arrays.fill(fresh,(byte)7);
  var before=new CredentialProtection("v1",Map.of("v1",old),Map.of("v1",old));
  String value=before.generate();byte[] cipher=before.protect("CODE:42",value.getBytes(StandardCharsets.UTF_8));
  var after=new CredentialProtection("v2",Map.of("v1",old,"v2",fresh),Map.of("v1",old,"v2",fresh));
  assertEquals(value,new String(after.reveal("CODE:42",cipher),StandardCharsets.UTF_8));assertEquals(before.digest("v1",value),after.digest("v1",value));
  assertThrows(IllegalStateException.class,()->new CredentialProtection("v2",Map.of("v2",fresh),Map.of("v2",fresh)).reveal("CODE:42",cipher));
 }
 @Test void wrongPurposeAndTamperingCannotDecryptAndCodeHas160BitEncoding(){
  var p=new CredentialProtection("qa",Map.of("qa",new byte[32]),Map.of("qa",new byte[32]));String code=p.generate();assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{32}"));
  byte[] c=p.protect("CODE:1",code.getBytes(StandardCharsets.UTF_8));assertThrows(IllegalStateException.class,()->p.reveal("CODE:2",c));c[c.length-1]^=1;assertThrows(IllegalStateException.class,()->p.reveal("CODE:1",c));
 }
 @Test void absentOrShortKeysNeverCreateProtection(){assertThrows(IllegalArgumentException.class,()->new CredentialProtection("v1",Map.of(),Map.of()));assertThrows(IllegalArgumentException.class,()->new CredentialProtection("v1",Map.of("v1",new byte[16]),Map.of("v1",new byte[32])));}
}
