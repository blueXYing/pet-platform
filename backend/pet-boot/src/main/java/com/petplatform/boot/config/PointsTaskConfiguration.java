package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.PageResult;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.order.api.dto.OrderSnapshotDTO;
import com.petplatform.order.api.query.OrderQueryApi;
import com.petplatform.points.biz.apiimpl.PointsTaskCommandApiImpl;
import com.petplatform.points.biz.apiimpl.PointsTaskQueryApiImpl;
import com.petplatform.points.biz.domain.service.PointsTaskFacts;
import com.petplatform.user.api.query.UserQueryApi;
import com.petplatform.user.biz.apiimpl.UserQueryApiImpl;
import java.time.Clock;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contract 58 runtime (CCR-POINTS-TASK-001): sign-in + referral earn kernel plus the
 * ops-configured grant amounts, default off. Hard requirements: a production Snowflake bean
 * (PLAT-002, the same constraint pet.auth.c carries) and the SQL58/SQL06 §9/SQL14 tables in the
 * connected database — no migration is executed here. The cross-domain facts adapter reads the
 * order first-payment instant and the user existence/nickname through the API modules only
 * (ARCH-002); missing order/user providers fail closed with COMMON_DEPENDENCY_UNAVAILABLE.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.points.task", name = "enabled", havingValue = "true")
public class PointsTaskConfiguration {

  @Bean
  PointsTaskFacts pointsTaskFacts(
      ObjectProvider<OrderQueryApi> orders, DataSource source) {
    UserQueryApi users = new UserQueryApiImpl(source);
    OrderQueryApi orderQuery = orders.getIfAvailable();
    return new BootPointsTaskFacts(orderQuery, users);
  }

  @Bean
  PointsTaskQueryApiImpl pointsTaskQueryApi(DataSource source,
      ObjectProvider<SnowflakeIdGenerator> ids, PointsTaskFacts facts, ObjectProvider<Clock> clock) {
    requireIds(ids);
    return new PointsTaskQueryApiImpl(source, ids.getObject(), facts,
        clock.getIfAvailable(Clock::systemUTC));
  }

  @Bean
  PointsTaskCommandApiImpl pointsTaskCommandApi(DataSource source,
      ObjectProvider<SnowflakeIdGenerator> ids, PointsTaskFacts facts, ObjectProvider<Clock> clock) {
    requireIds(ids);
    return new PointsTaskCommandApiImpl(source, ids.getObject(), facts,
        clock.getIfAvailable(Clock::systemUTC));
  }

  /**
   * PLAT-002: the earn kernel writes ledger/idempotency rows, so it cannot assemble without a
   * real ID provider. The HTTP switch cannot outrank the kernel: the controllers inject the
   * kernel beans directly, so http=true with the kernel off fails bean resolution at startup —
   * the same fail-fast guard shape as the refund-application slice.
   */
  private static void requireIds(ObjectProvider<SnowflakeIdGenerator> ids) {
    if (ids.getIfAvailable() == null) {
      throw new IllegalStateException(
          "Points task kernel requires a Snowflake ID provider (PLAT-002)");
    }
  }

  /** Boot-level adapter over the order/user API modules; pure reads, fail-closed. */
  private static final class BootPointsTaskFacts implements PointsTaskFacts {
    /** Defensive scan cap: 50 pages x 100 = 5_000 own orders; beyond that fail closed. */
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 50;
    private static final String FACTS_TRACE = "PTS:FIRST_PAID_ORDER_FACT";

    private final OrderQueryApi orders;
    private final UserQueryApi users;

    BootPointsTaskFacts(OrderQueryApi orders, UserQueryApi users) {
      this.orders = orders;
      this.users = Objects.requireNonNull(users);
    }

    @Override
    public java.time.OffsetDateTime firstPaidAt(String userId) {
      if (orders == null) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
            "订单事实不可用（订单读侧未装配）");
      }
      return orders.firstPaidAt(new OrderQueryApi.UserOrderFactQuery(userId));
    }

    /**
     * Contract 60 §7 (CCR-POINTS-ORDER-001 P1): earliest paid order of the user, resolved over
     * the caller's own {@code listMyOrders} read with the same subject — no order-module change,
     * no cross-user surface (the payment events carry no owner, 07号 §7.1 note). Deterministic
     * tie-break on the canonical orderId; a scan beyond the cap fails closed.
     */
    @Override
    public PointsTaskFacts.FirstPaidOrder firstPaidOrder(String userId) {
      if (orders == null) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
            "订单事实不可用（订单读侧未装配）");
      }
      String bestOrderId = null;
      java.time.OffsetDateTime bestPaidAt = null;
      long total = 0;
      for (int page = 1; page <= MAX_PAGES; page++) {
        PageResult<OrderSnapshotDTO> result = orders.listMyOrders(
            new OrderQueryApi.MyOrderListQuery(null, page, PAGE_SIZE,
                new QueryContext(FACTS_TRACE, OperatorType.USER, userId)));
        total = result.total();
        for (OrderSnapshotDTO snapshot : result.items()) {
          if (!"PAID".equals(snapshot.paymentStatus()) || snapshot.paidAt() == null) {
            continue;
          }
          boolean better = bestPaidAt == null
              || snapshot.paidAt().toInstant().isBefore(bestPaidAt.toInstant())
              || (snapshot.paidAt().toInstant().equals(bestPaidAt.toInstant())
                  && compareDecimalStrings(snapshot.orderId(), bestOrderId) < 0);
          if (better) {
            bestOrderId = snapshot.orderId();
            bestPaidAt = snapshot.paidAt();
          }
        }
        if ((long) page * PAGE_SIZE >= total) {
          return bestOrderId == null ? null : new PointsTaskFacts.FirstPaidOrder(bestOrderId, bestPaidAt);
        }
      }
      throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE,
          "订单事实扫描超出上限，首单定位失败");
    }

    /** Numeric compare over canonical decimal id strings; malformed ids sort last. */
    private static int compareDecimalStrings(String left, String right) {
      int byLength = Integer.compare(left.length(), right.length());
      return byLength != 0 ? byLength : left.compareTo(right);
    }

    @Override
    public boolean userEnabled(String userId) {
      var user = users.getUser(new com.petplatform.user.api.query.UserIdQuery(userId));
      return user != null && "ACTIVE".equals(user.status().name());
    }

    @Override
    public String userNickname(String userId) {
      var user = users.getUser(new com.petplatform.user.api.query.UserIdQuery(userId));
      return user == null || user.nickname() == null ? "" : user.nickname();
    }
  }
}
