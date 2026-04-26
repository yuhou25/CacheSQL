package com.browise;

import java.util.HashMap;
import java.util.Map;

import com.browise.core.util.DBUtil;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class MemoryAnalysis {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Memory Analysis for KCA2 =====\n");

        String sql = "select * from KCA2";
        String[] indexes = {"AAC001", "AAC002", "AAC003"};
        Table table = database.load("kca2", sql, indexes);
        table.init();

        int rows = table.getData().activeCount();
        String[] cols = table.getColumnNames();
        int colCount = cols.length;

        System.out.println("Rows: " + rows + ", Columns: " + colCount);
        System.out.println();

        System.out.println("--- Column value type & avg length ---");
        int totalStringCols = 0;
        int totalNumberCols = 0;
        int totalDateCols = 0;
        long totalStringChars = 0;
        int totalStringValues = 0;

        Map<String, int[]> colStats = new HashMap<String, int[]>();
        for (int c = 0; c < colCount; c++) {
            int stringCount = 0, numberCount = 0, dateCount = 0, nullCount = 0;
            int charLen = 0;
            for (int r = 0; r < Math.min(rows, 100); r++) {
                Row row = table.getData().get(r);
                Object v = row.get(c);
                if (v == null) { nullCount++; continue; }
                if (v instanceof String) {
                    stringCount++;
                    charLen += ((String) v).length();
                    totalStringChars += ((String) v).length();
                    totalStringValues++;
                } else if (v instanceof Number) {
                    numberCount++;
                } else if (v instanceof java.util.Date || v instanceof java.sql.Timestamp) {
                    dateCount++;
                }
            }
            String type = stringCount > 0 ? "String" : numberCount > 0 ? "Number" : dateCount > 0 ? "Date" : "?";
            int avgLen = stringCount > 0 ? charLen / stringCount : 0;
            if (stringCount > 0) totalStringCols++;
            if (numberCount > 0) totalNumberCols++;
            if (dateCount > 0) totalDateCols++;
            System.out.printf("  %-8s type=%-8s avgLen=%-3d nulls=%d%n", cols[c], type, avgLen, nullCount);
        }

        System.out.println();
        System.out.println("--- Memory breakdown estimate ---");

        long stringObjOverhead = 24L * totalStringValues * (rows / Math.min(rows, 100));
        long stringCharArrays = (12L + 2L * (totalStringChars / Math.max(1, totalStringValues))) * totalStringValues * (rows / Math.min(rows, 100));
        long rowObjects = 16L * rows;
        long valuesArrays = (12L + 8L * colCount) * rows;
        long columnNamesShared = 12L + 8L * colCount;
        long colNameStrings = 0;
        for (String c : cols) colNameStrings += 24 + 12 + 2L * c.length();

        int stringCols = totalStringCols;
        int numberCols = totalNumberCols;
        int dateCols = totalDateCols;

        long totalStringMem = stringObjOverhead + stringCharArrays;
        long charArraySavings = stringObjOverhead;

        System.out.printf("  Row objects (header+refs):          %8d KB%n", rowObjects / 1024);
        System.out.printf("  Object[] values arrays:             %8d KB%n", valuesArrays / 1024);
        System.out.printf("  String objects (%d per row × %d):   %8d KB%n", stringCols, rows, stringObjOverhead / 1024);
        System.out.printf("  char[] inside Strings:              %8d KB%n", stringCharArrays / 1024);
        System.out.printf("  Number objects (%d per row × %d):   %8d KB%n", numberCols, rows, 16L * numberCols * rows / 1024);
        System.out.printf("  Timestamp objects (%d per row):     %8d KB%n", dateCols, 24L * dateCols * rows / 1024);
        System.out.printf("  Column names (shared, 1 copy):      %8d KB%n", (columnNamesShared + (long) colNameStrings) / 1024);
        System.out.println("  ─────────────────────────────────────────────");
        System.out.printf("  Row data subtotal:                  %8d KB%n", (rowObjects + valuesArrays + totalStringMem + 16L * numberCols * rows + 24L * dateCols * rows) / 1024);

        System.out.println();
        System.out.println("--- String → char[] savings analysis ---");
        System.out.printf("  String wrapper overhead per value:   %d bytes (obj header 12 + hash 4 + ref 8)%n", 24);
        System.out.printf("  Total String wrappers:               %d%n", (long) stringCols * rows);
        System.out.printf("  Savings if char[] replaces String:   %d KB (%.1f%% of row data)%n",
            charArraySavings / 1024,
            100.0 * charArraySavings / (rowObjects + valuesArrays + totalStringMem + 16L * numberCols * rows + 24L * dateCols * rows));

        System.out.println();
        System.out.println("--- B+ tree index overhead ---");
        long entriesPerIndex = rows;
        long entryObj = 24L * entriesPerIndex;
        long arrayListObj = 40L * entriesPerIndex;
        long integerObj = 16L * entriesPerIndex;
        long perIndex = entryObj + arrayListObj + integerObj;
        System.out.printf("  Per index (%d entries):%n", entriesPerIndex);
        System.out.printf("    Entry objects:        %8d KB%n", entryObj / 1024);
        System.out.printf("    ArrayList(1) objects: %8d KB%n", arrayListObj / 1024);
        System.out.printf("    Integer row refs:     %8d KB%n", integerObj / 1024);
        System.out.printf("    Subtotal per index:   %8d KB%n", perIndex / 1024);
        System.out.printf("    3 indexes total:      %8d KB%n", perIndex * 3 / 1024);

        System.out.println();
        System.out.println("--- Scale projection (per million rows) ---");
        double scale = 1_000_000.0 / rows;
        long rowDataTotal = rowObjects + valuesArrays + totalStringMem + 16L * numberCols * rows + 24L * dateCols * rows;
        System.out.printf("  Current row data:      %8.0f MB%n", rowDataTotal * scale / 1024 / 1024);
        System.out.printf("  With char[] instead:   %8.0f MB  (save %.0f MB)%n",
            (rowDataTotal - charArraySavings) * scale / 1024 / 1024,
            charArraySavings * scale / 1024 / 1024);
        System.out.printf("  3 B+ tree indexes:     %8.0f MB%n", perIndex * 3 * scale / 1024 / 1024);
        System.out.printf("  Total current:         %8.0f MB%n", (rowDataTotal + perIndex * 3) * scale / 1024 / 1024);
        System.out.printf("  Total with char[]:     %8.0f MB  (save %.1f%%)%n",
            (rowDataTotal - charArraySavings + perIndex * 3) * scale / 1024 / 1024,
            100.0 * charArraySavings / (rowDataTotal + perIndex * 3));
    }
}
