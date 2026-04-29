package com.browise;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.browise.database.replication.ReplicationManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * ReplicationManager pendingQueue 缓冲+重放 测试
 * 
 * 验证核心同步机制:
 * 1. master 可达时 → 直接转发（pendingQueue 不积压）
 * 2. master 不可达 → 缓冲到 pendingQueue
 * 3. master 恢复 → flushPending 重放，数据到达 master
 * 
 * 注意：ROLE 是 static final，测试环境为 standalone。
 * forwardOrBuffer 是 slave 模式专用路径，本测试通过反射直接测试其内部机制。
 * 端到端测试（slave→master 真实同步）需启动两个独立 JVM 进程。
 */
public class ReplicationSyncTest {

	private HttpServer mockMaster;
	private AtomicInteger forwardCount;
	private AtomicReference<String> lastForwardedBody;

	@Before
	public void setUp() {
		forwardCount = new AtomicInteger(0);
		lastForwardedBody = new AtomicReference<String>(null);
		ReplicationManager.clearPending();
	}

	@After
	public void tearDown() {
		if (mockMaster != null) {
			mockMaster.stop(0);
			mockMaster = null;
		}
		ReplicationManager.clearPending();
	}

	@Test
	public void testPendingQueueEmpty() {
		assertEquals(0, ReplicationManager.getPendingCount());
		assertTrue(ReplicationManager.isMasterReachable());
	}

	@Test
	public void testClearPendingResetsState() {
		ReplicationManager.clearPending();
		assertEquals(0, ReplicationManager.getPendingCount());
		assertTrue(ReplicationManager.isMasterReachable());
	}

	@Test
	public void testDirectForwardSuccess() throws Exception {
		startMockMaster(200);
		String masterUrl = "http://127.0.0.1:" + mockMaster.getAddress().getPort();

		invokeForwardOrBuffer("insert", "test_table", "id", "001", null, masterUrl);

		assertEquals(1, forwardCount.get());
		assertEquals(0, ReplicationManager.getPendingCount());
	}

	@Test
	public void testBufferWhenMasterDown() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		invokeForwardOrBuffer("insert", "test_table", "id", "001", null, deadMasterUrl);

		assertFalse(ReplicationManager.isMasterReachable());
		assertEquals(1, ReplicationManager.getPendingCount());
	}

	@Test
	public void testBufferMultipleOps() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		for (int i = 1; i <= 5; i++) {
			HashMap<String, Object> data = new HashMap<String, Object>();
			data.put("name", "user_" + i);
			invokeForwardOrBuffer("insert", "test_table", "id", String.format("%03d", i), data, deadMasterUrl);
		}

		assertEquals(5, ReplicationManager.getPendingCount());
		assertFalse(ReplicationManager.isMasterReachable());
	}

	@Test
	public void testFlushPendingToRecoveredMaster() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		HashMap<String, Object> data = new HashMap<String, Object>();
		data.put("name", "buffered_user");
		invokeForwardOrBuffer("insert", "test_table", "id", "001", data, deadMasterUrl);
		assertEquals(1, ReplicationManager.getPendingCount());

		startMockMaster(200);
		String liveMasterUrl = "http://127.0.0.1:" + mockMaster.getAddress().getPort();
		setMasterUrl(liveMasterUrl);

		ReplicationManager.flushPendingPublic();

		assertEquals(0, ReplicationManager.getPendingCount());
		assertTrue(ReplicationManager.isMasterReachable());
		assertEquals(1, forwardCount.get());
	}

	@Test
	public void testFlushMultiplePendingOps() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		for (int i = 1; i <= 3; i++) {
			HashMap<String, Object> data = new HashMap<String, Object>();
			data.put("name", "user_" + i);
			invokeForwardOrBuffer("insert", "test_table", "id", String.format("%03d", i), data, deadMasterUrl);
		}
		assertEquals(3, ReplicationManager.getPendingCount());

		startMockMaster(200);
		String liveMasterUrl = "http://127.0.0.1:" + mockMaster.getAddress().getPort();
		setMasterUrl(liveMasterUrl);

		ReplicationManager.flushPendingPublic();

		assertEquals(0, ReplicationManager.getPendingCount());
		assertTrue(ReplicationManager.isMasterReachable());
		assertEquals(3, forwardCount.get());
	}

	@Test
	public void testFlushStopsOnMasterStillDown() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		for (int i = 1; i <= 3; i++) {
			invokeForwardOrBuffer("insert", "test_table", "id", String.format("%03d", i), null, deadMasterUrl);
		}
		assertEquals(3, ReplicationManager.getPendingCount());

		ReplicationManager.flushPendingPublic();

		assertEquals(3, ReplicationManager.getPendingCount());
		assertFalse(ReplicationManager.isMasterReachable());
	}

	@Test
	public void testUpdateBufferAndFlush() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		HashMap<String, Object> data = new HashMap<String, Object>();
		data.put("name", "updated_user");
		invokeForwardOrBuffer("update", "test_table", "id", "001", data, deadMasterUrl);
		assertEquals(1, ReplicationManager.getPendingCount());

		startMockMaster(200);
		setMasterUrl("http://127.0.0.1:" + mockMaster.getAddress().getPort());
		ReplicationManager.flushPendingPublic();

		assertEquals(0, ReplicationManager.getPendingCount());
		assertTrue(lastForwardedBody.get().contains("update"));
	}

	@Test
	public void testDeleteBufferAndFlush() throws Exception {
		ReplicationManager.clearPending();
		String deadMasterUrl = "http://127.0.0.1:19999";

		invokeForwardOrBuffer("delete", "test_table", "id", "001", null, deadMasterUrl);
		assertEquals(1, ReplicationManager.getPendingCount());

		startMockMaster(200);
		setMasterUrl("http://127.0.0.1:" + mockMaster.getAddress().getPort());
		ReplicationManager.flushPendingPublic();

		assertEquals(0, ReplicationManager.getPendingCount());
		assertEquals(1, forwardCount.get());
	}

	/* ========== helpers ========== */

	private void startMockMaster(final int responseCode) throws Exception {
		mockMaster = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		HttpHandler handler = new HttpHandler() {
			public void handle(HttpExchange exchange) throws IOException {
				forwardCount.incrementAndGet();
				byte[] buf = new byte[4096];
				int len = exchange.getRequestBody().read(buf);
				if (len > 0) {
					lastForwardedBody.set(new String(buf, 0, len, "UTF-8"));
				}
				byte[] resp = "{\"code\":0}".getBytes("UTF-8");
				exchange.sendResponseHeaders(responseCode, resp.length);
				OutputStream os = exchange.getResponseBody();
				os.write(resp);
				os.close();
			}
		};
		mockMaster.createContext("/cache/insert", handler);
		mockMaster.createContext("/cache/update", handler);
		mockMaster.createContext("/cache/delete", handler);
		mockMaster.setExecutor(null);
		mockMaster.start();
	}

	private static void invokeForwardOrBuffer(String op, String table, String indexColumn,
			String keyValue, HashMap<String, Object> data, String masterUrl) throws Exception {
		setMasterUrl(masterUrl);
		java.lang.reflect.Method m = ReplicationManager.class.getDeclaredMethod(
			"forwardOrBuffer", String.class, String.class, String.class, Object.class, HashMap.class);
		m.setAccessible(true);
		m.invoke(null, op, table, indexColumn, keyValue, data);
	}

	private static void setMasterUrl(String url) throws Exception {
		java.lang.reflect.Field f = ReplicationManager.class.getDeclaredField("masterUrl");
		f.setAccessible(true);
		java.lang.reflect.Field modifiers = java.lang.reflect.Field.class.getDeclaredField("modifiers");
		modifiers.setAccessible(true);
		modifiers.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
		f.set(null, url);
	}
}
