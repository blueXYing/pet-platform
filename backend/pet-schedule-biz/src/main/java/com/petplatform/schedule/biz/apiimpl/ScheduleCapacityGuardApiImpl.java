package com.petplatform.schedule.biz.apiimpl;

import com.petplatform.common.ApiException;
import com.petplatform.common.CommonApiCodes;
import com.petplatform.common.DecimalPublicIdCodec;
import com.petplatform.common.QueryContext;
import com.petplatform.schedule.api.protection.ScheduleCapacityGuardApi;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The stable SCH row lock shared by every capacity-changing command in one local transaction. */
public final class ScheduleCapacityGuardApiImpl implements ScheduleCapacityGuardApi {
    private static final DecimalPublicIdCodec IDS = new DecimalPublicIdCodec();
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final ThreadLocal<Map<ConnectionHolder, Registration>> held =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public ScheduleCapacityGuardApiImpl(DataSource source) {
        this.source = Objects.requireNonNull(source, "source is required");
        this.jdbc = new JdbcTemplate(source);
    }

    @Override
    public void acquire(List<String> storeIds, QueryContext context) {
        Objects.requireNonNull(context, "context is required");
        if (storeIds == null || storeIds.isEmpty()) invalid("storeIds are required");
        TreeSet<Long> ordered = new TreeSet<>();
        for (String storeId : storeIds) ordered.add(id(storeId));
        ConnectionHolder holder = checkedHolder();
        Map<ConnectionHolder, Registration> registrations = held.get();
        if (!registrations.isEmpty() && !registrations.containsKey(holder)) {
            fail(holder, "nested independent transaction cannot reacquire a guarded store");
        }
        Registration registration = registrations.get(holder);
        if (registration != null && !registration.ids.isEmpty()) {
            for (long target : ordered) {
                if (!registration.ids.contains(target) && target < registration.ids.last()) {
                    fail(holder, "store guard lock order would decrease");
                }
            }
        }
        if (registration == null) {
            registration = new Registration(holder.getConnection());
            registrations.put(holder, registration);
            ConnectionHolder captured = holder;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    Map<ConnectionHolder, Registration> current = held.get();
                    current.remove(captured);
                    if (current.isEmpty()) held.remove();
                }
            });
        }
        try {
            for (long storeId : ordered) {
                if (registration.ids.contains(storeId)) continue;
                jdbc.update("INSERT INTO schedule_store_capacity_guard(store_id,version,updated_at) "
                        + "VALUES(?,0,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE store_id=store_id", storeId);
                Long locked = jdbc.queryForObject(
                        "SELECT store_id FROM schedule_store_capacity_guard WHERE store_id=? FOR UPDATE",
                        Long.class, storeId);
                if (locked == null || locked != storeId) fail(holder, "store guard row is missing");
                registration.ids.add(storeId);
            }
        } catch (ApiException known) {
            holder.setRollbackOnly();
            throw known;
        } catch (RuntimeException unavailable) {
            fail(holder, "store guard dependency unavailable");
        }
    }

    @Override
    public void requireHeld(String storeId, DataSource callerSource) {
        long key = id(storeId);
        if (callerSource != source) {
            if (callerSource != null) {
                Object caller = TransactionSynchronizationManager.getResource(callerSource);
                if (caller instanceof ConnectionHolder holder) holder.setRollbackOnly();
            }
            reject("store guard datasource differs");
        }
        ConnectionHolder holder = checkedHolder();
        Registration registration = held.get().get(holder);
        if (registration == null || registration.connection != holder.getConnection()
                || !registration.ids.contains(key)) {
            fail(holder, "store guard is not held by this transaction");
        }
    }

    private ConnectionHolder checkedHolder() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            reject("a writable shared transaction is required");
        }
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (!(resource instanceof ConnectionHolder)) {
            reject("the shared datasource is not bound to this transaction");
        }
        ConnectionHolder holder = (ConnectionHolder) resource;
        try {
            Connection connection = holder.getConnection();
            if (connection.getAutoCommit() || connection.isReadOnly()
                    || connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                fail(holder, "a writable READ_COMMITTED transaction is required");
            }
        } catch (SQLException unavailable) {
            fail(holder, "cannot verify shared transaction connection");
        }
        return holder;
    }

    private static long id(String value) {
        try {
            long result = IDS.fromApi(value);
            if (result <= 0) throw new IllegalArgumentException("nonpositive id");
            return result;
        } catch (RuntimeException malformed) {
            invalid("invalid storeId");
            throw malformed;
        }
    }

    private static void invalid(String message) {
        throw new ApiException(CommonApiCodes.INVALID_ARGUMENT, message);
    }

    private static void unavailable(String message) {
        throw new ApiException(CommonApiCodes.DEPENDENCY_UNAVAILABLE, message);
    }

    private void reject(String message) {
        Object resource = TransactionSynchronizationManager.getResource(source);
        if (resource instanceof ConnectionHolder holder) holder.setRollbackOnly();
        unavailable(message);
    }

    private static void fail(ConnectionHolder holder, String message) {
        holder.setRollbackOnly();
        unavailable(message);
    }

    private static final class Registration {
        private final Connection connection;
        private final TreeSet<Long> ids = new TreeSet<>();

        private Registration(Connection connection) {
            this.connection = connection;
        }
    }
}
