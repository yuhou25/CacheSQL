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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class HttpQpsBenchmark {

	private HttpCacheServer server;
	private static final int PORT = 18080;
	private static final int ROWS = 1000;

	@Before
	public void setUp() throws Exception {
		String[] cols = new String[]{"id", "name"};
		Table t = database.load("bench", "bench", new String[]{"id", "name"});
		t.indexColumnType().put("id", String.class);
		t.indexColumnType().put("name", String.class);
		for (int i = 0; i < ROWS; i++) {
			Row row = new Row(cols);
			row.set(0, String.format("%04d", i));
			row.set(1, "user_" + i);
			int idx = t.getData().getList().size();
			t.getData().append(row);
			t.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
			t.getIndex().get("name").insertOrUpdate("user_" + i, idx, false);
		}
		server = new HttpCacheServer(PORT, 32);
		server.start();
		Thread.sleep(300);
	}

	@After
	public void tearDown() {
		if (server != null) server.stop();
		database.removeTable("bench");
	}

	@Test
	public void benchSqlEq1Thread() throws Exception {
		warmup(1, 500);
		int n = 10000;
		long start = System.nanoTime();
		for (int i = 0; i < n; i++) {
			String sql = "select * from bench where id = '" + String.format("%04d", i % ROWS) + "'";
			String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
			assertOk(resp);
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] 1-thread SQL EQ: %.0f QPS (%d req, %d ms)%n", n * 1000.0 / ms, n, ms);
	}

	@Test
	public void benchSqlEq8Thread() throws Exception {
		warmup(4, 500);
		int threads = 8, perThread = 5000, total = threads * perThread;
		AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
		long start = System.nanoTime();
		CountDownLatch latch = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			final int tid = t;
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						String sql = "select * from bench where id = '" + String.format("%04d", (tid * perThread + i) % ROWS) + "'";
						String resp = httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
						if (resp.contains("\"code\":0")) ok.incrementAndGet();
						else fail.incrementAndGet();
					}
				} catch (Exception e) { fail.incrementAndGet(); }
				finally { latch.countDown(); }
			}).start();
		}
		latch.await();
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] %d-thread SQL EQ (short conn): %.0f QPS (ok=%d, fail=%d, %d ms)%n", threads, total * 1000.0 / ms, ok.get(), fail.get(), ms);
	}

	@Test
	public void benchSqlEq8ThreadKeepAlive() throws Exception {
		warmup(4, 500);
		int threads = 8, perThread = 5000, total = threads * perThread;
		AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
		long start = System.nanoTime();
		CountDownLatch latch = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			final int tid = t;
			new Thread(() -> {
				try {
					URL url = new URL("http://127.0.0.1:" + PORT + "/cache/query");
					for (int i = 0; i < perThread; i++) {
						String sql = "select * from bench where id = '" + String.format("%04d", (tid * perThread + i) % ROWS) + "'";
						String resp = httpPostKeepAlive(url, "sql=" + URLEncoder.encode(sql, "UTF-8"));
						if (resp.contains("\"code\":0")) ok.incrementAndGet();
						else fail.incrementAndGet();
					}
				} catch (Exception e) { fail.incrementAndGet(); }
				finally { latch.countDown(); }
			}).start();
		}
		latch.await();
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] %d-thread SQL EQ (keep-alive): %.0f QPS (ok=%d, fail=%d, %d ms)%n", threads, total * 1000.0 / ms, ok.get(), fail.get(), ms);
	}

	@Test
	public void benchGetEndpoint1Thread() throws Exception {
		warmup(1, 500);
		int n = 10000;
		long start = System.nanoTime();
		for (int i = 0; i < n; i++) {
			String resp = httpGet("/cache/get?table=bench&column=id&value=" + String.format("%04d", i % ROWS));
			assertOk(resp);
		}
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] 1-thread /cache/get: %.0f QPS (%d req, %d ms)%n", n * 1000.0 / ms, n, ms);
	}

	@Test
	public void benchGetEndpoint8Thread() throws Exception {
		warmup(4, 500);
		int threads = 8, perThread = 5000, total = threads * perThread;
		AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
		long start = System.nanoTime();
		CountDownLatch latch = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			final int tid = t;
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						String resp = httpGet("/cache/get?table=bench&column=id&value=" + String.format("%04d", (tid * perThread + i) % ROWS));
						if (resp.contains("\"code\":0")) ok.incrementAndGet();
						else fail.incrementAndGet();
					}
				} catch (Exception e) { fail.incrementAndGet(); }
				finally { latch.countDown(); }
			}).start();
		}
		latch.await();
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] %d-thread /cache/get: %.0f QPS (ok=%d, fail=%d, %d ms)%n", threads, total * 1000.0 / ms, ok.get(), fail.get(), ms);
	}

	@Test
	public void benchRange8Thread() throws Exception {
		warmup(4, 500);
		int threads = 8, perThread = 5000, total = threads * perThread;
		AtomicInteger ok = new AtomicInteger(0), fail = new AtomicInteger(0);
		long start = System.nanoTime();
		CountDownLatch latch = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			final int tid = t;
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						int from = (tid * perThread + i) % (ROWS - 10);
						String resp = httpGet("/cache/range?table=bench&column=id&from=" + String.format("%04d", from) + "&to=" + String.format("%04d", from + 10));
						if (resp.contains("\"code\":0")) ok.incrementAndGet();
						else fail.incrementAndGet();
					}
				} catch (Exception e) { fail.incrementAndGet(); }
				finally { latch.countDown(); }
			}).start();
		}
		latch.await();
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] %d-thread RANGE: %.0f QPS (ok=%d, fail=%d, %d ms)%n", threads, total * 1000.0 / ms, ok.get(), fail.get(), ms);
	}

	@Test
	public void benchMetrics() throws Exception {
		warmup(1, 200);
		int n = 5000;
		long start = System.nanoTime();
		for (int i = 0; i < n; i++) httpGet("/cache/metrics");
		long ms = (System.nanoTime() - start) / 1_000_000;
		System.out.printf("[HTTP] 1-thread /cache/metrics: %.0f QPS%n", n * 1000.0 / ms);
	}

	private void assertOk(String resp) {
		if (!resp.contains("\"code\":0")) throw new RuntimeException("bad resp: " + resp.substring(0, Math.min(200, resp.length())));
	}

	private void warmup(int threads, int perThread) throws Exception {
		CountDownLatch latch = new CountDownLatch(threads);
		for (int t = 0; t < threads; t++) {
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						String sql = "select * from bench where id = '" + String.format("%04d", i % ROWS) + "'";
						httpPost("/cache/query", "sql=" + URLEncoder.encode(sql, "UTF-8"));
					}
				} catch (Exception e) {}
				finally { latch.countDown(); }
			}).start();
		}
		latch.await();
	}

	private String httpGet(String path) throws Exception {
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
		} catch (IOException e) {
			InputStream es = conn.getErrorStream();
			if (es != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int len;
				while ((len = es.read(buf)) != -1) baos.write(buf, 0, len);
				es.close();
				String body = baos.toString("UTF-8");
				throw new IOException(e.getMessage() + " body=" + body, e);
			}
			throw e;
		} finally {
			conn.disconnect();
		}
	}

	private String httpPost(String path, String body) throws Exception {
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
		} catch (IOException e) {
			InputStream es = conn.getErrorStream();
			if (es != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				byte[] buf = new byte[4096];
				int len;
				while ((len = es.read(buf)) != -1) baos.write(buf, 0, len);
				es.close();
				throw new IOException(e.getMessage() + " body=" + baos.toString("UTF-8"), e);
			}
			throw e;
		} finally {
			conn.disconnect();
		}
	}

	private String sqlUrl(String sql) throws Exception {
		return "/cache/query?sql=" + URLEncoder.encode(sql, "UTF-8");
	}

	private String httpPostKeepAlive(URL url, String body) throws Exception {
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
		InputStream is = conn.getInputStream();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int len;
		while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
		is.close();
		return baos.toString("UTF-8");
	}
}
