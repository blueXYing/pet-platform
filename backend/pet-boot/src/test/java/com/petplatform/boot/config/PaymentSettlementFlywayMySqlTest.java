package com.petplatform.boot.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.order.api.dto.OrderSettlementFact;
import com.petplatform.order.biz.apiimpl.OrderSettlementFactsApiImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Verifies Flyway {@code V38__payment_settlement.sql} execution via {@link PaymentSettlementConfiguration}
 * and {@link OrderSettlementFactsApiImpl} against a real isolated MySQL 8 {@code paysettle_*} database.
 */
class PaymentSettlementFlywayMySqlTest {

    @Test
    void flywayV38MigratesCleanlyAndOrderSettlementFactsReadsFlashPriceSnapshot() throws Exception {
        String url = System.getenv("PAYMENT_CENTER_MYSQL_URL");
        if (url == null || url.isBlank()) url = System.getenv("PAYMENT_REFUND_MYSQL_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "MySQL URL env not set");
        String user = env("PAYMENT_CENTER_MYSQL_USER", env("PAYMENT_REFUND_MYSQL_USER", "root"));
        String pass = env("PAYMENT_CENTER_MYSQL_PASSWORD", env("PAYMENT_REFUND_MYSQL_PASSWORD", "root"));
        String normalized = url.endsWith("/") ? url : url + "/";
        String dbName = "paysettle_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        MysqlDataSource admin = new MysqlDataSource();
        admin.setURL(normalized + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        admin.setUser(user);
        admin.setPassword(pass);
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + dbName + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        try {
            MysqlDataSource ds = new MysqlDataSource();
            ds.setURL(normalized + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
            ds.setUser(user);
            ds.setPassword(pass);

            // 1. Run isolated Flyway migration V38..V43 through PaymentSettlementConfiguration.
            var cfg = new PaymentSettlementConfiguration();
            cfg.settlementMigration(ds, new PaymentSettlementConfiguration.SettlementSettings(
                    "0", null, null, true, dbName, null));
            // V38..V43 contiguous settlement, fallback, receiver, wallet, reconcile, and acquiring tables (12 tables).
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                         + dbName + "' AND table_name IN ('settlement_config','settlement_config_audit',"
                         + "'settlement_order','settlement_instruction','settlement_instruction_receipt',"
                         + "'settlement_action_request','settlement_receiver_account','settlement_receiver_audit',"
                         + "'withdrawal_order','withdrawal_receipt','reconcile_job_run','reconcile_diff')")) {
                rs.next();
                assertEquals(12, rs.getInt(1));
            }
            // V39 fallback linkage columns on settlement_instruction.
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                         + dbName + "' AND table_name='settlement_instruction' AND column_name IN "
                         + "('refund_order_id','origin_instruction_id','fallback_reason')")) {
                rs.next();
                assertEquals(3, rs.getInt(1));
            }
            // V43 Mode A acquiring columns on settlement_config and settlement_config_audit.
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                         + dbName + "' AND table_name='settlement_config' AND column_name IN "
                         + "('lakala_merchant_no','lakala_term_no')")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='"
                         + dbName + "' AND table_name='settlement_config_audit' AND column_name IN "
                         + "('before_lakala_merchant_no','after_lakala_merchant_no',"
                         + "'before_lakala_term_no','after_lakala_term_no')")) {
                rs.next();
                assertEquals(4, rs.getInt(1));
            }

            // 2. Apply SQL06 and verify OrderSettlementFactsApiImpl reads original_price/flash_discount/discount_amount.
            Path sql06 = repoRoot().resolve("docs/03-database/06-核心数据库Schema-v0.1.sql");
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new EncodedResource(new FileSystemResource(sql06), StandardCharsets.UTF_8));
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,"
                            + "reservation_id,order_stage,payment_status,verification_status,fulfillment_type,"
                            + "original_amount,original_price,flash_discount,price_source,discount_amount,pay_amount,"
                            + "refunded_amount,appointment_start_at,appointment_end_at,verified_at,created_at,updated_at) "
                            + "VALUES(9001,900101,1001,5001,6001,7001,8001,3001,'COMPLETED','PAID','VERIFIED','IN_STORE',"
                            + "80.00,100.00,20.00,'FLASH',10.00,70.00,0.00,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),"
                            + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
                }
            }
            var api = new OrderSettlementFactsApiImpl(ds);
            OrderSettlementFact fact = api.readForSettlement("9001", new QueryContext("t", OperatorType.SYSTEM, null));
            assertEquals("5001", fact.merchantId());
            assertEquals("6001", fact.storeId());
            assertFalse(fact.orderCanceled());
            assertTrue(fact.verified());
            assertEquals(new BigDecimal("100.00"), fact.originalPrice());
            assertEquals(new BigDecimal("20.00"), fact.flashDiscount());
            assertEquals(new BigDecimal("10.00"), fact.discountAmount());
            assertEquals(new BigDecimal("70.00"), fact.payAmount());
        } finally {
            try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + dbName + "`");
            }
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("docs/03-database"))) p = p.getParent();
        return java.util.Objects.requireNonNull(p);
    }
}
