package com.browise;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class WriteBenchmark {

    private static final int ROWS = 10000;
    private static final int WRITE_ITERATIONS = 50000;

    public static void main(String[] args) throws Exception {
        System.out.println("===== Write Benchmark =====");

        String[] cols = new String[]{"id", "name", "age", "city"};
        Table table = database.load("write_bench", "write_bench", new String[]{"id", "name"});
        table.indexColumnType().put("id", String.class);
        table.indexColumnType().put("name", String.class);

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
        }
        System.out.println("Preloaded " + ROWS + " rows");
        System.out.println();

        int cores = Runtime.getRuntime().availableProcessors();

        System.out.println("--- Single thread write ---");
        benchInsertSingle(table);
        benchUpdateSingle(table);
        benchDeleteInsertSingle(table);
        benchMixedSingle(table);

        System.out.println();
        System.out.println("--- Concurrent write (" + cores + " threads) ---");
        benchInsertConcurrent(table, cores);
        benchUpdateConcurrent(table, cores);
        benchMixedConcurrent(table, cores);

        System.out.println();
        System.out.println("--- High contention: 16 threads, same 100 keys ---");
        benchHighContention(table, 16);

        System.out.println();
        System.out.println("========================================");
        System.out.println("  Part 2: HTTP Write Performance");
        System.out.println("========================================");
        System.out.println();

        HttpCacheServer httpServer = new HttpCacheServer(19092, 32);
        httpServer.start();
        Thread.sleep(500);
        try {
            benchHttpInsert1Thread();
            benchHttpInsert8Thread();
            benchHttpUpdate1Thread();
            benchHttpUpdate8Thread();
            benchHttpMixed8Thread();
        } finally {
            httpServer.stop();
        }

        System.out.println();
        System.out.println("===== DONE =====");
    }

    private static HashMap<String, Object> makeData(String id, String name, int age) {
        HashMap<String, Object> data = new HashMap<String, Object>();
        data.put("id", id);
        data.put("name", name);
        data.put("age", age);
        data.put("city", "WriteCity");
        return data;
    }

    private static void benchInsertSingle(Table table) throws Exception {
        long t1 = System.nanoTime();
        for (int i = 0; i < WRITE_ITERATIONS; i++) {
            String id = String.format("%05d", 10000 + i % 10000);
            table.insert("id", id, makeData(id, "new_" + i, 20 + i % 30));
        }
        long t2 = System.nanoTime();
        printResult("insert (single)", WRITE_ITERATIONS, t1, t2);
    }

    private static void benchUpdateSingle(Table table) throws Exception {
        long t1 = System.nanoTime();
        for (int i = 0; i < WRITE_ITERATIONS; i++) {
            String id = String.format("%04d", i % ROWS);
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("age", 99);
            data.put("city", "Updated");
            table.update("id", id, data);
        }
        long t2 = System.nanoTime();
        printResult("update (single)", WRITE_ITERATIONS, t1, t2);
    }

    private static void benchDeleteInsertSingle(Table table) throws Exception {
        int iters = 10000;
        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            String id = String.format("%04d", i % ROWS);
            table.delete("id", id);
            table.insert("id", id, makeData(id, "reborn_" + i, 20 + i % 30));
        }
        long t2 = System.nanoTime();
        printResult("delete+insert (single)", iters, t1, t2);
    }

    private static void benchMixedSingle(Table table) throws Exception {
        int iters = 30000;
        long t1 = System.nanoTime();
        for (int i = 0; i < iters; i++) {
            String id = String.format("%04d", i % ROWS);
            int op = i % 3;
            if (op == 0) {
                table.insert("id", id, makeData(id, "mix_" + i, 20 + i % 30));
            } else if (op == 1) {
                HashMap<String, Object> data = new HashMap<String, Object>();
                data.put("age", 88);
                table.update("id", id, data);
            } else {
                table.delete("id", id);
            }
        }
        long t2 = System.nanoTime();
        printResult("mixed r/w (single)", iters, t1, t2);
    }

    private static void benchInsertConcurrent(Table table, int threads) throws Exception {
        int perThread = WRITE_ITERATIONS / threads;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = String.format("%05d", 20000 + (tid * perThread + i) % 10000);
                        table.insert("id", id, makeData(id, "cnew_" + tid + "_" + i, 20 + i % 30));
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult("insert (" + threads + " threads)", total, t1, t2);
    }

    private static void benchUpdateConcurrent(Table table, int threads) throws Exception {
        int perThread = WRITE_ITERATIONS / threads;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = String.format("%04d", (tid * perThread + i) % ROWS);
                        HashMap<String, Object> data = new HashMap<String, Object>();
                        data.put("age", tid * 100 + i);
                        table.update("id", id, data);
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult("update (" + threads + " threads)", total, t1, t2);
    }

    private static void benchMixedConcurrent(Table table, int threads) throws Exception {
        int perThread = 20000 / threads;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = String.format("%04d", (tid * perThread + i) % ROWS);
                        int op = (tid + i) % 3;
                        if (op == 0) {
                            table.insert("id", id, makeData(id, "cmix_" + i, 20));
                        } else if (op == 1) {
                            HashMap<String, Object> data = new HashMap<String, Object>();
                            data.put("age", tid);
                            table.update("id", id, data);
                        } else {
                            table.delete("id", id);
                        }
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult("mixed r/w (" + threads + " threads)", total, t1, t2);
    }

    private static void benchHighContention(Table table, int threads) throws Exception {
        int perThread = 5000;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = String.format("%04d", i % 100);
                        table.update("id", id, makeData(id, "hot_" + tid + "_" + i, tid * 100 + i));
                        ok.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                latch.countDown();
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult("hotspot 100 keys (" + threads + " threads)", total, t1, t2);
    }

    private static void printResult(String label, long ops, long t1, long t2) {
        long qps = ops * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / ops;
        System.out.printf("  %-35s %,10d QPS  (%6.2f us/op)%n", label, qps, usPerOp);
    }

    private static final int HTTP_PORT = 19092;

    private static void benchHttpInsert1Thread() throws Exception {
        int n = 3000;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String id = String.format("%04d", i % ROWS);
            String body = "table=write_bench&column=id&value=" + id + "&name=http_" + i + "&age=25";
            String resp = httpPost("/cache/insert", body);
        }
        long t2 = System.nanoTime();
        long qps = n * 1_000_000_000L / (t2 - t1);
        System.out.printf("  %-35s %,10d QPS  (%6d ms total)%n", "HTTP insert (1 thread)", qps, (t2 - t1) / 1_000_000);
    }

    private static void benchHttpInsert8Thread() throws Exception {
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
                        String id = String.format("%04d", (tid * perThread + i) % ROWS);
                        String body = "table=write_bench&column=id&value=" + id + "&name=htc" + tid + "&age=25";
                        String resp = httpPost("/cache/insert", body);
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("  %-35s %,10d QPS  (ok=%d fail=%d %d ms)%n", "HTTP insert (8 threads)", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static void benchHttpUpdate1Thread() throws Exception {
        int n = 3000;
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String id = String.format("%04d", i % ROWS);
            String body = "table=write_bench&column=id&value=" + id + "&age=99";
            httpPost("/cache/update", body);
        }
        long t2 = System.nanoTime();
        long qps = n * 1_000_000_000L / (t2 - t1);
        System.out.printf("  %-35s %,10d QPS  (%6d ms total)%n", "HTTP update (1 thread)", qps, (t2 - t1) / 1_000_000);
    }

    private static void benchHttpUpdate8Thread() throws Exception {
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
                        String id = String.format("%04d", (tid * perThread + i) % ROWS);
                        String body = "table=write_bench&column=id&value=" + id + "&age=" + (tid * 100 + i);
                        String resp = httpPost("/cache/update", body);
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("  %-35s %,10d QPS  (ok=%d fail=%d %d ms)%n", "HTTP update (8 threads)", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static void benchHttpMixed8Thread() throws Exception {
        int threads = 8, perThread = 1500;
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = String.format("%04d", (tid * perThread + i) % ROWS);
                        int op = (tid + i) % 3;
                        String resp;
                        if (op == 0) {
                            resp = httpPost("/cache/insert", "table=write_bench&column=id&value=" + id + "&name=mix_" + i + "&age=" + (20 + i % 30));
                        } else if (op == 1) {
                            resp = httpPost("/cache/update", "table=write_bench&column=id&value=" + id + "&age=" + (tid * 100 + i));
                        } else {
                            resp = httpPost("/cache/delete", "table=write_bench&column=id&value=" + id);
                        }
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        long qps = total * 1_000_000_000L / (t2 - t1);
        System.out.printf("  %-35s %,10d QPS  (ok=%d fail=%d %d ms)%n", "HTTP mixed r/w (8 threads)", qps, ok.get(), fail.get(), (t2 - t1) / 1_000_000);
    }

    private static String httpPost(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + HTTP_PORT + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
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
