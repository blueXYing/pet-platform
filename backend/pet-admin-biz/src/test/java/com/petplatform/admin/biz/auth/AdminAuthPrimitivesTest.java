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
            "merchant.application.read", "merchant.application.decide", "merchant.identity.reveal",
            // ADM-001 service write slice (CCR-W2-API-001): the three service review codes now
            // have server-side enforcement points; force-offline uses the AUTH-lexicon spelling.
            "service.review.read", "service.review.decide", "service.force.offline",
            "aftersale.read", "aftersale.handle", "aftersale.decide",
            // REV-002 review appeal slice (contract 56): both enforcement points ship in it.
            "review.appeal.read", "review.appeal.decide",
            // Contract-58 earn slice (CCR-POINTS-TASK-001, 32号 ruling): the config read/write
            // enforcement points ship in the same slice.
            "points.task.config.read", "points.task.config.write",
            // Contract-63 platform trade read slice (ADM-002/A-003, AUTH-001 matrix rows 7/8):
            // GET-only query enforcement points ship in the same slice.
            "order.read", "order.status-log.read", "order.verification-log.read", "refund.read",
            // Contract-64 platform governance read slice (ADM-001 gap slice): merchant.read
            // (matrix row 3) + schedule.read/merchant.staff.read (PRD 6.2.5/6.2.6 read-only
            // supervision, SSOT §29/§30); GET-only, no governance write action exists.
            "merchant.read", "schedule.read", "merchant.staff.read",
            // Contract-63 v0.2 disposal face (33号 ruling, 2026-10-15, AUTH-001 matrix rows 4/7/8):
            // the four disposal write enforcement points ship in the same face; proposal spellings
            // "refund.channel-query"/"order.abnormal-close" deploy as dotted segments.
            "refund.channel.query", "refund.retry", "order.abnormal.close", "merchant.disable",
            // Contract-65 ops console slice (A-005, matrix rows 21/13/14/15/23): the ten
            // encyclopedia/coupon/points-ledger/settings enforcement points ship in that face;
            // row 14 return and row 23 configure stay unregistered (CCR-ADM-A005-OPS-001).
            // The grant channel adds coupon.instance.grant (user ruling 2026-10-16,
            // CCR-CPN-GRANT-001); the void channel adds coupon.instance.void (user ruling
            // 2026-10-16 doc 36, CCR-CPN-VOID-001); batch void/return stay unregistered.
            "encyclopedia.read", "encyclopedia.manage", "encyclopedia.publish",
            "breed.read", "breed.manage",
            "coupon.template.read", "coupon.template.manage", "coupon.instance.read",
            "coupon.instance.grant", "coupon.instance.void",
            "points.ledger.read", "system.parameter.read",
            // Contract-67 v0.2 feedback ops face (user ruling 2026-10-19 "继续扩展CCR", matrix
            // row 18): the three complaint enforcement points ship in that face (C-side
            // controller snapshot gate + AdminFeedbackOpsController + kernel port re-proof);
            // row 18's complaint.assign stays unregistered — no dispatch in V1.
            "complaint.read", "complaint.handle", "complaint.decide",
            "community.post.read", "community.post.moderate", "community.report.read",
            "community.report.handle",
            "marketing.campaign.read", "marketing.campaign.manage", "marketing.campaign.publish",
            "marketing.placement.read", "marketing.placement.manage",
            "marketing.enrollment.read", "marketing.enrollment.review",
            "coupon.campaign.read", "coupon.campaign.manage", "coupon.campaign.publish"),
        AdminPermissionEvaluator.DEPLOYED_ACTIONS);
    assertEquals(AdminPermissionEvaluator.DEPLOYED_ACTIONS.stream().sorted().toList(),AdminPermissionEvaluator.evaluate(true,List.of("refund.retry"),AdminPermissionEvaluator.DEPLOYED_ACTIONS));
        // A proposed-but-undeployed code (merchant.freeze: AUTH-001 matrix row, explicitly not
        // implemented in V1) grants nothing to a non-super-admin even when granted.
        assertEquals(List.of(),AdminPermissionEvaluator.evaluate(false,List.of("merchant.freeze"),AdminPermissionEvaluator.DEPLOYED_ACTIONS));
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
