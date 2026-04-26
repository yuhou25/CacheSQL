package com.browise;

import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class RangeBugTest {

    public static void main(String[] args) throws Exception {
        String sql = "select * from KCA2";
        String[] indexes = {"AAC001"};
        Table table = database.load("kca2", sql, indexes);
        table.init();
        System.out.println("Loaded: " + table.getData().activeCount() + " rows\n");

        String key = "9999900003";

        System.out.println("=== Exact key: " + key + " ===");
        Object eq = table.get("AAC001", key);
        printResult("= (exact)", eq);

        System.out.println("\n=== GT vs GE test ===");
        Object gt = table.getMoreThen("AAC001", key);
        printResult("> (GT, should NOT include " + key + ")", gt);
        Object ge = table.getMoreThenEquals("AAC001", key);
        printResult(">= (GE, SHOULD include " + key + ")", ge);

        int eqCount = eq instanceof java.util.List ? ((java.util.List<?>) eq).size() : 0;
        int gtCount = gt instanceof java.util.List ? ((java.util.List<?>) gt).size() : 0;
        int geCount = ge instanceof java.util.List ? ((java.util.List<?>) ge).size() : 0;
        System.out.println("\n  = count: " + eqCount);
        System.out.println("  > count: " + gtCount + " (should be < total - eqCount if GT excludes exact)");
        System.out.println("  >= count: " + geCount + " (should be > GT count if GE includes exact)");

        System.out.println("\n=== LT vs LE test ===");
        String key2 = "9999900924";
        Object lt = table.getLessThen("AAC001", key2);
        printResult("< (LT, should NOT include " + key2 + ")", lt);
        Object le = table.getLessThenEquals("AAC001", key2);
        printResult("<= (LE, SHOULD include " + key2 + ")", le);

        int ltCount = lt instanceof java.util.List ? ((java.util.List<?>) lt).size() : 0;
        int leCount = le instanceof java.util.List ? ((java.util.List<?>) le).size() : 0;
        System.out.println("\n  < count: " + ltCount);
        System.out.println("  <= count: " + leCount + " (should be > LT count if LE includes exact)");

        System.out.println("\n=== Bug check ===");
        boolean geBug = (geCount == gtCount);
        boolean leBug = (leCount == ltCount);
        if (geBug) System.out.println("  [BUG] GE same as GT — exact match row missing!");
        else System.out.println("  [OK] GE > GT — exact match included");
        if (leBug) System.out.println("  [BUG] LE same as LT — exact match row missing!");
        else System.out.println("  [OK] LE > LT — exact match included");
    }

    private static void printResult(String label, Object result) {
        if (result == null) {
            System.out.println("  " + label + " => null");
        } else if (result instanceof java.util.List) {
            java.util.List<?> list = (java.util.List<?>) result;
            System.out.println("  " + label + " => " + list.size() + " rows");
            if (!list.isEmpty() && list.get(0) instanceof Row) {
                Row first = (Row) list.get(0);
                System.out.println("    first: " + first.get("AAC001"));
                Row last = (Row) list.get(list.size() - 1);
                System.out.println("    last:  " + last.get("AAC001"));
            }
        }
    }
}
