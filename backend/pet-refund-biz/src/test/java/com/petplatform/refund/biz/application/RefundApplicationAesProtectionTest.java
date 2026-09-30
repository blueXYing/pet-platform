package com.petplatform.refund.biz.application;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class RefundApplicationAesProtectionTest {
    @Test void authenticatedReasonCannotMoveAcrossApplicationsOrBeModified() {
        var protection=new RefundApplicationAesProtection(new byte[32]);
        byte[] reason="商家拒绝说明 🔒".getBytes(StandardCharsets.UTF_8);
        byte[] first=protection.protect("DECISION:101",reason);
        byte[] second=protection.protect("DECISION:101",reason);
        assertFalse(Arrays.equals(first,second));
        assertArrayEquals(reason,protection.reveal("DECISION:101",first));
        assertThrows(IllegalStateException.class,()->protection.reveal("DECISION:102",first));
        first[first.length-1]^=1;
        assertThrows(IllegalStateException.class,()->protection.reveal("DECISION:101",first));
    }
    @Test void originalCommandAndReceiptUseSeparateAuthenticatedPurposes() {
        var protection=new RefundApplicationAesProtection(new byte[32]);
        byte[] command=protection.protect("REFUND_COMMAND:apply:user:1",new byte[]{1,2,3});
        assertThrows(IllegalStateException.class,()->protection.reveal("REFUND_COMMAND:apply:user:1:RESULT",command));
        assertThrows(IllegalStateException.class,()->protection.reveal("REFUND_COMMAND:apply:user:1",Arrays.copyOf(command,27)));
        assertThrows(IllegalArgumentException.class,()->new RefundApplicationAesProtection(new byte[16]));
    }
}
