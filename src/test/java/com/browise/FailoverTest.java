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
 * Failover 集成测试 — 验证超时、读容灾、写容灾（master优先）
 * Failover integration test — timeout, read failover, write failover (master first)
 */
public class FailoverTest {

	private HttpCacheServer master;
	private HttpCacheServer slave;
	private CacheSQLClient client;
	private static final int MASTER_PORT = 19101;
	private static final int SLAVE_PORT = 19102;

	@Before
	public void setUp() throws Exception {
		setupTable("failover_t");
		master = new HttpCacheServer(MASTER_PORT, 2);
		master.start();
		slave = new HttpCacheServer(SLAVE_PORT, 2);
		slave.start();
		Thread.sleep(300);

		Properties props = new Properties();
		props.setProperty("cachesql.connectTimeout", "3000");
		props.setProperty("cachesql.readTimeout", "5000");
		props.setProperty("cachesql.group.test.master", "http://127.0.0.1:" + MASTER_PORT);
		props.setProperty("cachesql.group.test.slaves", "http://127.0.0.1:" + SLAVE_PORT);
		props.setProperty("cachesql.group.test.tables", "failover_t");
		client = new CacheSQLClient(props);
	}

	@After
	public void tearDown() {
		if (master != null) master.stop();
		if (slave != null) slave.stop();
		database.removeTable("failover_t");
	}

	@Test
	public void testReadBothAlive() throws Exception {
		List<Map<String, Object>> rows = client.get("failover_t", "id", "0050");
		assertEquals(1, rows.size());
		assertEquals("0050", rows.get(0).get("id"));
	}

	@Test
	public void testReadFailoverWhenMasterDown() throws Exception {
		master.stop();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.get("failover_t", "id", "0050");
		assertEquals(1, rows.size());
		assertEquals("0050", rows.get(0).get("id"));
	}

	@Test
	public void testReadFailoverWhenSlaveDown() throws Exception {
		slave.stop();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.get("failover_t", "id", "0050");
		assertEquals(1, rows.size());
		assertEquals("0050", rows.get(0).get("id"));
	}

	@Test(expected = RuntimeException.class)
	public void testReadAllDown() throws Exception {
		master.stop();
		slave.stop();
		Thread.sleep(200);
		client.get("failover_t", "id", "0050");
	}

	@Test
	public void testWriteMasterAlive() throws Exception {
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "new_row");
		boolean ok = client.insert("failover_t", "id", "9999", data);
		assertTrue(ok);
		List<Map<String, Object>> rows = client.get("failover_t", "id", "9999");
		assertEquals(1, rows.size());
		assertEquals("new_row", rows.get(0).get("name"));
	}

	@Test
	public void testWriteFailoverToSlave() throws Exception {
		master.stop();
		Thread.sleep(200);
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "failover_row");
		boolean ok = client.insert("failover_t", "id", "8888", data);
		assertTrue(ok);
		List<Map<String, Object>> rows = client.get("failover_t", "id", "8888");
		assertEquals(1, rows.size());
		assertEquals("failover_row", rows.get(0).get("name"));
	}

	@Test
	public void testUpdateFailoverToSlave() throws Exception {
		master.stop();
		Thread.sleep(200);
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "updated_on_slave");
		boolean ok = client.update("failover_t", "id", "0010", data);
		assertTrue(ok);
		slave.stop();
		master = new HttpCacheServer(MASTER_PORT, 2);
		master.start();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.get("failover_t", "id", "0010");
		assertEquals(1, rows.size());
		assertEquals("updated_on_slave", rows.get(0).get("name"));
	}

	@Test
	public void testDeleteFailoverToSlave() throws Exception {
		master.stop();
		Thread.sleep(200);
		boolean ok = client.delete("failover_t", "id", "0020");
		assertTrue(ok);
	}

	@Test
	public void testRangeFailover() throws Exception {
		master.stop();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.getRange("failover_t", "id", "0010", "0015");
		assertEquals(6, rows.size());
	}

	@Test
	public void testLessThenFailover() throws Exception {
		slave.stop();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.getLessThen("failover_t", "id", "0005");
		assertFalse(rows.isEmpty());
		for (Map<String, Object> row : rows) {
			String id = String.valueOf(row.get("id"));
			assertTrue(id.compareTo("0005") < 0);
		}
	}

	@Test
	public void testMoreThenFailover() throws Exception {
		slave.stop();
		Thread.sleep(200);
		List<Map<String, Object>> rows = client.getMoreThen("failover_t", "id", "0997");
		assertFalse(rows.isEmpty());
		for (Map<String, Object> row : rows) {
			String id = String.valueOf(row.get("id"));
			assertTrue(id.compareTo("0997") > 0);
		}
	}

	@Test(expected = RuntimeException.class)
	public void testWriteAllDown() throws Exception {
		master.stop();
		slave.stop();
		Thread.sleep(200);
		Map<String, Object> data = new HashMap<String, Object>();
		data.put("name", "nope");
		client.insert("failover_t", "id", "7777", data);
	}

	@Test
	public void testTimeoutConfig() throws Exception {
		Properties props = new Properties();
		props.setProperty("cachesql.connectTimeout", "1234");
		props.setProperty("cachesql.readTimeout", "5678");
		props.setProperty("cachesql.group.test.master", "http://127.0.0.1:" + MASTER_PORT);
		props.setProperty("cachesql.group.test.tables", "failover_t");
		CacheSQLClient c = new CacheSQLClient(props);
		List<Map<String, Object>> rows = c.get("failover_t", "id", "0050");
		assertEquals(1, rows.size());
	}

	private static void setupTable(String name) throws Exception {
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
