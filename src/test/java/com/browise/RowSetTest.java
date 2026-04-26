package com.browise;

import static org.junit.Assert.*;
import org.junit.Test;

import com.browise.database.table.Row;
import com.browise.database.table.rowSet;

public class RowSetTest {

	@Test
	public void testRowCompactMode() {
		String[] cols = {"id", "name", "age"};
		Row row = new Row(cols);
		row.set(0, "123");
		row.set(1, "Alice");
		row.set(2, 25);
		assertEquals("123", row.get(0));
		assertEquals("Alice", row.get(1));
		assertEquals(25, row.get(2));
		assertEquals("123", row.get("id"));
		assertEquals("Alice", row.get("name"));
		assertEquals(25, row.get("age"));
	}

	@Test
	public void testRowFallbackMode() {
		Row row = new Row();
		row.put("id", "456");
		row.put("name", "Bob");
		assertEquals("456", row.get("id"));
		assertEquals("Bob", row.get("name"));
		assertNull(row.get("nonexistent"));
	}

	@Test
	public void testRowTouch() {
		Row row = new Row(new String[]{"id"});
		long before = row.getLastAccessTime();
		try { Thread.sleep(1); } catch (Exception e) {}
		row.touch();
		assertTrue(row.getLastAccessTime() >= before);
	}

	@Test
	public void testRowDelete() {
		Row row = new Row(new String[]{"id"});
		assertFalse(row.isDelete());
		row.setDelete(true);
		assertTrue(row.isDelete());
		assertTrue(row.isDelete());
	}

	@Test
	public void testRowGetData() {
		String[] cols = {"id", "name"};
		Row row = new Row(cols);
		row.set(0, "1");
		row.set(1, "Test");
		java.util.HashMap<String, Object> data = row.getData();
		assertEquals("1", data.get("id"));
		assertEquals("Test", data.get("name"));
	}

	@Test
	public void testRowSetAppendAndCount() {
		rowSet rs = new rowSet();
		String[] cols = {"id"};
		for (int i = 0; i < 100; i++) {
			Row row = new Row(cols);
			row.set(0, String.valueOf(i));
			rs.append(row);
		}
		assertEquals(100, rs.size());
		assertEquals(100, rs.activeCount());
	}

	@Test
	public void testRowSetDelete() throws Exception {
		rowSet rs = new rowSet();
		String[] cols = {"id"};
		for (int i = 0; i < 10; i++) {
			Row row = new Row(cols);
			row.set(0, String.valueOf(i));
			rs.append(row);
		}
		rs.delete(5);
		assertEquals(10, rs.size());
		assertEquals(9, rs.activeCount());
		assertTrue(rs.get(5).isDelete());
	}

	@Test
	public void testRowSetClear() throws Exception {
		rowSet rs = new rowSet();
		rs.append(new Row(new String[]{"id"}));
		rs.append(new Row(new String[]{"id"}));
		assertEquals(2, rs.size());
		rs.clear();
		assertEquals(0, rs.size());
		assertEquals(0, rs.activeCount());
	}

	@Test(expected = Exception.class)
	public void testRowSetOutOfBounds() throws Exception {
		rowSet rs = new rowSet();
		rs.get(0);
	}
}
