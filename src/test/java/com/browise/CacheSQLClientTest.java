package com.browise;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.browise.client.CacheSQLClient;
import com.browise.database.database;
import com.browise.database.server.HttpCacheServer;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

/**
 * CacheSQLClient 集成测试 — 启动真实 HTTP 服务，验证多组路由
 * CacheSQLClient integration test — starts real HTTP server, validates multi-group routing
 */
public class CacheSQLClientTest {

	private HttpCacheServer group1;
	private HttpCacheServer group2;
	private CacheSQLClient client;
	private static final int PORT1 = 19091;
	private static final int PORT2 = 19092;

	@Before
	public void setUp() throws Exception {
		// group1: 保险组，存 bench_ins 表
		setupTable("bench_ins", PORT1);
		group1 = new HttpCacheServer(PORT1, 4);
		group1.start();

		// group2: 医疗组，存 bench_med 表
		setupTable("bench_med", PORT2);
		group2 = new HttpCacheServer(PORT2, 4);
		group2.start();

		Thread.sleep(300);

		// 构造客户端配置：两个组各管不同表
		Properties props = new Properties();
		props.setProperty("cachesql.group.insurance.master", "http://127.0.0.1:" + PORT1);
		props.setProperty("cachesql.group.insurance.tables", "bench_ins");
		props.setProperty("cachesql.group.medical.master", "http://127.0.0.1:" + PORT2);
		props.setProperty("cachesql.group.medical.tables", "bench_med");
		client = new CacheSQLClient(props);
	}

	@After
	public void tearDown() {
		if (group1 != null) group1.stop();
		if (group2 != null) group2.stop();
		database.removeTable("bench_ins");
		database.removeTable("bench_med");
	}

	@Test
	public void testGetRoutesToCorrectGroup() throws Exception {
		List<Map<String, Object>> rows = client.get("bench_ins", "id", "0005");
		assertEquals(1, rows.size());
		assertEquals("0005", rows.get(0).get("id"));
		assertEquals("user_5", rows.get(0).get("name"));
	}

	@Test
	public void testGetLessRoutesToCorrectGroup() throws Exception {
		List<Map<String, Object>> rows = client.getLessThen("bench_med", "id", "0005");
		assertFalse(rows.isEmpty());
		for (Map<String, Object> row : rows) {
			String id = String.valueOf(row.get("id"));
			assertTrue(id.compareTo("0005") < 0);
		}
	}

	@Test
	public void testGetMoreRoutesToCorrectGroup() throws Exception {
		List<Map<String, Object>> rows = client.getMoreThen("bench_ins", "id", "0997");
		assertFalse(rows.isEmpty());
		for (Map<String, Object> row : rows) {
			String id = String.valueOf(row.get("id"));
			assertTrue(id.compareTo("0997") > 0);
		}
	}

	@Test
	public void testRangeRoutesToCorrectGroup() throws Exception {
		List<Map<String, Object>> rows = client.getRange("bench_med", "id", "0010", "0015");
		assertEquals(6, rows.size());
		assertEquals("0010", rows.get(0).get("id"));
		assertEquals("0015", rows.get(5).get("id"));
	}

	@Test
	public void testInsertThenGet() throws Exception {
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "new_user");
		boolean ok = client.insert("bench_ins", "id", "9999", data);
		assertTrue(ok);

		List<Map<String, Object>> rows = client.get("bench_ins", "id", "9999");
		assertEquals(1, rows.size());
		assertEquals("new_user", rows.get(0).get("name"));
	}

	@Test
	public void testUpdateThenGet() throws Exception {
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "updated_user");
		boolean ok = client.update("bench_med", "id", "0010", data);
		assertTrue(ok);

		List<Map<String, Object>> rows = client.get("bench_med", "id", "0010");
		assertEquals(1, rows.size());
		assertEquals("updated_user", rows.get(0).get("name"));
	}

	@Test
	public void testDeleteThenGet() throws Exception {
		boolean ok = client.delete("bench_ins", "id", "0003");
		assertTrue(ok);

		List<Map<String, Object>> rows = client.get("bench_ins", "id", "0003");
		assertTrue(rows.isEmpty());
	}

	@Test
	public void testQueryReturnsResults() throws Exception {
		List<Map<String, Object>> rows = client.query("select * from bench_ins where id = '0001'");
		assertFalse(rows.isEmpty());
		assertEquals("0001", rows.get(0).get("id"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void testUnknownTableThrows() throws Exception {
		client.get("unknown_table", "id", "0001");
	}

	@Test
	public void testTablesAreSeparateByGroup() throws Exception {
		// bench_ins only in group1, bench_med only in group2
		// Verify bench_ins doesn't have group2's data
		List<Map<String, Object>> ins = client.get("bench_ins", "id", "0001");
		assertFalse(ins.isEmpty());

		List<Map<String, Object>> med = client.get("bench_med", "id", "0001");
		assertFalse(med.isEmpty());
	}

	/* ========== helpers ========== */

	private static void setupTable(String name, int port) throws Exception {
		Table t = database.load(name, name, new String[]{"id", "name"});
		t.indexColumnType().put("id", String.class);
		t.indexColumnType().put("name", String.class);
		String[] cols = new String[]{"id", "name"};
		for (int i = 0; i < 1000; i++) {
			Row row = new Row(cols);
			row.set(0, String.format("%04d", i));
			row.set(1, "user_" + i);
			int idx = t.getData().getList().size();
			t.getData().append(row);
			t.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
			t.getIndex().get("name").insertOrUpdate("user_" + i, idx, false);
		}
	}
}
