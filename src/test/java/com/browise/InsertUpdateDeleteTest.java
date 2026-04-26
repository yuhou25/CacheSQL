package com.browise;

import static org.junit.Assert.*;

import java.util.HashMap;

import org.junit.Before;
import org.junit.Test;

import com.browise.core.exception.utilException;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class InsertUpdateDeleteTest {

	private Table table;
	private static final String[] COLS = {"id", "name", "age", "city"};

	@Before
	public void setup() throws utilException {
		database.removeTable("iud_test");
		String[] indexes = {"id", "name"};
		table = database.load("iud_test", "iud_test", indexes);
		table.indexColumnType().put("id", String.class);
		table.indexColumnType().put("name", String.class);

		for (int i = 1; i <= 10; i++) {
			Row row = new Row(COLS);
			row.set(0, String.format("%03d", i));
			row.set(1, i % 2 == 0 ? "Alice" : "Bob");
			row.set(2, 20 + i);
			row.set(3, i % 2 == 0 ? "Beijing" : "Shanghai");
			int idx = table.getData().getList().size();
			table.getData().append(row);
			table.getIndex().get("id").insertOrUpdate(String.format("%03d", i), idx, false);
			table.getIndex().get("name").insertOrUpdate(i % 2 == 0 ? "Alice" : "Bob", idx, false);
		}
	}

	@Test
	public void testDelete() throws Exception {
		table.delete("id", "005");
		Object result = table.get("id", "005");
		assertNull(result);
	}

	@Test
	public void testDeleteThenInsertReuse() throws Exception {
		Object before = table.get("id", "005");
		assertNotNull(before);
		assertEquals(1, ((java.util.List<Row>) before).size());

		table.delete("id", "005");
		assertNull(table.get("id", "005"));

		HashMap<String, Object> newData = new HashMap<String, Object>();
		newData.put("id", "005");
		newData.put("name", "Charlie");
		newData.put("age", 99);
		newData.put("city", "Guangzhou");
		table.insert("id", "005", newData);

		Object result = table.get("id", "005");
		assertNotNull(result);
		java.util.List<Row> rows = (java.util.List<Row>) result;
		assertEquals(1, rows.size());
		assertEquals("Charlie", rows.get(0).get("name"));
		assertEquals(99, rows.get(0).get("age"));
		assertEquals("Guangzhou", rows.get(0).get("city"));
	}

	@Test
	public void testUpdate() throws Exception {
		HashMap<String, Object> newData = new HashMap<String, Object>();
		newData.put("name", "David");
		newData.put("age", 50);
		table.update("id", "003", newData);

		Object result = table.get("id", "003");
		assertNotNull(result);
		java.util.List<Row> rows = (java.util.List<Row>) result;
		assertEquals(1, rows.size());
		assertEquals("David", rows.get(0).get("name"));
		assertEquals(50, rows.get(0).get("age"));
	}

	@Test
	public void testUpdateChangesIndex() throws Exception {
		HashMap<String, Object> newData = new HashMap<String, Object>();
		newData.put("name", "Eve");
		table.update("id", "003", newData);

		java.util.List<Row> bobs = (java.util.List<Row>) table.get("name", "Bob");
		java.util.List<Row> eves = (java.util.List<Row>) table.get("name", "Eve");

		assertNotNull(eves);
		assertTrue(eves.size() >= 1);

		if (bobs != null) {
			for (Row r : bobs) {
				assertNotEquals("003", r.get("id"));
			}
		}
	}

	@Test
	public void testInsertNew() throws Exception {
		HashMap<String, Object> newData = new HashMap<String, Object>();
		newData.put("id", "099");
		newData.put("name", "NewGuy");
		newData.put("age", 33);
		newData.put("city", "Shenzhen");
		table.insert("id", "099", newData);

		Object result = table.get("id", "099");
		assertNotNull(result);
		java.util.List<Row> rows = (java.util.List<Row>) result;
		assertEquals(1, rows.size());
		assertEquals("NewGuy", rows.get(0).get("name"));
	}

	@Test
	public void testInsertIdempotent() throws Exception {
		HashMap<String, Object> data1 = new HashMap<String, Object>();
		data1.put("id", "003");
		data1.put("name", "IdemBoy");
		data1.put("age", 77);
		table.insert("id", "003", data1);

		HashMap<String, Object> data2 = new HashMap<String, Object>();
		data2.put("id", "003");
		data2.put("name", "IdemGirl");
		data2.put("age", 88);
		table.insert("id", "003", data2);

		Object result = table.get("id", "003");
		assertNotNull(result);
		java.util.List<Row> rows = (java.util.List<Row>) result;
		assertEquals(1, rows.size());
		assertEquals("IdemGirl", rows.get(0).get("name"));
		assertEquals(88, rows.get(0).get("age"));
	}

	@Test
	public void testDeleteNonExistent() throws Exception {
		table.delete("id", "999");
	}

	@Test
	public void testUpdateNonExistent() throws Exception {
		HashMap<String, Object> newData = new HashMap<String, Object>();
		newData.put("name", "Nobody");
		table.update("id", "999", newData);
	}
}
