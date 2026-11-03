package com.petplatform.boot.config;

import com.petplatform.admin.api.dto.AdminActionCheckQuery;
import com.petplatform.admin.api.dto.AdminActionDecision;
import com.petplatform.admin.api.dto.AdminCollectionActionCheckQuery;
import com.petplatform.admin.api.dto.AdminResourceScope;
import com.petplatform.admin.api.query.AdminAuthorizationQueryApi;
import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.OperatorType;
import com.petplatform.common.QueryContext;
import com.petplatform.common.SnowflakeIdGenerator;
import com.petplatform.community.api.CommunityAdminApi;
import com.petplatform.community.api.CommunityPublicApi;
import com.petplatform.community.biz.apiimpl.CommunityAdminApiImpl;
import com.petplatform.community.biz.apiimpl.CommunityPublicApiImpl;
import com.petplatform.community.biz.application.CommunityAdminAuthorizationPort;
import com.petplatform.community.biz.application.port.CommunityMediaObjectStore;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort;
import com.petplatform.community.biz.application.port.CommunityUserDirectoryPort.UserCard;
import com.petplatform.user.api.dto.UserBasicDTO;
import com.petplatform.user.api.query.UserQueryApi;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CCR-COM-001 community runtime. Default OFF; the kernel and both HTTP faces assemble only
 * when the feature switch is on. Missing cross-domain owners fail closed at call time.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "pet.community", name = "enabled", havingValue = "true")
public class CommunityConfiguration {

    @Bean
    CommunityUserDirectoryPort communityUserDirectoryPort(DataSource source,
            ObjectProvider<UserQueryApi> users) {
        return new CommunityUserDirectoryAdapter(users, source);
    }

    @Bean
    CommunityAdminAuthorizationPort communityAdminAuthorizationPort(
            ObjectProvider<AdminAuthorizationQueryApi> authorization) {
        return new CommunityAdminAuthorizationAdapter(authorization);
    }

    @Bean
    CommunityMediaObjectStore communityMediaObjectStore() {
        return new CommunityOssMediaStore();
    }

    @Bean
    CommunityPublicApi communityPublicApi(DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            CommunityUserDirectoryPort users, CommunityMediaObjectStore mediaStore,
            ObjectProvider<Clock> clock,
            @Value("${pet.community.media.public-base-url:}") String mediaBaseUrl) {
        return new CommunityPublicApiImpl(source, requireIds(ids), users, mediaStore,
                clock.getIfAvailable(Clock::systemUTC), mediaBaseUrl);
    }

    @Bean
    CommunityAdminApi communityAdminApi(DataSource source, ObjectProvider<SnowflakeIdGenerator> ids,
            CommunityAdminAuthorizationPort authorization, CommunityUserDirectoryPort users,
            CommunityMediaObjectStore mediaStore, ObjectProvider<Clock> clock,
            @Value("${pet.community.media.public-base-url:}") String mediaBaseUrl) {
        return new CommunityAdminApiImpl(source, requireIds(ids), authorization, users, mediaStore,
                clock.getIfAvailable(Clock::systemUTC), mediaBaseUrl);
    }

    private static SnowflakeIdGenerator requireIds(ObjectProvider<SnowflakeIdGenerator> ids) {
        SnowflakeIdGenerator provider = ids.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("Community kernel requires a Snowflake ID provider");
        }
        return provider;
    }

    /** Isolated opt-in Flyway migration; only an explicitly named com001_* database is accepted. */
    @Bean
    Object communityMigration(DataSource source,
            @Value("${pet.community.migration-enabled:false}") boolean enabled,
            @Value("${pet.community.migration-database:}") String database) throws java.sql.SQLException {
        if (!enabled) return null;
        String catalog;
        try (java.sql.Connection connection = source.getConnection()) {
            catalog = connection.getCatalog();
        }
        if (database == null || !database.startsWith("com001_") || !database.equals(catalog)) {
            throw new IllegalStateException(
                    "Only explicit isolated community migration is permitted in this delivery");
        }
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/community-migration")
                .table("community_schema_history")
                .load()
                .migrate();
        return null;
    }

    // ------------------------------------------------------------------ adapters

    static final class CommunityUserDirectoryAdapter implements CommunityUserDirectoryPort {
        private final ObjectProvider<UserQueryApi> users;

        private final DataSource source;

        CommunityUserDirectoryAdapter(ObjectProvider<UserQueryApi> users, DataSource source) {
            this.users = Objects.requireNonNull(users);
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public Map<String, UserCard> lookup(List<String> userIds) {
            UserQueryApi api = users.getIfAvailable();
            if (api == null) api = new com.petplatform.user.biz.apiimpl.UserQueryApiImpl(source);
            try {
                List<UserBasicDTO> rows = api.listUsers(new UserQueryApi.UserBasicsQuery(userIds));
                Map<String, UserCard> result = new LinkedHashMap<>();
                if (rows != null) {
                    for (UserBasicDTO row : rows) {
                        String nickname = row.status() != null && "ACTIVE".equals(row.status().name())
                                ? row.nickname() : "已注销用户";
                        result.put(row.userId(), new UserCard(row.userId(),
                                nickname == null || nickname.isBlank() ? "宠友" : nickname, null));
                    }
                }
                return result;
            } catch (RuntimeException failure) {
                throw unavailable("user query owner is unavailable");
            }
        }
    }

    static final class CommunityAdminAuthorizationAdapter implements CommunityAdminAuthorizationPort {
        private final ObjectProvider<AdminAuthorizationQueryApi> authorization;

        CommunityAdminAuthorizationAdapter(ObjectProvider<AdminAuthorizationQueryApi> authorization) {
            this.authorization = Objects.requireNonNull(authorization);
        }

        @Override
        public Decision check(Check check) {
            AdminAuthorizationQueryApi api = authorization.getIfAvailable();
            if (api == null) throw unavailable("admin authorization owner is not assembled");
            AdminActionDecision decision;
            try {
                String action = check.actionCode();
                if (action != null && action.endsWith(".read")
                        && check.resourceType() != null && check.resourceType().endsWith("_LIST")) {
                    decision = api.checkCollection(new AdminCollectionActionCheckQuery(
                            check.sessionId(), check.sessionGeneration(), check.operatorId(),
                            action, purpose(action), AdminActionCheckQuery.CheckPhase.READ_RESULT));
                } else {
                    decision = api.check(new AdminActionCheckQuery(
                            check.sessionId(), check.sessionGeneration(), check.operatorId(),
                            action, new AdminResourceScope(check.resourceType(),
                            check.resourceId() == null || "0".equals(check.resourceId())
                                    ? "1" : check.resourceId(), null, null, "0"),
                            purpose(action), phase(action)));
                }
            } catch (RuntimeException failure) {
                throw unavailable("admin authorization owner is unavailable");
            }
            if (decision == null || decision.checkedAt() == null || decision.authzVersion() == null
                    || decision.authzVersion().isBlank()) {
                throw unavailable("authorization decision is unavailable");
            }
            return new Decision(decision.allowed(), decision.authzVersion(), decision.checkedAt());
        }

        private static AdminActionCheckQuery.CheckPhase phase(String action) {
            return action != null && action.endsWith(".read")
                    ? AdminActionCheckQuery.CheckPhase.READ_RESULT
                    : AdminActionCheckQuery.CheckPhase.EXECUTE;
        }

        private static String purpose(String action) {
            String value = "COMMUNITY_" + String.valueOf(action).replace('.', '_').toUpperCase(Locale.ROOT);
            return value.length() > 64 ? value.substring(0, 64) : value;
        }
    }

    private static ApiException unavailable(String message) {
        return new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }
}
