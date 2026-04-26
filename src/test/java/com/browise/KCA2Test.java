package com.browise;

import com.browise.core.util.DBUtil;
import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class KCA2Test {

    public static void main(String[] args) throws Exception {
        System.out.println("===== CacheSQL KCA2 Test =====");
        System.out.println("DB: " + DBUtil.getConfig("db.url", "?"));
        System.out.println();

        System.out.println("--- Step 1: Load KCA2 ---");
        Runtime rt = Runtime.getRuntime();
        long before = rt.totalMemory() - rt.freeMemory();
        System.gc(); Thread.sleep(100);
        before = rt.totalMemory() - rt.freeMemory();

        String sql = "select * from KCA2";
        String[] indexes = {"AAC001", "AAC002", "AAC003"};
        Table table = database.load("kca2", sql, indexes);
        table.init();

        System.gc(); Thread.sleep(100);
        long after = rt.totalMemory() - rt.freeMemory();

        System.out.println("Rows loaded: " + table.getData().activeCount());
        System.out.println("Columns: " + table.getColumnNames().length);
        System.out.println("Indexes: " + table.getIndex().keySet());
        System.out.println("Index types: " + table.indexColumnType());
        System.out.println("Memory used: " + (after - before) / 1024 + " KB (" + (after - before) / 1024 / 1024 + " MB)");
        System.out.println("Per row: " + (after - before) / table.getData().activeCount() + " bytes");
        System.out.println("Total heap: " + rt.totalMemory() / 1024 / 1024 + " MB, free: " + rt.freeMemory() / 1024 / 1024 + " MB");
        System.out.println();

        System.out.println("--- Step 2: Print first 3 rows ---");
        for (int i = 0; i < Math.min(3, table.getData().size()); i++) {
            Row row = table.getData().get(i);
            if (row.isDelete()) continue;
            String[] cols = row.getColumnNames();
            if (cols != null) {
                StringBuilder sb = new StringBuilder("  row" + i + ": ");
                for (int j = 0; j < Math.min(5, cols.length); j++) {
                    Object v = row.get(j);
                    sb.append(cols[j]).append("=").append(v).append(" ");
                }
                System.out.println(sb.toString());
            }
        }
        System.out.println();

        String sampleAAC001 = "9999903676";
        String sampleAAC002 = null;
        String sampleAAC003 = null;
        Row r0 = table.getData().get(0);
        if (r0 != null) {
            String[] cols = r0.getColumnNames();
            for (int i = 0; i < cols.length; i++) {
                if ("AAC002".equals(cols[i])) sampleAAC002 = String.valueOf(r0.get(i));
                if ("AAC003".equals(cols[i])) sampleAAC003 = String.valueOf(r0.get(i));
            }
        }

        System.out.println("--- Step 3: Test direct get() ---");
        Object result = table.get("AAC001", sampleAAC001);
        printResult("AAC001=" + sampleAAC001, result);
        System.out.println();

        System.out.println("--- Step 4: Test SQL query ---");
        testSql("select * from kca2 where AAC001 = " + sampleAAC001);
        testSql("select * from kca2 where AAC001 = '" + sampleAAC001 + "'");
        if (sampleAAC002 != null) testSql("select * from kca2 where AAC002 = '" + sampleAAC002 + "'");
        if (sampleAAC003 != null) testSql("select * from kca2 where AAC003 = '" + sampleAAC003 + "'");
        testSql("select * from kca2 where AAC001 > '9999900000'");
        testSql("select * from kca2 where AAC001 < '9999900100'");
        testSql("select * from kca2 where AAC001 >= '9999903000' and AAC001 <= '9999904000'");
        testSql("select * from kca2 where NO_INDEX_COL = 123");

        System.out.println();
        System.out.println("===== All tests done =====");
    }

    private static void testSql(String sql) throws Exception {
        try {
            QueryResult qr = SqlQueryEngine.query(sql);
            int count = (qr.rows != null) ? qr.rows.size() : 0;
            System.out.println("  [" + qr.method + "] " + sql);
            System.out.println("    => " + count + " rows");
            if (qr.rows != null && !qr.rows.isEmpty()) {
                Row first = qr.rows.get(0);
                String[] cols = first.getColumnNames();
                if (cols != null && cols.length > 0) {
                    StringBuilder sb = new StringBuilder("    first: ");
                    for (int i = 0; i < Math.min(4, cols.length); i++) {
                        sb.append(cols[i]).append("=").append(first.get(i)).append(" ");
                    }
                    System.out.println(sb.toString());
                }
                if (count > 1) {
                    Row last = qr.rows.get(count - 1);
                    String[] cols2 = last.getColumnNames();
                    if (cols2 != null && cols2.length > 0) {
                        StringBuilder sb = new StringBuilder("    last:  ");
                        for (int i = 0; i < Math.min(4, cols2.length); i++) {
                            sb.append(cols2[i]).append("=").append(last.get(i)).append(" ");
                        }
                        System.out.println(sb.toString());
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  [ERROR] " + sql + " => " + e.getMessage());
        }
        System.out.println();
    }

    private static void printResult(String label, Object result) {
        if (result == null) {
            System.out.println("  " + label + " => null");
        } else if (result instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) result;
            System.out.println("  " + label + " => " + list.size() + " rows");
            if (!list.isEmpty() && list.get(0) instanceof Row) {
                Row first = (Row) list.get(0);
                String[] cols = first.getColumnNames();
                if (cols != null) {
                    StringBuilder sb = new StringBuilder("    ");
                    for (int i = 0; i < Math.min(4, cols.length); i++) {
                        sb.append(cols[i]).append("=").append(first.get(i)).append(" ");
                    }
                    System.out.println(sb.toString());
                }
            }
        }
    }
}
