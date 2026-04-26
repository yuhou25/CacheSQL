package com.browise;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import com.browise.core.util.DBUtil;
import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class BenchmarkTest {

    public static void main(String[] args) throws Exception {
        System.out.println("===== Benchmark Test =====");
        System.out.println("DB: " + DBUtil.getConfig("db.url", "?"));
        System.out.println();

        String sql = "select * from KCA2";
        String[] indexes = {"AAC001", "AAC002", "AAC003"};
        Table table = database.load("kca2", sql, indexes);
        System.out.println("Loaded: " + table.getData().activeCount() + " rows\n");

        String sampleAAC001 = String.valueOf(table.getData().get(0).get("AAC001"));

        int warmup = 10000;
        for (int i = 0; i < warmup; i++) {
            table.get("AAC001", sampleAAC001);
            try { SqlQueryEngine.query("select * from kca2 where AAC001 = '" + sampleAAC001 + "'"); } catch (Exception e) {}
        }

        int iterations = 100000;
        System.out.println("--- Single thread: " + iterations + " iterations ---");

        long t1, t2;

        t1 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            table.get("AAC001", sampleAAC001);
        }
        t2 = System.nanoTime();
        long directQps = iterations * 1_000_000_000L / (t2 - t1);
        System.out.printf("  get() direct:          %,10d QPS  (%6.1f us/op)%n", directQps, (t2 - t1) / 1000.0 / iterations);

        t1 = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try {
                SqlQueryEngine.query("select * from kca2 where AAC001 = '" + sampleAAC001 + "'");
            } catch (Exception e) {}
        }
        t2 = System.nanoTime();
        long cachedSqlQps = iterations * 1_000_000_000L / (t2 - t1);
        System.out.printf("  SQL (plan cached):     %,10d QPS  (%6.1f us/op)%n", cachedSqlQps, (t2 - t1) / 1000.0 / iterations);
        System.out.printf("  Plan cache size:       %d%n", SqlQueryEngine.planCacheSize());

        int threads = Runtime.getRuntime().availableProcessors();
        int perThread = iterations / threads;
        System.out.println();
        System.out.println("--- Multi-thread: " + threads + " threads x " + perThread + " iterations ---");

        AtomicLong totalCount = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(threads);

        t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        Object r = table.get("AAC001", sampleAAC001);
                        if (r != null) totalCount.incrementAndGet();
                    }
                } catch (Exception e) {}
                latch.countDown();
            }).start();
        }
        latch.await();
        t2 = System.nanoTime();
        long totalOps = (long) threads * perThread;
        long multiDirectQps = totalOps * 1_000_000_000L / (t2 - t1);
        System.out.printf("  get() concurrent:      %,10d QPS  (%6.1f us/op)%n", multiDirectQps, (t2 - t1) / 1000.0 / totalOps);

        AtomicLong sqlCount = new AtomicLong(0);
        CountDownLatch latch2 = new CountDownLatch(threads);

        t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        QueryResult r = SqlQueryEngine.query("select * from kca2 where AAC001 = '" + sampleAAC001 + "'");
                        if (r != null && r.rows != null) sqlCount.incrementAndGet();
                    }
                } catch (Exception e) {}
                latch2.countDown();
            }).start();
        }
        latch2.await();
        t2 = System.nanoTime();
        long multiSqlQps = totalOps * 1_000_000_000L / (t2 - t1);
        System.out.printf("  SQL concurrent:        %,10d QPS  (%6.1f us/op)%n", multiSqlQps, (t2 - t1) / 1000.0 / totalOps);

        System.out.println();
        System.out.println("--- Summary ---");
        System.out.printf("  Direct get():          %,10d QPS (single)%n", directQps);
        System.out.printf("  SQL plan cached:       %,10d QPS (single)  %.1fx slower than direct%n", cachedSqlQps, (double) directQps / cachedSqlQps);
        System.out.printf("  Direct concurrent:     %,10d QPS (%d threads)%n", multiDirectQps, threads);
        System.out.printf("  SQL concurrent:        %,10d QPS (%d threads)%n", multiSqlQps, threads);
    }
}
