package com.petplatform.admin.biz.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.petplatform.admin.api.dto.AdminDataScope;
import com.petplatform.admin.biz.application.*;
import com.petplatform.admin.biz.domain.service.AdminPermissionEvaluator;
import com.petplatform.admin.biz.infrastructure.provider.*;
import com.petplatform.admin.biz.task.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminAuthPrimitivesTest {
    @TempDir Path directory;
    @Test void argon2idHasAcceptedParametersRandomSaltAndNoPasswordNormalization() {
        var hasher=new AdminPasswordHasher();char[] password="Example_ONLY_92!".toCharArray();
        String one=hasher.encode(password),two=hasher.encode(password);
        assertTrue(one.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"));assertNotEquals(one,two);
        assertTrue(hasher.matches(password,one));assertFalse(hasher.matches("Example_ONLY_92! ".toCharArray(),one));
        assertFalse(hasher.matches(password,null));assertFalse(one.contains("Example_ONLY"));
    }
    @Test void initialStrengthAndLengthRemainRequiredWithoutMfa() {
        assertDoesNotThrow(()->AdminPasswordHasher.requireInitialStrength("Abcdef12".toCharArray()));
        assertThrows(AdminAuthFailure.class,()->AdminPasswordHasher.requireInitialStrength("abcdefgh".toCharArray()));
        assertThrows(AdminAuthFailure.class,()->AdminPasswordHasher.require("short".toCharArray()));
        assertThrows(AdminAuthFailure.class,()->AdminPasswordHasher.require("X".repeat(65).toCharArray()));
    }
    @Test void secretEncryptionAuthenticatesContextAndDoesNotExposePayload() {
        var codec=AdminSecretCodec.fixed("qa-key",AuthTestDatabase.bytes(3),AuthTestDatabase.bytes(7));
        Map<String,Object> body=Map.of("accessToken","EXAMPLE_ONLY_SECRET","sessionId","9007199254740993");
        byte[] encrypted=codec.encrypt("qa-key","ADMIN|request-a",body);
        assertEquals(body,codec.decrypt("qa-key","ADMIN|request-a",encrypted));
        assertFalse(new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("EXAMPLE_ONLY_SECRET"));
        assertThrows(AdminAuthFailure.class,()->codec.decrypt("qa-key","ADMIN|request-b",encrypted));
        encrypted[encrypted.length-1]^=1;
        assertThrows(AdminAuthFailure.class,()->codec.decrypt("qa-key","ADMIN|request-a",encrypted));
        assertThrows(IllegalArgumentException.class,()->new AdminSecretCodec.Keys(AuthTestDatabase.bytes(3),AuthTestDatabase.bytes(3)));
    }
    @Test void macSeparatesFieldBoundariesAndKeyFailureIsSanitized() {
        var codec=AdminSecretCodec.fixed("qa-key",AuthTestDatabase.bytes(3),AuthTestDatabase.bytes(7));
        assertFalse(Arrays.equals(codec.mac("qa-key","ab","c"),codec.mac("qa-key","a","bc")));
        var failure=assertThrows(AdminAuthFailure.class,()->codec.mac("missing","EXAMPLE_ONLY_SECRET"));
        assertEquals(503,failure.status());assertFalse(failure.toString().contains("EXAMPLE_ONLY_SECRET"));
        assertFalse(new AdminSecretResult(Map.of("accessToken","EXAMPLE_ONLY_SECRET")).toString().contains("EXAMPLE_ONLY_SECRET"));
    }
    @Test void scopeValidatesDisjointModesAndSortsLargeStringIdsNumerically() {
        assertEquals(List.of("2","10","9007199254740993"),new AdminDataScope("MERCHANT",List.of(),List.of("10","9007199254740993","2")).merchantIds());
        assertThrows(IllegalArgumentException.class,()->new AdminDataScope("ALL",List.of("310100"),List.of()));
        assertThrows(IllegalArgumentException.class,()->new AdminDataScope("MERCHANT",List.of(),List.of()));
        assertThrows(IllegalArgumentException.class,()->new AdminDataScope("MERCHANT",List.of(),List.of("2","2")));
        assertThrows(IllegalArgumentException.class,()->new AdminDataScope("MERCHANT",List.of(),List.of("01")));
        assertEquals("NONE",new AdminDataScope("NONE",List.of(),List.of()).mode());
    }
    @Test void productionActionCatalogOnlyGrantsActionsWithImplementedEnforcementPoints() {
        assertEquals(
        Set.of(
            "merchant.application.read", "merchant.application.decide", "merchant.identity.reveal"),
        AdminPermissionEvaluator.DEPLOYED_ACTIONS);
    assertEquals(AdminPermissionEvaluator.DEPLOYED_ACTIONS.stream().sorted().toList(),AdminPermissionEvaluator.evaluate(true,List.of("refund.retry"),AdminPermissionEvaluator.DEPLOYED_ACTIONS));
        assertEquals(List.of(),AdminPermissionEvaluator.evaluate(false,List.of("refund.retry"),AdminPermissionEvaluator.DEPLOYED_ACTIONS));
        // Pure template behavior; this test does not register a production action.
        assertEquals(List.of("test.read"),AdminPermissionEvaluator.evaluate(false,List.of("test.read","test.read","unknown"),Set.of("test.read")));
    }
    private AdminAuditSink.Entry entry(long id,String reason) {return new AdminAuditSink.Entry(id,7L,null,"AUTH_LOGIN",9L,"ALLOWED",reason,Instant.parse("2026-09-14T12:00:00Z"));}
    @Test void durableAuditRetrySameIdDoesNotAppendTwiceEvenAfterReopen() throws Exception {
        Path file=directory.resolve("audit.bin");var sink=new FileAdminAuditSink(file);var entry=entry(1,"Test login");
        sink.append(entry);byte[] first=Files.readAllBytes(file);new FileAdminAuditSink(file).append(entry);
        assertArrayEquals(first,Files.readAllBytes(file));
        assertThrows(AdminAuthFailure.class,()->sink.append(entry(1,"Different payload")));
        assertArrayEquals(first,Files.readAllBytes(file));
    }
    @Test void auditDetectsTruncationAndTamperingInsteadOfOverwritingHistory() throws Exception {
        Path file=directory.resolve("audit.bin");var sink=new FileAdminAuditSink(file);sink.append(entry(1,"First"));
        byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThrows(AdminAuthFailure.class,()->sink.append(entry(2,"Second")));assertArrayEquals(bytes,Files.readAllBytes(file));
        Files.write(file,Arrays.copyOf(bytes,7));assertThrows(AdminAuthFailure.class,()->sink.append(entry(2,"Second")));
    }
    @Test void deletedAuditFileCannotBeRecreatedAsAnEmptyHistory() throws Exception {
        Path file=directory.resolve("deleted.bin");new FileAdminAuditSink(file).append(entry(1,"First"));
        Files.delete(file);
        assertThrows(AdminAuthFailure.class,()->new FileAdminAuditSink(file).append(entry(2,"Second")));
        assertTrue(!Files.exists(file)||Files.size(file)==0,"No new frame may hide lost history");
    }
    @Test void clearedAuditFileFailsAgainstDurableCheckpoint() throws Exception {
        Path file=directory.resolve("cleared.bin");new FileAdminAuditSink(file).append(entry(1,"First"));
        Files.write(file,new byte[0]);
        assertThrows(AdminAuthFailure.class,()->new FileAdminAuditSink(file).append(entry(2,"Second")));
        assertEquals(0,Files.size(file));
    }
    @Test void truncatingAtAnOtherwiseValidFrameBoundaryStillFails() throws Exception {
        Path file=directory.resolve("boundary.bin");var sink=new FileAdminAuditSink(file);sink.append(entry(1,"First"));
        byte[] firstFrame=Files.readAllBytes(file);sink.append(entry(2,"Second"));
        Files.write(file,firstFrame);
        assertThrows(AdminAuthFailure.class,()->new FileAdminAuditSink(file).append(entry(3,"Third")));
        assertArrayEquals(firstFrame,Files.readAllBytes(file));
    }
    @Test void missingOrCorruptCheckpointCannotAcknowledgeExistingHistory() throws Exception {
        for(boolean missing:List.of(false,true)){
            Path file=directory.resolve("checkpoint-"+missing+".bin");new FileAdminAuditSink(file).append(entry(1,"First"));
            byte[] log=Files.readAllBytes(file);Path checkpoint=file.resolveSibling(file.getFileName()+".checkpoint");
            assertTrue(Files.size(checkpoint)>0);
            if(missing)Files.delete(checkpoint);else{byte[] bytes=Files.readAllBytes(checkpoint);bytes[bytes.length-1]^=1;Files.write(checkpoint,bytes);}
            assertThrows(AdminAuthFailure.class,()->new FileAdminAuditSink(file).append(entry(1,"First")));
            assertArrayEquals(log,Files.readAllBytes(file));
        }
    }
}
