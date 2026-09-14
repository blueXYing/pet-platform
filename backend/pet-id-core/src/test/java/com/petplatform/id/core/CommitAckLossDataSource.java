package com.petplatform.id.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Test-only: a real commit succeeds, then its caller loses the acknowledgement. */
final class CommitAckLossDataSource extends AbstractDataSource {
    private final DataSource delegate;
    private final AtomicBoolean armed = new AtomicBoolean(true);
    private final AtomicBoolean committed = new AtomicBoolean();
    private final AtomicInteger commits = new AtomicInteger();
    private final int loseCommitNumber;

    CommitAckLossDataSource(DataSource delegate, int loseCommitNumber) {
        this.delegate = delegate;
        this.loseCommitNumber = loseCommitNumber;
    }
    boolean committedBeforeLoss() { return committed.get(); }

    @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException {
        return wrap(delegate.getConnection(user, password));
    }

    private Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (method.getName().equals("commit") && commits.incrementAndGet() == loseCommitNumber
                                && armed.compareAndSet(true, false)) {
                            committed.set(true);
                            throw new SQLException("TEST: actual MySQL commit succeeded; acknowledgement lost", "08006");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
