package com.petplatform.boot.booking;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.boot.adapter.web.c.CScheduleController;
import com.petplatform.boot.adapter.web.c.CServiceExceptionHandler;
import com.petplatform.boot.config.BookingExpiryConfiguration;
import com.petplatform.boot.config.CBearerSessionFilter;
import com.petplatform.common.*;
import com.petplatform.coupon.biz.apiimpl.BookingCouponExposureApiImpl;
import com.petplatform.merchant.biz.apiimpl.MerchantCurrentStaffFactsApiImpl;
import com.petplatform.merchant.biz.apiimpl.BookingMerchantFactsApiImpl;
import com.petplatform.merchant.biz.application.PersistentApplicationReviewFactsReader;
import com.petplatform.order.api.command.OrderExpiryApi;
import com.petplatform.order.api.dto.OrderExpiryTypes.*;
import com.petplatform.order.api.dto.OrderCreationTypes.*;
import com.petplatform.order.biz.apiimpl.OrderExpiryApiImpl;
import com.petplatform.order.biz.apiimpl.OrderExpiryFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderProtectionFactsApiImpl;
import com.petplatform.order.biz.apiimpl.OrderCreationApiImpl;
import com.petplatform.payment.biz.apiimpl.BookingPaymentExposureApiImpl;
import com.petplatform.schedule.api.dto.ReservationExpiryTypes.ExpireHoldCommand;
import com.petplatform.schedule.api.query.ScheduleSelectionQueryApi;
import com.petplatform.schedule.biz.apiimpl.*;
import com.petplatform.schedule.biz.application.QualifiedStaffFactsPort;
import com.petplatform.service.biz.apiimpl.BookingServiceFactsApiImpl;
import com.petplatform.service.api.dto.ServiceBookabilityDTO;
import com.petplatform.service.api.dto.ServiceSnapshotDTO;
import com.petplatform.service.api.enums.FulfillmentType;
import com.petplatform.service.biz.apiimpl.ServiceQueryApiImpl;
import com.petplatform.task.core.*;
import com.petplatform.user.biz.application.UserAuthService.MiniSessionView;
import com.petplatform.user.biz.apiimpl.BookingUserFactsApiImpl;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Independent MySQL and servlet-contract checks for the opt-in selection/expiry slice. */
class SelectionExpiryAcceptanceTest {
  private static final long MERCHANT = 8_100_001L;
  private static final long STORE = 8_100_002L;
  private static final long USER = 8_100_003L;
  private static final long PET = 8_100_004L;
  private static final long SERVICE = 8_100_005L;
  private static final long ORDER = 8_100_006L;
  private static final long RESERVATION = 8_100_007L;
  private static final long PICKUP = 8_100_008L;
  private static final long RETURN = 8_100_009L;
  private static final long GAP_PICKUP = 8_100_010L;
  private static final AtomicLong IDS = new AtomicLong(8_100_000_000_000_000L);
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void duePickupClosesOrderAndBothClaimsInOneCommitAndReplaysWithoutDuplicates() throws Exception {
    try (Database db = new Database()) {
      Seed seed = db.seedPickup(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
      OrderExpiryApi expiry = api(db);
      assertEquals(ExpireOrderResult.CLOSED, expiry.expire(command(seed)));
      assertEquals("CANCELED", db.text("SELECT order_stage FROM pet_order WHERE id=?", ORDER));
      assertEquals("EXPIRED", db.text("SELECT status FROM schedule_reservation WHERE id=?", RESERVATION));
      assertEquals(1L, db.number("SELECT version FROM pet_order WHERE id=?", ORDER));
      assertEquals(1L, db.number("SELECT version FROM schedule_reservation WHERE id=?", RESERVATION));
      assertEquals(2L, db.number("SELECT COUNT(*) FROM schedule_reservation_claim WHERE reservation_id=?", RESERVATION));
      assertEquals(1L, db.number("SELECT COUNT(*) FROM order_status_log WHERE order_id=? AND event_type='PAYMENT_TIMEOUT'", ORDER));
      assertEquals(1L, db.number("SELECT COUNT(*) FROM schedule_reservation_audit WHERE reservation_id=? AND action='EXPIRE'", RESERVATION));
      assertEquals("SYSTEM", db.text("SELECT actor_type FROM schedule_reservation_audit WHERE reservation_id=? AND action='EXPIRE'", RESERVATION));
      assertNull(db.jdbc.queryForObject("SELECT actor_user_id FROM schedule_reservation_audit WHERE reservation_id=? AND action='EXPIRE'", Long.class, RESERVATION));
      assertEquals(ExpireOrderResult.NOOP, expiry.expire(command(seed)));
      assertEquals(1L, db.number("SELECT COUNT(*) FROM order_status_log WHERE order_id=? AND event_type='PAYMENT_TIMEOUT'", ORDER));
      assertEquals(1L, db.number("SELECT COUNT(*) FROM schedule_reservation_audit WHERE reservation_id=? AND action='EXPIRE'", RESERVATION));
    }
  }

  @Test
  void realBookingCreateSchedulesOriginalDeadlineAndWorkerClosesItAcrossJvmTimeZones()
      throws Exception {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : List.of("Asia/Shanghai", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try (var db = new BookingCreateAcceptanceTest.Database()) {
          db.seedBookableFacts();
          Clock creationClock = Clock.fixed(Instant.now().minus(11, ChronoUnit.MINUTES)
              .truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
          var guard = new ScheduleCapacityGuardApiImpl(db.source);
          var scheduleFacts = new ScheduleProtectionFactsApiImpl(db.source, guard);
          var staffFacts = new MerchantCurrentStaffFactsApiImpl(db.source, guard);
          var orderFacts = new OrderProtectionFactsApiImpl(db.source, guard,
              scheduleFacts, staffFacts, creationClock);
          var proof = new ScheduleCapacityProofApiImpl(db.source, guard, scheduleFacts,
              staffFacts, orderFacts, creationClock, 10_000);
          var hold = new ReservationHoldApiImpl(db.source, SelectionExpiryAcceptanceTest::id,
              guard, scheduleFacts, proof, orderFacts, creationClock);
          var approval = new PersistentApplicationReviewFactsReader(db.source,
              SelectionExpiryAcceptanceTest::id);
          var create = new OrderCreationApiImpl(db.source, SelectionExpiryAcceptanceTest::id,
              new BookingUserFactsApiImpl(db.source, guard),
              new BookingMerchantFactsApiImpl(db.source, guard, approval),
              new BookingServiceFactsApiImpl(db.source, guard), guard, hold,
              new BookingCreateAcceptanceTest.InputProtector(), null, creationClock);
          OffsetDateTime appointment = OffsetDateTime.parse("2030-01-01T09:00:00Z");
          CreateOrderResult created = create.create(new CreateOrderCommand(
              new CommandContext(UUID.randomUUID().toString(), "create-expiry-qa", OperatorType.USER,
                  "710100", "MINIAPP"), "710302", "710401", "710200", "IN_STORE",
              appointment, appointment.plusMinutes(90), null, null, "710500", null, null,
              null, null, null));
          assertTrue(created.created());
          String order = created.orderId();
          long orderId = Long.parseLong(order);
          long reservationId = db.jdbc.queryForObject(
              "SELECT reservation_id FROM pet_order WHERE id=?", Long.class, orderId);
          String taskKey = "RESERVATION_HOLD_EXPIRE:" + reservationId + ":0";
          String rawExpected = created.paymentExpireAt().withOffsetSameInstant(ZoneOffset.UTC)
              .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'000'"));
          assertEquals(rawExpected, db.jdbc.queryForObject(
              "SELECT DATE_FORMAT(payment_expire_at,'%Y-%m-%d %H:%i:%s.%f') FROM pet_order WHERE id=?",
              String.class, orderId), zone + " order deadline raw UTC");
          assertEquals(rawExpected, db.jdbc.queryForObject(
              "SELECT DATE_FORMAT(lock_expire_at,'%Y-%m-%d %H:%i:%s.%f') FROM schedule_reservation WHERE id=?",
              String.class, reservationId), zone + " reservation deadline raw UTC");
          assertEquals(rawExpected, db.jdbc.queryForObject(
              "SELECT DATE_FORMAT(execute_at,'%Y-%m-%d %H:%i:%s.%f') FROM async_task WHERE task_key=?",
              String.class, taskKey), zone + " task first schedule raw UTC");
          assertEquals(rawExpected, db.jdbc.queryForObject(
              "SELECT DATE_FORMAT(submitted_execute_at,'%Y-%m-%d %H:%i:%s.%f') FROM async_task WHERE task_key=?",
              String.class, taskKey), zone + " immutable first schedule raw UTC");
          assertEquals(1L, db.jdbc.queryForObject(
              "SELECT COUNT(*) FROM async_task WHERE task_key=? AND status='READY'", Long.class,
              taskKey));
          var registration = BookingExpiryConfiguration.registration(api(db.source), Clock.systemUTC());
          try (var worker = AsyncTaskWorker.create(db.source, SelectionExpiryAcceptanceTest::id,
              "qa-expiry-" + zone, Clock.systemUTC(), TaskWorkerSettings.defaults(),
              BookingExpiryConfiguration.retryDelays(), List.of(registration))) {
            assertEquals(AsyncTaskWorker.Outcome.COMPLETED, worker.runOne());
          }
          assertEquals("CANCELED", db.jdbc.queryForObject(
              "SELECT order_stage FROM pet_order WHERE id=?", String.class, orderId));
          assertEquals("EXPIRED", db.jdbc.queryForObject(
              "SELECT status FROM schedule_reservation WHERE id=?", String.class, reservationId));
          assertEquals("SUCCEEDED", db.jdbc.queryForObject(
              "SELECT status FROM async_task WHERE task_key=?", String.class, taskKey));
        }
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void notDueOrUncertainPaymentCouponAndBadLaterStageNeverReleaseClaims() throws Exception {
    try (Database db = new Database()) {
      Seed future = db.seedPickup(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(9));
      OrderExpiryApi expiry = api(db);
      assertEquals(ExpireOrderResult.NOT_DUE, expiry.expire(command(future)));
      db.assertActive();
      db.shiftDeadline(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
      Seed due = db.seed();
      db.jdbc.update("INSERT INTO payment_order(id,payment_no,order_id,amount,status,channel,expire_at,created_at,updated_at)"
              + " VALUES(?,?,?,128.00,'PAYING','LAKALA_WECHAT',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          id(), id(), ORDER, due.deadline().toLocalDateTime());
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> expiry.expire(command(due)));
      db.assertActive();
      db.jdbc.update("DELETE FROM payment_order WHERE order_id=?", ORDER);
      db.jdbc.update("INSERT INTO coupon_instance(id,coupon_template_id,user_id,status,order_id,original_expire_at,expire_at,created_at,updated_at)"
              + " VALUES(?, ?,?,'FROZEN',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          id(), id(), USER, ORDER);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> expiry.expire(command(due)));
      db.assertActive();
      db.jdbc.update("DELETE FROM coupon_instance WHERE order_id=?", ORDER);
      db.jdbc.update("UPDATE pet_order SET order_stage='PENDING_CONFIRM',payment_status='BROKEN' WHERE id=?", ORDER);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> expiry.expire(command(due)));
      assertEquals("TEMP_LOCKED", db.text("SELECT status FROM schedule_reservation WHERE id=?", RESERVATION));
      assertEquals(0L, db.number("SELECT COUNT(*) FROM order_status_log WHERE order_id=? AND event_type='PAYMENT_TIMEOUT'", ORDER));
    }
  }

  @Test
  void auditFailureOrMissingReturnClaimRollsBackCancellationAndExpiry() throws Exception {
    try (Database db = new Database()) {
      Seed seed = db.seedPickup(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
      OrderExpiryApi expiry = api(db);
      db.jdbc.execute("CREATE TRIGGER qa_expiry_fail BEFORE INSERT ON order_status_log FOR EACH ROW "
          + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='QA_EXPIRY_ROLLBACK'");
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> expiry.expire(command(seed)));
      db.assertActive();
      db.jdbc.execute("DROP TRIGGER qa_expiry_fail");
      db.jdbc.update("DELETE FROM schedule_reservation_claim WHERE reservation_id=? AND kind='RETURN'", RESERVATION);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> expiry.expire(command(seed)));
      db.assertActive();
      assertEquals(1L, db.number("SELECT COUNT(*) FROM schedule_reservation_claim WHERE reservation_id=?", RESERVATION));
    }
  }

  @Test
  void standaloneScheduleExpireCannotReuseHistoricalCancellationLog() throws Exception {
    try (Database db = new Database()) {
      Seed seed = db.seedPickup(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(11));
      db.jdbc.update("UPDATE pet_order SET order_stage='CANCELED' WHERE id=?", ORDER);
      db.jdbc.update("INSERT INTO order_status_log(id,order_id,dimension,from_status,to_status,event_type,operator_type,request_id,created_at)"
              + " VALUES(?,?,'ORDER_STAGE','PENDING_PAYMENT','CANCELED','PAYMENT_TIMEOUT','SYSTEM',?,UTC_TIMESTAMP(3))",
          id(), ORDER, requestId());
      var guard = new ScheduleCapacityGuardApiImpl(db.source);
      var facts = new ScheduleProtectionFactsApiImpl(db.source, guard);
      var schedule = new ReservationExpiryApiImpl(db.source, SelectionExpiryAcceptanceTest::id,
          guard, new OrderExpiryFactsApiImpl(db.source, guard), facts);
      assertCode("COMMON_DEPENDENCY_UNAVAILABLE", () -> db.transaction(() -> {
        guard.acquire(List.of(Long.toString(STORE)), new QueryContext("qa", OperatorType.SYSTEM, null));
        schedule.expire(new ExpireHoldCommand(systemContext(), Long.toString(ORDER),
            Long.toString(RESERVATION), Long.toString(STORE), 0, seed.deadline(),
            OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)));
        return null;
      }));
      assertEquals("TEMP_LOCKED", db.text("SELECT status FROM schedule_reservation WHERE id=?", RESERVATION));
    }
  }

  private static OrderExpiryApi api(Database db) {
    return api(db.source);
  }

  private static OrderExpiryApi api(DataSource source) {
    var guard = new ScheduleCapacityGuardApiImpl(source);
    var scheduleFacts = new ScheduleProtectionFactsApiImpl(source, guard);
    var reservations = new ReservationExpiryApiImpl(source, SelectionExpiryAcceptanceTest::id,
        guard, new OrderExpiryFactsApiImpl(source, guard), scheduleFacts);
    return new OrderExpiryApiImpl(source, SelectionExpiryAcceptanceTest::id, guard,
        reservations, new BookingPaymentExposureApiImpl(source, guard),
        new BookingCouponExposureApiImpl(source, guard));
  }

  private static ExpireOrderCommand command(Seed seed) {
    return new ExpireOrderCommand(systemContext(), Long.toString(ORDER), Long.toString(RESERVATION),
        0, seed.deadline());
  }

  private static CommandContext systemContext() {
    return new CommandContext(requestId(), "expiry-qa", OperatorType.SYSTEM, null, "ASYNC_TASK");
  }

  private static String requestId() {
    return "TASK:RESERVATION_HOLD_EXPIRE:" + RESERVATION + ":0";
  }

  private static long id() { return IDS.incrementAndGet(); }

  private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
    ApiException error = assertThrows(ApiException.class, action);
    assertEquals(code, error.code());
  }

  private record Seed(OffsetDateTime deadline, LocalDate day) {}

  private static final class Database implements AutoCloseable {
    private static final List<String> SCHEMAS = List.of(
        "06-核心数据库Schema-v0.1.sql", "13-Async-Infra-Schema-v0.1.sql",
        "37-Reservation-Protection-Foundation-Schema-v0.1.sql",
        "38-Booking-Create-Schema-v0.1.sql", "39-Booking-Expiry-Schema-v0.1.sql");
    private final String name = "qa_sel_exp_" + UUID.randomUUID().toString().replace("-", "");
    private final JdbcTemplate admin;
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final DataSourceTransactionManager manager;
    private Seed seed;

    Database() throws Exception {
      Map<String, String> env = System.getenv();
      String prefix = env.containsKey("BOOKING_MYSQL_URL") ? "BOOKING" : "AUTH";
      String url = env.get(prefix + "_MYSQL_URL"), user = env.get(prefix + "_MYSQL_USER");
      String password = env.get(prefix + "_MYSQL_PASSWORD");
      if (url == null || user == null || password == null
          || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/")) {
        throw new IllegalStateException("Use an isolated local BOOKING or AUTH MySQL server root");
      }
      admin = new JdbcTemplate(source(url, user, password));
      source = source(url + name, user, password);
      jdbc = new JdbcTemplate(source);
      manager = new DataSourceTransactionManager(source);
      admin.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4");
      try {
        for (String file : SCHEMAS) script(file);
      } catch (Exception failure) { close(); throw failure; }
    }

    private static DataSource source(String url, String user, String password) {
      return new DriverManagerDataSource(url
          + "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
          user, password);
    }

    private void script(String filename) throws Exception {
      Path root = Path.of("").toAbsolutePath();
      while (root != null && !Files.exists(root.resolve("docs/03-database/" + filename))) root = root.getParent();
      assertNotNull(root);
      try (Connection connection = source.getConnection()) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(
            new FileSystemResource(root.resolve("docs/03-database/" + filename)), StandardCharsets.UTF_8));
      }
    }

    private <T> T transaction(java.util.concurrent.Callable<T> work) {
      TransactionTemplate tx = new TransactionTemplate(manager);
      tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
      return tx.execute(status -> {
        try { return work.call(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
      });
    }

    private Seed seedPickup(OffsetDateTime deadline) {
      if (seed != null) throw new IllegalStateException("one order per fixture");
      deadline = deadline.truncatedTo(ChronoUnit.MILLIS);
      LocalDate day = LocalDate.now(ZoneOffset.UTC).plusDays(10);
      LocalDateTime p = day.atTime(1, 0), pEnd = day.atTime(1, 35);
      LocalDateTime gap = day.atTime(2, 0), gapEnd = day.atTime(2, 30);
      LocalDateTime r = day.atTime(3, 30), rEnd = day.atTime(4, 20);
      LocalDateTime due = deadline.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
      jdbc.update("INSERT INTO merchant(id,owner_user_id,merchant_name,status,created_at,updated_at)"
          + " VALUES(?,?,'Expiry QA','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", MERCHANT, USER);
      jdbc.update("INSERT INTO merchant_store(id,merchant_id,store_name,address,status,created_at,updated_at)"
          + " VALUES(?,?,?,'Test address','ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          STORE, MERCHANT, "Expiry store");
      window(PICKUP, p, pEnd, "PICKUP");
      window(GAP_PICKUP, gap, gapEnd, "PICKUP");
      window(RETURN, r, rEnd, "RETURN");
      jdbc.update("INSERT INTO schedule_reservation(id,order_id,merchant_id,store_id,service_id,user_id,"
              + "fulfillment_type,start_at,end_at,pickup_start_at,return_start_at,status,lock_expire_at,"
              + "capacity_snapshot,qualified_staff_count_snapshot,version,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,'PICKUP_DELIVERY',?,?,?,?, 'TEMP_LOCKED',?,1,1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          RESERVATION, ORDER, MERCHANT, STORE, SERVICE, USER, p, rEnd, p, r, due);
      claim(PICKUP, "PICKUP", p, pEnd);
      claim(RETURN, "RETURN", r, rEnd);
      jdbc.update("INSERT INTO pet_order(id,order_no,user_id,merchant_id,store_id,service_id,pet_id,reservation_id,"
              + "order_stage,payment_status,verification_status,fulfillment_type,original_amount,discount_amount,"
              + "pay_amount,appointment_start_at,appointment_end_at,payment_expire_at,version,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,?,?,'PENDING_PAYMENT','INIT','UNVERIFIED','PICKUP_DELIVERY',128.00,0.00,"
              + "128.00,?,?,?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          ORDER, id(), USER, MERCHANT, STORE, SERVICE, PET, RESERVATION, p, rEnd, due);
      seed = new Seed(deadline, day);
      return seed;
    }

    private void window(long id, LocalDateTime start, LocalDateTime end, String kind) {
      jdbc.update("INSERT INTO schedule_availability_window(id,merchant_id,store_id,service_id,"
              + "start_at,end_at,configured_capacity,status,window_kind,version,created_at,updated_at)"
              + " VALUES(?,?,?,?,?,?,1,'OPEN',?,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
          id, MERCHANT, STORE, SERVICE, start, end, kind);
    }

    private void claim(long window, String kind, LocalDateTime start, LocalDateTime end) {
      jdbc.update("INSERT INTO schedule_reservation_claim(id,reservation_id,window_id,store_id,service_id,kind,start_at,end_at)"
              + " VALUES(?,?,?,?,?,?,?,?)", id(), RESERVATION, window, STORE, SERVICE, kind, start, end);
    }

    private Seed seed() { return Objects.requireNonNull(seed); }

    private void shiftDeadline(OffsetDateTime value) {
      value = value.truncatedTo(ChronoUnit.MILLIS);
      LocalDateTime utc = value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
      jdbc.update("UPDATE pet_order SET payment_expire_at=? WHERE id=?", utc, ORDER);
      jdbc.update("UPDATE schedule_reservation SET lock_expire_at=? WHERE id=?", utc, RESERVATION);
      seed = new Seed(value, seed().day());
    }

    private String text(String sql, Object arg) { return jdbc.queryForObject(sql, String.class, arg); }
    private long number(String sql, Object arg) { return jdbc.queryForObject(sql, Long.class, arg); }

    private void assertActive() {
      assertEquals("PENDING_PAYMENT", text("SELECT order_stage FROM pet_order WHERE id=?", ORDER));
      assertEquals("TEMP_LOCKED", text("SELECT status FROM schedule_reservation WHERE id=?", RESERVATION));
      assertEquals(0L, number("SELECT COUNT(*) FROM order_status_log WHERE order_id=? AND event_type='PAYMENT_TIMEOUT'", ORDER));
      assertEquals(0L, number("SELECT COUNT(*) FROM schedule_reservation_audit WHERE reservation_id=? AND action='EXPIRE'", RESERVATION));
    }

    @Override public void close() { admin.execute("DROP DATABASE `" + name + "`"); }
  }
}
