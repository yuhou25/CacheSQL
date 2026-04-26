package com.browise;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class GetFailDebug {

    private static final int ROWS = 10000;
    private static final int PORT = 19998;

    public static void main(String[] args) throws Exception {
        String[] cols = new String[]{"id", "name", "age", "city"};
        Table table = database.load("faildbg", "faildbg", new String[]{"id", "name"});
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
        System.out.println("Loaded " + ROWS + " rows");

        HttpCacheServer server = new HttpCacheServer(PORT, 4);
        server.start();
        Thread.sleep(500);

        System.out.println("\n--- Sequential /get test (10000 requests) ---");
        int ok = 0, fail = 0;
        String firstFailResp = null;
        String firstFailUrl = null;
        for (int i = 0; i < 10000; i++) {
            String url = "/cache/get?table=faildbg&column=id&value=" + String.format("%04d", i % ROWS);
            String resp = httpGet(url);
            boolean isOk = resp.contains("\"code\":0");
            if (isOk) ok++; else {
                fail++;
                if (firstFailResp == null) {
                    firstFailResp = resp;
                    firstFailUrl = url;
                    System.out.println("  FIRST FAIL at i=" + i + " url=" + url);
                    System.out.println("  resp=" + resp);
                }
            }
        }
        System.out.println("Result: ok=" + ok + " fail=" + fail);
        if (firstFailResp != null) {
            System.out.println("First fail URL: " + firstFailUrl);
            System.out.println("First fail resp: " + firstFailResp);
        }

        System.out.println("\n--- SQL query for same keys ---");
        ok = 0; fail = 0;
        for (int i = 0; i < 100; i++) {
            String sql = "select * from faildbg where id = '" + String.format("%04d", i % ROWS) + "'";
            String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
            boolean isOk = resp.contains("\"code\":0");
            if (isOk) ok++; else fail++;
        }
        System.out.println("Result: ok=" + ok + " fail=" + fail);

        System.out.println("\n--- Concurrent /get test (4 threads x 100) ---");
        int threads = 4, perThread = 100;
        AtomicInteger ok2 = new AtomicInteger(0), fail2 = new AtomicInteger(0);
        AtomicInteger printed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            new Thread(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String urlStr = "/cache/get?table=faildbg&column=id&value=" + String.format("%04d", (tid * perThread + i) % ROWS);
                        String resp = httpGet(urlStr);
                        boolean isOk = resp.contains("\"code\":0");
                        if (isOk) ok2.incrementAndGet(); else {
                            fail2.incrementAndGet();
                            int p = printed.incrementAndGet();
                            if (p <= 5) {
                                System.out.println("  FAIL [" + tid + "-" + i + "] value=" + String.format("%04d", (tid * perThread + i) % ROWS) + " resp=" + resp.substring(0, Math.min(200, resp.length())));
                            }
                        }
                    }
                } catch (Exception e) {
                    fail2.incrementAndGet();
                    int p = printed.incrementAndGet();
                    if (p <= 5) System.out.println("  EXCEPTION [" + tid + "]: " + e.getMessage());
                }
                finally { latch.countDown(); }
            }).start();
        }
        latch.await();
        System.out.println("Result: ok=" + ok2.get() + " fail=" + fail2.get());

        server.stop();
    }

    private static String httpGet(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:" + PORT + path);
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
                String body = baos.toString("UTF-8");
                return "HTTP" + conn.getResponseCode() + ":" + body;
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:" + PORT + path);
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
        } catch (Exception e) {
            InputStream es = conn.getErrorStream();
            if (es != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = es.read(buf)) != -1) baos.write(buf, 0, len);
                es.close();
                return "HTTP" + conn.getResponseCode() + ":" + baos.toString("UTF-8");
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }
}
