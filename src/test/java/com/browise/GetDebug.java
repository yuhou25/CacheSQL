package com.browise;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class GetDebug {

    public static void main(String[] args) throws Exception {
        String[] cols = new String[]{"id", "name"};
        Table t = database.load("dbg", "dbg", new String[]{"id", "name"});
        t.indexColumnType().put("id", String.class);
        t.indexColumnType().put("name", String.class);
        for (int i = 0; i < 100; i++) {
            Row row = new Row(cols);
            row.set(0, String.format("%04d", i));
            row.set(1, "user_" + i);
            int idx = t.getData().getList().size();
            t.getData().append(row);
            t.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
            t.getIndex().get("name").insertOrUpdate("user_" + i, idx, false);
        }
        System.out.println("Loaded 100 rows, activeCount=" + t.getData().activeCount());

        System.out.println("\n--- Direct Table.get() test ---");
        Object r1 = t.get("id", "0001");
        System.out.println("get('id','0001') = " + r1);
        Object r2 = t.get("id", "0050");
        System.out.println("get('id','0050') = " + r2);
        Object r3 = t.get("id", "0099");
        System.out.println("get('id','0099') = " + r3);

        HttpCacheServer server = new HttpCacheServer(19999, 4);
        server.start();
        Thread.sleep(500);

        System.out.println("\n--- HTTP /cache/get test ---");
        String[][] tests = {
            {"0000", "first"},
            {"0001", "second"},
            {"0050", "middle"},
            {"0099", "last"},
            {"0100", "not exist"},
        };
        for (String[] test : tests) {
            String resp = httpGet("/cache/get?table=dbg&column=id&value=" + test[0]);
            System.out.println("  value=" + test[0] + " (" + test[1] + "): " + resp);
        }

        System.out.println("\n--- HTTP /cache/query SQL test ---");
        String sql = "select * from dbg where id = '0001'";
        String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
        System.out.println("  SQL result: " + resp);

        server.stop();
    }

    private static String httpGet(String path) throws Exception {
        URL url = new URL("http://127.0.0.1:19999" + path);
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
                return "HTTP " + conn.getResponseCode() + ": " + baos.toString("UTF-8");
            }
            return "ERROR: " + e.getMessage();
        } finally {
            conn.disconnect();
        }
    }

    private static String httpPost(String path, String body) throws Exception {
        URL url = new URL("http://127.0.0.1:19999" + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] data = body.getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(data.length));
        java.io.OutputStream os = conn.getOutputStream();
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
                return "HTTP " + conn.getResponseCode() + ": " + baos.toString("UTF-8");
            }
            return "ERROR: " + e.getMessage();
        } finally {
            conn.disconnect();
        }
    }
}
