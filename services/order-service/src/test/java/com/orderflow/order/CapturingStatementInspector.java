package com.orderflow.order;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.hibernate.resource.jdbc.spi.StatementInspector;

public class CapturingStatementInspector implements StatementInspector {

    // Hibernate instantiates the inspector itself, so tests read the captured SQL through static state.
    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    static void clear() {
        STATEMENTS.clear();
    }

    static List<String> orderQueries() {
        return STATEMENTS.stream().filter(sql -> sql.contains("orders.orders")).toList();
    }
}
