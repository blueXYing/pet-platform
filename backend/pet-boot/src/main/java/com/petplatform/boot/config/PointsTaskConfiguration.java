package com.petplatform.boot.config;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.SnowflakeIdGenerator;
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
