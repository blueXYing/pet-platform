package com.petplatform.payment.biz.settlement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.payment.biz.task.ReceiverBindQueryTask;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskLease;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Offline unit tests for {@link ReceiverSensitiveCipher} and {@link ReceiverBindQueryTask}. */
class ReceiverSensitiveCipherTest {

    @Test
    void aes256GcmUsesFreshIvBindsMerchantFieldAadAndFailsClosedOnTamper() {
        byte[] rawKey1 = new byte[32];
        byte[] rawKey2 = new byte[32];
        new SecureRandom().nextBytes(rawKey1);
        new SecureRandom().nextBytes(rawKey2);
        var cipher1 = new ReceiverSensitiveCipher(new SecretKeySpec(rawKey1, "AES"));
        var cipher2 = new ReceiverSensitiveCipher(new SecretKeySpec(rawKey2, "AES"));

        String card = "6222021234567894837";
        String ct1 = cipher1.encrypt(5001L, "acct_no", card);
        String ct2 = cipher1.encrypt(5001L, "acct_no", card);

        assertTrue(ct1.startsWith("v1:"));
        assertNotEquals(ct1, ct2, "fresh 12-byte IV must produce distinct ciphertexts");
        assertFalse(ct1.contains("6222"));
        assertFalse(ct1.contains("4837"));
        assertEquals(card, cipher1.decrypt(5001L, "acct_no", ct1));
        assertEquals(card, cipher1.decrypt(5001L, "acct_no", ct2));
        assertNull(cipher1.encrypt(5001L, "license_no", null));
        assertNull(cipher1.decrypt(5001L, "license_no", null));

        // Wrong merchantId (AAD mismatch) -> fail closed
        ApiException wrongMerchant = assertThrows(ApiException.class,
                () -> cipher1.decrypt(5002L, "acct_no", ct1));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, wrongMerchant.code());

        // Wrong fieldName (AAD mismatch) -> fail closed
        ApiException wrongField = assertThrows(ApiException.class,
                () -> cipher1.decrypt(5001L, "contact_mobile", ct1));
        assertEquals(CommonApiCodes.DEPENDENCY_UNAVAILABLE, wrongField.code());

        // Wrong key -> fail closed
        assertThrows(ApiException.class, () -> cipher2.decrypt(5001L, "acct_no", ct1));

        // Tampered ciphertext byte -> fail closed
        byte[] bytes = Base64.getDecoder().decode(ct1.substring("v1:".length()));
        bytes[bytes.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(bytes);
        assertThrows(ApiException.class, () -> cipher1.decrypt(5001L, "acct_no", tampered));
    }

    @Test
    void loadKeyFromFileAcceptsValid32ByteBase64AndRejectsInvalid(@TempDir Path dir) throws Exception {
        byte[] key32 = new byte[32];
        new SecureRandom().nextBytes(key32);
        Path valid = dir.resolve("receiver.key");
        Files.writeString(valid, Base64.getEncoder().encodeToString(key32) + "\n", StandardCharsets.US_ASCII);

        var cipher = ReceiverSensitiveCipher.fromKeyFile(valid);
        assertEquals("13812345678", cipher.decrypt(1L, "contact_mobile",
                cipher.encrypt(1L, "contact_mobile", "13812345678")));

        Path shortKey = dir.resolve("short.key");
        Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]), StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> ReceiverSensitiveCipher.loadKeyFromFile(shortKey));
        assertThrows(IllegalArgumentException.class, () -> ReceiverSensitiveCipher.loadKeyFromFile(dir.resolve("missing.key")));
    }

    @Test
    void deterministicMaskingHelpersMatchSecurityRules() {
        assertEquals("6222****4837", ReceiverSensitiveCipher.maskCard("6222021234567894837"));
        assertEquals("4837", ReceiverSensitiveCipher.last4("6222021234567894837"));
        assertEquals("310****1234", ReceiverSensitiveCipher.maskIdCard("310101199001011234"));
        assertEquals("138****5678", ReceiverSensitiveCipher.maskPhone("13812345678"));
        assertEquals("9131****001X", ReceiverSensitiveCipher.maskLicense("91310000MA1FL0001X"));
        assertEquals("*三", ReceiverSensitiveCipher.maskName("张三"));
        assertEquals("王*明", ReceiverSensitiveCipher.maskName("王小明"));
        assertEquals("上海****公司", ReceiverSensitiveCipher.maskName("上海萌宠科技有限公司"));
        assertNull(ReceiverSensitiveCipher.maskLicense(null));
    }

    @Test
    void bindQueryTaskKeyPayloadBackoffAndDecode() {
        assertEquals("RECEIVER_BIND_QUERY:5001:548099616395186176",
                ReceiverBindQueryTask.taskKey(5001L, "548099616395186176"));
        assertEquals(List.of(
                Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                Duration.ofHours(1), Duration.ofHours(6)), ReceiverBindQueryTask.DELAYS);
        assertEquals(Duration.ofMinutes(1), ReceiverBindQueryTask.delay(0));
        assertEquals(Duration.ofMinutes(5), ReceiverBindQueryTask.delay(1));
        assertEquals(Duration.ofMinutes(15), ReceiverBindQueryTask.delay(2));
        assertEquals(Duration.ofHours(1), ReceiverBindQueryTask.delay(3));
        assertEquals(Duration.ofHours(6), ReceiverBindQueryTask.delay(4));
        assertEquals(Duration.ofHours(6), ReceiverBindQueryTask.delay(10));

        TaskLease lease = new TaskLease(
                10L, "RECEIVER_BIND_QUERY:5001:548099616395186176",
                ReceiverBindQueryTask.TASK_TYPE, 5001L, null,
                ReceiverBindQueryTask.payload(5001L, "548099616395186176"), "owner",
                0L, 11L, 1, 2, ReceiverBindQueryTask.MAX_RETRY_COUNT,
                ReceiverBindQueryTask.RETRY_POLICY);
        var payload = ReceiverBindQueryTask.decode(lease);
        assertEquals(5001L, payload.merchantId());
        assertEquals("548099616395186176", payload.applyId());
        assertEquals(2, payload.retryCount());

        assertTrue(ReceiverBindQueryTask.map(ReceiverBindQueryTask.Outcome.DONE, 0) instanceof TaskExecutionResult.Success);
        assertTrue(ReceiverBindQueryTask.map(ReceiverBindQueryTask.Outcome.PENDING, 0) instanceof TaskExecutionResult.Retry);
        assertTrue(ReceiverBindQueryTask.map(ReceiverBindQueryTask.Outcome.PENDING,
                ReceiverBindQueryTask.MAX_RETRY_COUNT - 1) instanceof TaskExecutionResult.Dead);
        assertTrue(ReceiverBindQueryTask.map(ReceiverBindQueryTask.Outcome.NOT_FOUND, 0) instanceof TaskExecutionResult.Cancelled);
    }
}
