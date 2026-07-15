package com.buggyapp.chattiness;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.testng.Assert;
import org.testng.annotations.Test;

public class H2ChattinessDemoTest {

    private static final int EXPECTED_SAMPLE_ROWS = 10_000;

    @Test
    public void setupCreatesOnlyRowsUsedByChattyQueries() throws Exception {
        AtomicInteger batchedRows = new AtomicInteger();
        PreparedStatement preparedStatement = proxy(PreparedStatement.class, (proxy, method, args) -> {
            if ("addBatch".equals(method.getName())
                    && batchedRows.incrementAndGet() > EXPECTED_SAMPLE_ROWS) {
                throw new SQLException("Setup queued more than 10,000 sample rows");
            }
            return defaultValue(method.getReturnType());
        });
        Statement statement = proxy(Statement.class,
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        Connection connection = proxy(Connection.class, (proxy, method, args) -> {
            if ("createStatement".equals(method.getName())) {
                return statement;
            }
            if ("prepareStatement".equals(method.getName())) {
                return preparedStatement;
            }
            return defaultValue(method.getReturnType());
        });

        Method setupDatabase = H2ChattinessDemo.class
                .getDeclaredMethod("setupDatabase", Connection.class);
        setupDatabase.setAccessible(true);

        try {
            setupDatabase.invoke(null, connection);
        } catch (InvocationTargetException exception) {
            Assert.fail("Database setup exceeded the sample data required by the query loop",
                    exception.getCause());
        }

        Assert.assertEquals(batchedRows.get(), EXPECTED_SAMPLE_ROWS);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        return 0D;
    }
}
