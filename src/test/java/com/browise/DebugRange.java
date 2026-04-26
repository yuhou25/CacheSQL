package com.browise;

import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class DebugRange {

    public static void main(String[] args) throws Exception {
        String sql = "select * from KCA2";
        String[] indexes = {"AAC001", "AAC002", "AAC003"};
        Table table = database.load("kca2", sql, indexes);
        table.init();
        System.out.println("Loaded: " + table.getData().activeCount() + " rows\n");

        System.out.println("--- getMoreAndLessThen('99999', '9999:') ---");
        Object r = table.getMoreAndLessThen("AAC001", "99999", "9999:");
        if (r instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) r;
            System.out.println("Result: " + list.size() + " rows");
            if (!list.isEmpty()) {
                Row first = (Row) list.get(0);
                Row last = (Row) list.get(list.size() - 1);
                System.out.println("first AAC001=" + first.get("AAC001"));
                System.out.println("last  AAC001=" + last.get("AAC001"));
            }
        } else {
            System.out.println("Result: null");
        }

        System.out.println("\n--- getMoreThen('99999') ---");
        Object r2 = table.getMoreThen("AAC001", "99999");
        if (r2 instanceof java.util.List) {
            System.out.println("Result: " + ((java.util.List<?>) r2).size() + " rows");
        }

        System.out.println("\n--- getLessThen('9999:') ---");
        Object r3 = table.getLessThen("AAC001", "9999:");
        if (r3 instanceof java.util.List) {
            System.out.println("Result: " + ((java.util.List<?>) r3).size() + " rows");
        } else {
            System.out.println("Result: null");
        }

        System.out.println("\n--- string compare test ---");
        System.out.println("'9999903676' < '9999:' = " + ("9999903676".compareTo("9999:") < 0));
        System.out.println("'99999' < '9999:' = " + ("99999".compareTo("9999:") < 0));
        System.out.println("'9999900003' < '9999:' = " + ("9999900003".compareTo("9999:") < 0));
    }
}
