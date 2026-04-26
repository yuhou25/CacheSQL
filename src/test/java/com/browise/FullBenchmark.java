package com.browise;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class FullBenchmark {

    private static final int ROWS = 10000;
    private static final int WARMUP = 1000;
    private static final int ITERATIONS = 50000;
    private static final int HTTP_PORT = 19090;

    public static void main(String[] args) throws Exception {
        printJvmInfo();

        String[] cols = new String[]{"id", "name", "age", "city"};
        Table table = database.load("bench", "bench", new String[]{"id", "name", "age"});
        table.indexColumnType().put("id", String.class);
        table.indexColumnType().put("name", String.class);
        table.indexColumnType().put("age", Long.class);
        for (int i = 0; i < ROWS; i++) {
            Row row = new Row(cols);
            row.set(0, String.format("%04d", i));
            row.set(1, "user_" + (i % 100));
            row.set(2, 20 + (i % 50));
            row.set(3, i % 2 == 0 ? "Beijing" : "Shanghai");
            int idx = table.getData().getList().size();
            table.getData().append(row);
            table.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
            table.getIndex().get("name").insertOrUpdate("user_" + (i % 100), idx, false);
            table.getIndex().get("age").insertOrUpdate((long)(20 + i % 50), idx, false);
        }
        System.out.println("Loaded " + ROWS + " rows into 'bench' table");
        System.out.println();

        System.out.println("========================================");
        System.out.println("  Part 1: Embedded Performance");
        System.out.println("========================================");
        System.out.println();

        warmupEmbedded(table);

        benchmarkEmbeddedGet(table);
        benchmarkEmbeddedSqlEq(table);
        benchmarkEmbeddedSqlRange(table);
        benchmarkEmbeddedSqlLike(table);

        int cores = Runtime.getRuntime().availableProcessors();
        benchmarkEmbeddedGetConcurrent(table, cores);
        benchmarkEmbeddedSqlEqConcurrent(table, cores);

        System.out.println();
        System.out.println("========================================");
        System.out.println("  Part 2: HTTP Performance");
        System.out.println("========================================");
        System.out.println();

        HttpCacheServer server = new HttpCacheServer(HTTP_PORT, 32);
        server.start();
        Thread.sleep(500);
        try {
            warmupHttp();

        benchmarkHttpSqlEq1Thread();
        benchmarkHttpSqlEq8Thread();
        } finally {
            server.stop();
        }

        System.out.println();
        System.out.println("========================================");
        System.out.println("  DONE");
        System.out.println("========================================");
    }

    private static void printJvmInfo() {
        System.out.println("JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.println("Java: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        System.out.println("CPU cores: " + Runtime.getRuntime().availableProcessors());
        long maxMem = Runtime.getRuntime().maxMemory();
        System.out.printf("Max heap: %d MB%n", maxMem / 1024 / 1024);
        System.out.println();
    }

    private static void warmupEmbedded(Table table) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            table.get("id", String.format("%04d", i % ROWS));
            SqlQueryEngine.query("select * from bench where id = '" + String.format("%04d", i % ROWS) + "'");
        }
    }

    private static void benchmarkEmbeddedGet(Table table) throws Exception {
        long t1 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            table.get("id", String.format("%04d", i % ROWS));
        }
        long t2 = System.nanoTime();
        long qps = ITERATIONS * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / ITERATIONS;
        System.out.printf("[Embedded] get() single:           %,10d QPS  (%6.2f us/op)%n", qps, usPerOp);
    }

    private static void benchmarkEmbeddedSqlEq(Table table) throws Exception {
        long t1 = System.nanoTime();
        for (int i = 0; i < ITERATIONS; i++) {
            SqlQueryEngine.query("select * from bench where id = '" + String.format("%04d", i % ROWS) + "'");
        }
        long t2 = System.nanoTime();
        long qps = ITERATIONS * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / ITERATIONS;
        System.out.printf("[Embedded] SQL EQ single:          %,10d QPS  (%6.2f us/op)%n", qps, usPerOp);
    }

    private static void benchmarkEmbeddedSqlRange(Table table) throws Exception {
        int iters = 20000;
        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            SqlQueryEngine.query("select * from bench where id >= '0050' and id <= '0060'");
        }
        long t2 = System.nanoTime();
        long qps = iters * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / iters;
        System.out.printf("[Embedded] SQL RANGE single:       %,10d QPS  (%6.2f us/op)%n", qps, usPerOp);
    }

    private static void benchmarkEmbeddedSqlLike(Table table) throws Exception {
        int iters = 20000;
        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            SqlQueryEngine.query("select * from bench where name like 'user_1%'");
        }
        long t2 = System.nanoTime();
        long qps = iters * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / iters;
        System.out.printf("[Embedded] SQL LIKE single:        %,10d QPS  (%6.2f us/op)%n", qps, usPerOp);
    }

    private static void benchmarkEmbeddedGetConcurrent(Table table, int threads) throws Exception {
        int perThread = ITERATIONS / threads;
        long totalOps = (long) threads * perThread;
        AtomicLong count = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        Object r = table.get("id", String.format("%04d", (tid * perThread + i) % ROWS));
                        if (r != null) count.incrementAndGet();
                    }
                } catch (Exception e) {}
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = totalOps * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / totalOps;
        System.out.printf("[Embedded] get() %d-thread:        %,10d QPS  (%6.2f us/op)%n", threads, qps, usPerOp);
    }

    private static void benchmarkEmbeddedSqlEqConcurrent(Table table, int threads) throws Exception {
        int perThread = ITERATIONS / threads;
        long totalOps = (long) threads * perThread;
        AtomicLong count = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        QueryResult r = SqlQueryEngine.query("select * from bench where id = '" + String.format("%04d", (tid * perThread + i) % ROWS) + "'");
                        if (r != null && r.rows != null) count.incrementAndGet();
                    }
                } catch (Exception e) {}
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = totalOps * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / totalOps;
        System.out.printf("[Embedded] SQL EQ %d-thread:       %,10d QPS  (%6.2f us/op)%n", threads, qps, usPerOp);
    }

    private static void warmupHttp() throws Exception {
        CountDownLatch latch = new CountDownLatch(4);
        for (int t = 0; t < 4; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 200; i++) {
                        String sql = "select * from bench where id = '" + String.format("%04d", i % ROWS) + "'";
                        httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
                    }
                } catch (Exception e) {}
                latch.countDown();
            }).start();
        }
        latch.await();
    }

    private static void benchmarkHttpSqlEq1Thread() throws Exception {
        int n = 5000;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String sql = "select * from bench where id = '" + String.format("%04d", i % ROWS) + "'";
            String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
            if (!resp.contains("\"code\":0")) throw new RuntimeException("bad: " + resp);
        }
        long t2 = System.nanoTime();
        long qps = n * 1_000_000_000L / (t2 - t1);
        System.out.printf("[HTTP] 1-thread SQL EQ:            %,10d QPS  (%6d ms total)%n", qps, (t2 - t1) / 1_000_000);
    }

    private static void benchmarkHttpSqlEq8Thread() throws Exception {
        int threads = 8, perThread = 2000;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String sql = "select * from bench where id = '" + String.format("%04d", (tid * perThread + i) % ROWS) + "'";
                        String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("[HTTP] 8-thread SQL EQ:            %,10d QPS  (ok=%d fail=%d %d ms)%n", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static void benchmarkHttpGet1Thread() throws Exception {
        int n = 5000;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String resp = httpGet("/cache/get?table=bench&column=id&value=" + String.format("%04d", i % ROWS));
            if (!resp.contains("\"code\":0")) throw new RuntimeException("bad: " + resp);
        }
        long t2 = System.nanoTime();
        long qps = n * 1_000_000_000L / (t2 - t1);
        System.out.printf("[HTTP] 1-thread /cache/get:        %,10d QPS  (%6d ms total)%n", qps, (t2 - t1) / 1_000_000);
    }

    private static void benchmarkHttpGet8Thread() throws Exception {
        int threads = 8, perThread = 2000;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String resp = httpGet("/cache/get?table=bench&column=id&value=" + String.format("%04d", (tid * perThread + i) % ROWS));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("[HTTP] 8-thread /cache/get:        %,10d QPS  (ok=%d fail=%d %d ms)%n", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static void benchmarkHttpRange8Thread() throws Exception {
        int threads = 8, perThread = 2000;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        int from = (tid * perThread + i) % (ROWS - 10);
                        String resp = httpGet("/cache/range?table=bench&column=id&from=" + String.format("%04d", from) + "&to=" + String.format("%04d", from + 10));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("[HTTP] 8-thread RANGE:             %,10d QPS  (ok=%d fail=%d %d ms)%n", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static String httpGet(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + HTTP_PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try {
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();
            return baos.toString("UTF-8");
        } catch (IOException e) {
            InputStream es = conn.getErrorStream();
            if (es != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = es.read(buf)) != -1) baos.write(buf, 0, len);
                es.close();
                return baos.toString("UTF-8");
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + HTTP_PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] data = body.getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(data.length));
        OutputStream os = conn.getOutputStream();
        os.write(data);
        os.close();
        try {
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            is.close();
            return baos.toString("UTF-8");
        } catch (IOException e) {
            InputStream es = conn.getErrorStream();
            if (es != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = es.read(buf)) != -1) baos.write(buf, 0, len);
                es.close();
                return baos.toString("UTF-8");
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }
}
