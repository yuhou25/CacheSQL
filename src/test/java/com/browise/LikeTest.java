package com.browise;

import com.browise.core.util.DBUtil;
import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class LikeTest {

    public static void main(String[] args) throws Exception {
        System.out.println("===== LIKE 'xxx%' Test =====\n");

        String sql = "select * from KCA2";
        String[] indexes = {"AAC001", "AAC002", "AAC003"};
        Table table = database.load("kca2", sql, indexes);
        table.init();
        System.out.println("Loaded: " + table.getData().activeCount() + " rows\n");

        System.out.println("--- AAC001 first 5 values ---");
        for (int i = 0; i < Math.min(5, table.getData().size()); i++) {
            System.out.println("  " + table.getData().get(i).get("AAC001"));
        }
        System.out.println();

        System.out.println("--- LIKE prefix '9999900%' ---");
        testSql("select * from kca2 where AAC001 like '9999900%'");

        System.out.println("--- LIKE prefix '9999903%' ---");
        testSql("select * from kca2 where AAC001 like '9999903%'");

        System.out.println("--- LIKE prefix '99999%' ---");
        testSql("select * from kca2 where AAC001 like '99999%'");

        System.out.println("--- LIKE prefix 'xxx%' (no match) ---");
        testSql("select * from kca2 where AAC001 like 'xxx%'");

        System.out.println("--- LIKE prefix '99999000003' (exact match via like) ---");
        testSql("select * from kca2 where AAC001 like '99999000003%'");

        System.out.println("--- AAC003 first 5 values ---");
        for (int i = 0; i < Math.min(5, table.getData().size()); i++) {
            Object v = table.getData().get(i).get("AAC003");
            System.out.println("  " + v);
        }
        System.out.println();

        System.out.println("===== Done =====");
    }

    private static void testSql(String sql) throws Exception {
        try {
            QueryResult qr = SqlQueryEngine.query(sql);
            int count = (qr.rows != null) ? qr.rows.size() : 0;
            System.out.printf("  [%s] %s%n", qr.method, sql);
            System.out.printf("    => %d rows%n", count);
            if (qr.rows != null && !qr.rows.isEmpty()) {
                Row first = qr.rows.get(0);
                Row last = qr.rows.get(qr.rows.size() - 1);
                System.out.println("    first: AAC001=" + first.get("AAC001"));
                System.out.println("    last:  AAC001=" + last.get("AAC001"));
            }
        } catch (Exception e) {
            System.out.println("  [ERROR] " + sql + " => " + e.getMessage());
        }
        System.out.println();
    }
}
