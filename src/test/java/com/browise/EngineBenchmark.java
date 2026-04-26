package com.browise;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.server.HttpServerEngine;
import com.browise.database.server.JdkEngine;
import com.browise.database.server.UndertowEngine;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class EngineBenchmark {

    private static final int ROWS = 10000;
    private static final int HTTP_PORT_BIO = 19080;
    private static final int HTTP_PORT_NIO = 19081;

    public static void main(String[] args) throws Exception {
        System.out.println("========================================================");
        System.out.println("  CacheSQL HTTP Engine Benchmark: BIO (JDK) vs NIO (Undertow)");
        System.out.println("========================================================");
        System.out.println();

        String[] cols = new String[]{"id", "name", "age", "city"};
        Table table = database.load("engine_bench", "engine_bench", new String[]{"id", "name"});
        table.indexColumnType().put("id", String.class);
        table.indexColumnType().put("name", String.class);
        for (int i = 0; i < ROWS; i++) {
            Row row = new Row(cols);
            row.set(0, String.format("%04d", i));
            row.set(1, "user_" + i);
            row.set(2, 20 + (i % 50));
            row.set(3, i % 2 == 0 ? "Beijing" : "Shanghai");
            int idx = table.getData().getList().size();
            table.getData().append(row);
            table.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
            table.getIndex().get("name").insertOrUpdate("user_" + i, idx, false);
        }
        System.out.println("Preloaded " + ROWS + " rows into 'engine_bench'");
        System.out.println();

        int[] threadCounts = {1, 4, 8, 16, 32};
        int requests = 10000;

        System.out.println("================ Part 1: Read Performance ================");
        System.out.println();

        for (int threads : threadCounts) {
            System.out.println("--- " + threads + " threads, " + requests + " reqs/thread ---");

            System.out.println("\n  [BIO - JDK HttpServer]");
            HttpCacheServer bioServer = new HttpCacheServer(HTTP_PORT_BIO, threads);
            bioServer.start();
            Thread.sleep(500);
            warmup(HTTP_PORT_BIO, 2, 500);
            benchSqlRead(HTTP_PORT_BIO, threads, requests, "BIO");
            benchGetRead(HTTP_PORT_BIO, threads, requests, "BIO");
            benchRangeRead(HTTP_PORT_BIO, threads, requests, "BIO");
            bioServer.stop();
            Thread.sleep(300);

            System.out.println("\n  [NIO - Undertow]");
            HttpCacheServer nioServer = createNioServer(HTTP_PORT_NIO, threads);
            nioServer.start();
            Thread.sleep(500);
            warmup(HTTP_PORT_NIO, 2, 500);
            benchSqlRead(HTTP_PORT_NIO, threads, requests, "NIO");
            benchGetRead(HTTP_PORT_NIO, threads, requests, "NIO");
            benchRangeRead(HTTP_PORT_NIO, threads, requests, "NIO");
            nioServer.stop();
            Thread.sleep(300);

            System.out.println();
        }

        System.out.println("================ Part 2: Write Performance ================");
        System.out.println();

        for (int threads : threadCounts) {
            int writeReqs = threads <= 4 ? 3000 : 2000;
            System.out.println("--- " + threads + " threads, " + writeReqs + " reqs/thread ---");

            System.out.println("\n  [BIO - JDK HttpServer]");
            HttpCacheServer bioServer = new HttpCacheServer(HTTP_PORT_BIO, threads);
            bioServer.start();
            Thread.sleep(500);
            benchHttpInsert(HTTP_PORT_BIO, threads, writeReqs, "BIO");
            benchHttpUpdate(HTTP_PORT_BIO, threads, writeReqs, "BIO");
            benchHttpMixed(HTTP_PORT_BIO, threads, writeReqs, "BIO");
            bioServer.stop();
            Thread.sleep(300);

            System.out.println("\n  [NIO - Undertow]");
            HttpCacheServer nioServer = createNioServer(HTTP_PORT_NIO, threads);
            nioServer.start();
            Thread.sleep(500);
            benchHttpInsert(HTTP_PORT_NIO, threads, writeReqs, "NIO");
            benchHttpUpdate(HTTP_PORT_NIO, threads, writeReqs, "NIO");
            benchHttpMixed(HTTP_PORT_NIO, threads, writeReqs, "NIO");
            nioServer.stop();
            Thread.sleep(300);

            System.out.println();
        }

        System.out.println("================ Summary Complete ================");
        database.removeTable("engine_bench");
    }

    private static HttpCacheServer createNioServer(int port, int threads) throws Exception {
        UndertowEngine engine = new UndertowEngine();
        return new HttpCacheServer(port, threads, engine);
    }

    private static void benchSqlRead(int port, int threads, int perThread, String label) throws Exception {
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String sql = "select * from engine_bench where id = '" + String.format("%04d", (tid * perThread + i) % ROWS) + "'";
                        String resp = httpPost(port, "/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " SQL EQ read (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void benchGetRead(int port, int threads, int perThread, String label) throws Exception {
        long total = (long) threads * perThread;
        AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        long t1 = System.nanoTime();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String resp = httpGet(port, "/cache/get?table=engine_bench&column=id&value=" + String.format("%04d", (tid * perThread + i) % ROWS));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " /get read (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void benchRangeRead(int port, int threads, int perThread, String label) throws Exception {
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
                        String resp = httpGet(port, "/cache/range?table=engine_bench&column=id&from=" + String.format("%04d", from) + "&to=" + String.format("%04d", from + 10));
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " range read (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void benchHttpInsert(int port, int threads, int perThread, String label) throws Exception {
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
                        String body = "table=engine_bench&column=id&value=" + id + "&name=ins_" + tid + "_" + i + "&age=25";
                        String resp = httpPost(port, "/cache/insert", body);
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " insert (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void benchHttpUpdate(int port, int threads, int perThread, String label) throws Exception {
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
                        String body = "table=engine_bench&column=id&value=" + id + "&age=" + (tid * 100 + i);
                        String resp = httpPost(port, "/cache/update", body);
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " update (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void benchHttpMixed(int port, int threads, int perThread, String label) throws Exception {
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
                            resp = httpPost(port, "/cache/insert", "table=engine_bench&column=id&value=" + id + "&name=mix_" + i + "&age=" + (20 + i % 30));
                        } else if (op == 1) {
                            resp = httpPost(port, "/cache/update", "table=engine_bench&column=id&value=" + id + "&age=" + (tid * 100 + i));
                        } else {
                            resp = httpPost(port, "/cache/delete", "table=engine_bench&column=id&value=" + id);
                        }
                        if (resp.contains("\"code\":0")) ok.incrementAndGet(); else fail.incrementAndGet();
                    }
                } catch (Exception e) { fail.incrementAndGet(); }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        long t2 = System.nanoTime();
        printResult(label + " mixed r/w (" + threads + "T)", total, t1, t2, ok.get(), fail.get());
    }

    private static void warmup(int port, int threads, int perThread) throws Exception {
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        httpGet(port, "/cache/get?table=engine_bench&column=id&value=" + String.format("%04d", i % ROWS));
                    }
                } catch (Exception e) {}
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
    }

    private static void printResult(String label, long ops, long t1, long t2, int ok, int fail) {
        long qps = ops * 1_000_000_000L / (t2 - t1);
        double usPerOp = (t2 - t1) / 1000.0 / ops;
        System.out.printf("    %-42s %,10d QPS  (%6.1f us/op)  ok=%d fail=%d%n", label, qps, usPerOp, ok, fail);
    }

    private static String httpGet(int port, String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
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
        } catch (Exception e) {
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

    private static String httpPost(int port, String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + port + path);
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
        } catch (Exception e) {
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
