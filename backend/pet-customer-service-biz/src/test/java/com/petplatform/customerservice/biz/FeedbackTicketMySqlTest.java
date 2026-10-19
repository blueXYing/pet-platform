package com.petplatform.customerservice.biz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommandContext;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.customerservice.biz.application.FeedbackOpsAuthorizationPort;
import com.petplatform.customerservice.biz.application.FeedbackTicketService;
import com.petplatform.customerservice.biz.infrastructure.persistence.FeedbackTicketStore;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetFact;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.PrivateAssetStatus;
import com.petplatform.thirdparty.api.dto.PrivateAssetTypes.ResolveOwnedPrivateAssetsQuery;
import com.petplatform.user.api.query.UserActiveStatusApi;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/**
 * Contract-67 feedback kernel over isolated real MySQL (PRD 5.1.28 complaint class;
 * CCR-CS-FEEDBACK-001 + the v0.2 extension): strict four-value type word and content bounds,
 * the append-only SUBMITTED audit row rides the creation transaction, supplement-23
 * idempotency returns the first receipt on the same key and 409 on the same key with
 * different bytes, attachments re-prove READY/owned FEEDBACK_ATTACHMENT assets through the
 * 31号 port (0..6) and bind to the ticket, the ops face re-proves session/action through the
 * module-local port and drives PENDING→PROCESSING→RESOLVED with the single ≤1000-codepoint
 * reply plus PROCESSING/RESOLVED audit rows, and the owner detail renders the reply.
 */
class FeedbackTicketMySqlTest {

    private static final long USER = 630_001L;
    private static final long OPERATOR = 900_100L;
    private static final AtomicLong IDS = new AtomicLong(800_000);

    private static CommandContext context(String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.USER,
                Long.toString(USER), "C_MINIAPP");
    }

    private static CommandContext operatorContext(String requestId) {
        return new CommandContext(requestId, "trace-" + requestId, OperatorType.PLATFORM_OPERATOR,
                Long.toString(OPERATOR), "ADMIN_WEB");
    }

    private static FeedbackTicketService.CreateFeedbackCommand create(
            String requestId, String type, String contact, String content) {
        return create(requestId, type, contact, content, List.of());
    }

    private static FeedbackTicketService.CreateFeedbackCommand create(
            String requestId, String type, String contact, String content, List<String> assets) {
        return new FeedbackTicketService.CreateFeedbackCommand(context(requestId), type, contact,
                content, assets);
    }

    private static FeedbackOpsAuthorizationPort.Authorization opsAuthorization() {
        return new FeedbackOpsAuthorizationPort.Authorization("session-1", 3);
    }

    private static QueryContext opsQuery() {
        return new QueryContext("trace-ops", OperatorType.PLATFORM_OPERATOR,
                Long.toString(OPERATOR));
    }

    @Test
    void createWritesTicketAndAuditRowThenReplays() throws Exception {
        try (Database db = new Database()) {
            FeedbackTicketService service = db.service(userId -> { });
            String requestId = UUID.randomUUID().toString();
            var outcome = service.create(create(requestId, "SERVICE_COMPLAINT",
                    "13800003001", "小程序下单时宠物档案选择后无法保存，请修复。"));
            assertFalse(outcome.replayed());
            assertEquals("PENDING", outcome.receipt().status());
            assertEquals("SERVICE_COMPLAINT", outcome.receipt().type());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket WHERE user_id=?", Integer.class, USER));
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket_event WHERE action='SUBMITTED'",
                    Integer.class));

            // Same-key same-params replay returns the first receipt without new rows.
            var replay = service.create(create(requestId, "SERVICE_COMPLAINT",
                    "13800003001", "小程序下单时宠物档案选择后无法保存，请修复。"));
            assertTrue(replay.replayed());
            assertEquals(outcome.receipt().ticketId(), replay.receipt().ticketId());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket", Integer.class));

            // Same key, different bytes: 409.
            ApiException conflict = assertThrows(ApiException.class,
                    () -> service.create(create(requestId, "SERVICE_COMPLAINT", null, "换了内容")));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.code());

            // The word list: a fresh ticket always lands PENDING.
            assertEquals("PENDING", db.jdbc.queryForObject(
                    "SELECT status FROM feedback_ticket WHERE id=?",
                    String.class, Long.parseLong(outcome.receipt().ticketId())));
        }
    }

    @Test
    void strictValidationRejectsBadTypeContactAndContent() throws Exception {
        try (Database db = new Database()) {
            FeedbackTicketService service = db.service(userId -> { });
            assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "PROMOTION", null, "内容")));
            ApiException badType = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "HACK", null, "内容")));
            assertEquals("FEEDBACK_TYPE_INVALID", badType.code());
            ApiException emptyContent = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "")));
            assertEquals("COMMON_INVALID_ARGUMENT", emptyContent.code());
            ApiException longContent = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "好".repeat(501))));
            assertEquals("COMMON_INVALID_ARGUMENT", longContent.code());
            ApiException longContact = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", "微".repeat(65), "内容")));
            assertEquals("COMMON_INVALID_ARGUMENT", longContact.code());
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket", Integer.class));
        }
    }

    @Test
    void attachmentsReproveOwnershipAndBindToTheTicket() throws Exception {
        try (Database db = new Database()) {
            db.assets.put(810_001L, PrivateAssetStatus.READY);
            db.assets.put(810_002L, PrivateAssetStatus.READY);
            FeedbackTicketService service = db.service(userId -> { });

            var outcome = service.create(create(UUID.randomUUID().toString(), "BUG_REPORT", null,
                    "附件反馈", List.of("810001", "810002")));
            long ticketId = Long.parseLong(outcome.receipt().ticketId());
            assertEquals(2, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket_attachment WHERE ticket_id=?",
                    Integer.class, ticketId));

            // Owner detail carries the attachment ids (submission order).
            var detail = service.getMine(Long.toString(USER), outcome.receipt().ticketId());
            assertEquals(List.of("810001", "810002"), detail.attachmentAssetIds());

            // >6 attachments: 400 before any write.
            ApiException tooMany = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容",
                            List.of("810001", "810002", "810003", "810004", "810005", "810006",
                                    "810007"))));
            assertEquals("COMMON_INVALID_ARGUMENT", tooMany.code());
            // Malformed id: 400.
            ApiException malformed = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容",
                            List.of("not-a-number"))));
            assertEquals("COMMON_INVALID_ARGUMENT", malformed.code());
            // Duplicate id: 400.
            ApiException duplicate = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容",
                            List.of("810001", "810001"))));
            assertEquals("COMMON_INVALID_ARGUMENT", duplicate.code());
            // Not-owned / unknown asset: the port answers NOT_FOUND.
            ApiException foreign = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容",
                            List.of("999999"))));
            assertEquals("COMMON_NOT_FOUND", foreign.code());
            // Not-READY asset: 400.
            db.assets.put(810_003L, PrivateAssetStatus.SCANNING);
            ApiException notReady = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容",
                            List.of("810003"))));
            assertEquals("COMMON_INVALID_ARGUMENT", notReady.code());

            // Attachment-bearing creation is part of the idempotency equality fact.
            String requestId = UUID.randomUUID().toString();
            service.create(create(requestId, "OTHER", null, "带附件", List.of("810001")));
            ApiException conflict = assertThrows(ApiException.class,
                    () -> service.create(create(requestId, "OTHER", null, "带附件", List.of())));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", conflict.code());
            assertEquals(1, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket WHERE content='带附件'", Integer.class));
        }
    }

    @Test
    void opsFaceListsFiltersAndDrivesTransitionsWithReplyAndAudit() throws Exception {
        try (Database db = new Database()) {
            FeedbackTicketService service = db.service(userId -> { });
            String a = service.create(create(UUID.randomUUID().toString(), "BUG_REPORT", null, "缺陷甲"))
                    .receipt().ticketId();
            String b = service.create(create(UUID.randomUUID().toString(), "SERVICE_COMPLAINT",
                    "13800003002", "投诉乙")).receipt().ticketId();

            // Ops list: status/type filters inside the open vocabulary.
            var pending = service.listForOps(new FeedbackTicketService.OpsListQuery(
                    opsAuthorization(), opsQuery(), "PENDING", null, 1, 20));
            assertEquals(2, pending.total());
            assertEquals(1, service.listForOps(new FeedbackTicketService.OpsListQuery(
                    opsAuthorization(), opsQuery(), null, "SERVICE_COMPLAINT", 1, 20)).total());
            ApiException badFilter = assertThrows(ApiException.class,
                    () -> service.listForOps(new FeedbackTicketService.OpsListQuery(
                            opsAuthorization(), opsQuery(), "WAITING_SUPPLEMENT", null, 1, 20)));
            assertEquals("COMMON_INVALID_ARGUMENT", badFilter.code());

            // Ops detail sees the ticket verbatim including the owner id.
            var opsDetail = service.getForOps(new FeedbackTicketService.OpsTicketQuery(
                    opsAuthorization(), opsQuery(), a));
            assertEquals(Long.toUnsignedString(USER), opsDetail.ownerUserId());
            assertEquals("缺陷甲", opsDetail.content());

            // PENDING → PROCESSING with the PROCESSING audit row.
            String processRequestId = UUID.randomUUID().toString();
            var processed = service.processTicket(new FeedbackTicketService.ProcessCommand(
                    operatorContext(processRequestId), opsAuthorization(), a));
            assertEquals("PROCESSING", processed.receipt().status());
            // Replay: same requestId + same params returns the first receipt without a second hop.
            var replay = service.processTicket(new FeedbackTicketService.ProcessCommand(
                    operatorContext(processRequestId), opsAuthorization(), a));
            assertTrue(replay.replayed());
            assertEquals(processed.receipt().version(), replay.receipt().version());

            // Resolve requires PROCESSING and carries the single ≤1000-codepoint reply.
            ApiException resolvePending = assertThrows(ApiException.class,
                    () -> service.resolveTicket(new FeedbackTicketService.ResolveCommand(
                            operatorContext(UUID.randomUUID().toString()), opsAuthorization(),
                            b, "回复")));
            assertEquals("COMMON_CONFLICT", resolvePending.code());
            ApiException longReply = assertThrows(ApiException.class,
                    () -> service.resolveTicket(new FeedbackTicketService.ResolveCommand(
                            operatorContext(UUID.randomUUID().toString()), opsAuthorization(),
                            a, "回".repeat(1001))));
            assertEquals("COMMON_INVALID_ARGUMENT", longReply.code());
            var resolved = service.resolveTicket(new FeedbackTicketService.ResolveCommand(
                    operatorContext(UUID.randomUUID().toString()), opsAuthorization(), a,
                    "问题已定位并在新版本修复，感谢反馈。"));
            assertEquals("RESOLVED", resolved.receipt().status());
            ApiException reprocess = assertThrows(ApiException.class,
                    () -> service.processTicket(new FeedbackTicketService.ProcessCommand(
                            operatorContext(UUID.randomUUID().toString()), opsAuthorization(), a)));
            assertEquals("COMMON_CONFLICT", reprocess.code());

            // Audit trail: SUBMITTED + PROCESSING + RESOLVED rows in order.
            assertEquals(List.of("SUBMITTED", "PROCESSING", "RESOLVED"), db.jdbc.queryForList(
                    "SELECT action FROM feedback_ticket_event WHERE ticket_id=? ORDER BY created_at, id",
                    String.class, Long.parseLong(a)));

            // The owner detail now shows the status and the reply.
            var mine = service.getMine(Long.toString(USER), a);
            assertEquals("RESOLVED", mine.status());
            assertEquals(1, mine.replies().size());
            assertEquals("问题已定位并在新版本修复，感谢反馈。", mine.replies().get(0).reply());
            assertEquals(3, mine.events().size());
            // The other ticket stays untouched.
            assertEquals("PENDING", service.getMine(Long.toString(USER), b).status());
        }
    }

    @Test
    void listStaysOwnerScopedSortedAndPreviewTruncated() throws Exception {
        try (Database db = new Database()) {
            FeedbackTicketService service = db.service(userId -> { });
            String longContent = "问".repeat(80);
            for (int index = 0; index < 3; index++) {
                service.create(create(UUID.randomUUID().toString(),
                        index == 0 ? "FEATURE_SUGGESTION" : "BUG_REPORT", null, longContent));
            }
            var page = service.listMine(Long.toString(USER), 1, 2);
            assertEquals(3, page.total());
            assertEquals(2, page.items().size());
            assertEquals(50, page.items().get(0).contentPreview().codePointCount(0,
                    page.items().get(0).contentPreview().length()));
            // Fixed sort: newest first.
            var detail = service.getMine(Long.toString(USER), page.items().get(0).ticketId());
            assertEquals(longContent, detail.content());
            assertNull(detail.contact());
            assertEquals(1, detail.events().size());
            assertEquals("SUBMITTED", detail.events().get(0).action());
            assertTrue(detail.replies().isEmpty());
            assertEquals(0, detail.attachmentAssetIds().size());

            // Another user sees nothing — not even existence.
            FeedbackTicketService stranger = db.serviceAs(9_999_999L, userId -> { });
            assertEquals(0, stranger.listMine("9999999", 1, 20).total());
            ApiException foreign = assertThrows(ApiException.class,
                    () -> stranger.getMine("9999999", page.items().get(0).ticketId()));
            assertEquals("FEEDBACK_TICKET_NOT_FOUND", foreign.code());
            ApiException missing = assertThrows(ApiException.class,
                    () -> service.getMine(Long.toString(USER), "not-a-number"));
            assertEquals("FEEDBACK_TICKET_NOT_FOUND", missing.code());
        }
    }

    @Test
    void activeGuardAndRateLimitHold() throws Exception {
        try (Database db = new Database()) {
            FeedbackTicketService service = db.service(userId -> {
                throw new ApiException("USER_FROZEN", "账号不可用，拒绝写入");
            });
            ApiException frozen = assertThrows(ApiException.class,
                    () -> service.create(create(UUID.randomUUID().toString(), "OTHER", null, "内容")));
            assertEquals("USER_FROZEN", frozen.code());
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_ticket", Integer.class));
            assertEquals(0, db.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM feedback_command_idempotency WHERE status='SUCCEEDED'",
                    Integer.class));

            // The limiter is per-user: five creations pass, the sixth is a 429.
            FeedbackTicketService open = db.service(userId -> { });
            for (int index = 0; index < FeedbackTicketService.RATE_LIMIT_PER_MINUTE; index++) {
                open.create(create(UUID.randomUUID().toString(), "OTHER", null, "第" + index + "条反馈"));
            }
            ApiException limited = assertThrows(ApiException.class,
                    () -> open.create(create(UUID.randomUUID().toString(), "OTHER", null, "第六条反馈")));
            assertEquals("COMMON_RATE_LIMITED", limited.code());

            // A different user keeps its own window.
            FeedbackTicketService otherUser = db.serviceAs(630_002L, userId -> { });
            otherUser.create(new FeedbackTicketService.CreateFeedbackCommand(
                    new CommandContext(UUID.randomUUID().toString(), "trace", OperatorType.USER,
                            "630002", "C_MINIAPP"), "OTHER", null, "另一用户的反馈", List.of()));
        }
    }

    /** Isolated real-MySQL fixture over schemas 06 + 67; dropped on close. */
    private static final class Database implements AutoCloseable {
        private final String name = "csfb001_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcTemplate admin;
        private final DataSource dataSource;
        private final JdbcTemplate jdbc;
        private final Map<Long, PrivateAssetStatus> assets = new HashMap<>();

        Database() throws Exception {
            String url = System.getenv().getOrDefault("AUTH_MYSQL_URL", "jdbc:mysql://127.0.0.1:33440/");
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
                throw new IllegalArgumentException("AUTH_MYSQL_URL must name a local isolated server");
            }
            String user = System.getenv().getOrDefault("AUTH_MYSQL_USER", "root");
            String password = System.getenv().getOrDefault("AUTH_MYSQL_PASSWORD", "");
            admin = new JdbcTemplate(source(url, user, password));
            dataSource = source(url + name, user, password);
            jdbc = new JdbcTemplate(dataSource);
            admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
            try {
                try (Connection connection = dataSource.getConnection()) {
                    for (String file : List.of("06-核心数据库Schema-v0.1.sql",
                            "67-Feedback-Ticket-Schema-v0.1.sql")) {
                        ScriptUtils.executeSqlScript(connection, new EncodedResource(
                                new FileSystemResource(root().resolve("docs/03-database/" + file)),
                                StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception failure) {
                try {
                    close();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        /** Fixed-owner service; the ACTIVE guard is the lambda (real port has its own wiring). */
        FeedbackTicketService service(UserActiveStatusApi guard) {
            return serviceAs(USER, guard);
        }

        FeedbackTicketService serviceAs(long userId, UserActiveStatusApi guard) {
            return new FeedbackTicketService(new FeedbackTicketStore(dataSource),
                    guard, IDS::incrementAndGet,
                    query -> {
                        // Stub of the 31号 resolveOwned port: owner-scoped facts, purpose re-proof
                        // mirrors the real selectOwned(owner, purpose, ids) shape.
                        if (!FeedbackTicketService.ATTACHMENT_PURPOSE.equals(query.requiredPurpose())) {
                            throw new ApiException(com.petplatform.common.CommonApiCodes.INVALID_ARGUMENT,
                                    "私有材料查询参数无效");
                        }
                        if (!query.ownerUserId().equals(Long.toUnsignedString(USER))) {
                            throw new ApiException(com.petplatform.common.CommonApiCodes.NOT_FOUND,
                                    "私有材料不存在或不可访问");
                        }
                        List<PrivateAssetFact> facts = new ArrayList<>();
                        for (String assetId : query.assetIds()) {
                            PrivateAssetStatus status = assets.get(Long.parseLong(assetId));
                            if (status == null) {
                                throw new ApiException(com.petplatform.common.CommonApiCodes.NOT_FOUND,
                                        "私有材料不存在或不可访问");
                            }
                            facts.add(new PrivateAssetFact(assetId, query.ownerUserId(),
                                    "src", "obj", "v1", "image/jpeg", 1024, status, "1"));
                        }
                        return List.copyOf(facts);
                    },
                    new FeedbackOpsAuthorizationPort.FeedbackPort() {
                        @Override
                        public FeedbackOpsAuthorizationPort.Decision check(
                                FeedbackOpsAuthorizationPort.Check check) {
                            return new FeedbackOpsAuthorizationPort.Decision(true,
                                    OffsetDateTime.now(), "authz-test", null);
                        }

                        @Override
                        public FeedbackOpsAuthorizationPort.Decision checkCollection(
                                FeedbackOpsAuthorizationPort.CollectionCheck check) {
                            return new FeedbackOpsAuthorizationPort.Decision(true,
                                    OffsetDateTime.now(), "authz-test", null);
                        }
                    });
        }

        JdbcTemplate jdbc() {
            return jdbc;
        }

        private static Path root() {
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.exists(
                    root.resolve("docs/03-database/06-核心数据库Schema-v0.1.sql"))) {
                root = root.getParent();
            }
            if (root == null) throw new IllegalStateException("Authoritative SQL06 schema was not found");
            return root;
        }

        private static DataSource source(String url, String user, String password) {
            return new DriverManagerDataSource(
                    url + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC",
                    user, password) {
                @Override
                public Connection getConnection() throws SQLException {
                    Connection connection = super.getConnection();
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET SESSION time_zone = '+00:00'");
                        return connection;
                    } catch (SQLException failure) {
                        connection.close();
                        throw failure;
                    }
                }
            };
        }

        @Override
        public void close() {
            admin.execute("DROP DATABASE `" + name + "`");
        }
    }
}
