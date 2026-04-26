package com.browise;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

import com.browise.core.exception.utilException;
import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

public class SqlQueryEngineTest {

	private Table table;

	@Before
	public void setup() throws utilException {
		SqlQueryEngine.clearPlanCache();
		database.removeTable("test");
		String[] indexes = {"id", "name", "age"};
		table = database.load("test", "test", indexes);

		String[] cols = {"id", "name", "age", "city"};
		for (int i = 1; i <= 100; i++) {
			Row row = new Row(cols);
			row.set(0, String.format("%04d", i));
			row.set(1, i % 2 == 0 ? "Alice" : "Bob");
			row.set(2, 20 + (i % 10));
			row.set(3, i % 2 == 0 ? "Beijing" : "Shanghai");
			table.getData().append(row);

			int idx = table.getData().size() - 1;
			table.getIndex().get("id").insertOrUpdate(String.format("%04d", i), idx, false);
			table.getIndex().get("name").insertOrUpdate(i % 2 == 0 ? "Alice" : "Bob", idx, false);
			table.getIndex().get("age").insertOrUpdate((long)(20 + i % 10), idx, false);
		}
	}

	@Test
	public void testEqualString() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where id = '0001'");
		assertNotNull(r.rows);
		assertEquals(1, r.rows.size());
		assertEquals("0001", r.rows.get(0).get("id"));
	}

	@Test
	public void testEqualNumber() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where age = 25");
		assertNotNull(r.rows);
		assertTrue(r.rows.size() > 0);
		for (Row row : r.rows) {
			assertEquals(25, row.get("age"));
		}
	}

	@Test
	public void testGreaterThan() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where id > '0098'");
		assertNotNull(r.rows);
		assertEquals(2, r.rows.size());
		assertEquals("0099", r.rows.get(0).get("id"));
		assertEquals("0100", r.rows.get(1).get("id"));
	}

	@Test
	public void testRange() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where id >= '0010' and id <= '0020'");
		assertNotNull(r.rows);
		assertEquals(11, r.rows.size());
	}

	@Test
	public void testLessThanStringOrder() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where id < '0005'");
		assertNotNull(r.rows);
		for (Row row : r.rows) {
			assertTrue(((String) row.get("id")).compareTo("0005") < 0);
		}
	}

	@Test
	public void testLikePrefix() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where name like 'A%'");
		assertNotNull(r.rows);
		assertEquals(50, r.rows.size());
	}

	@Test
	public void testLikePrefixNoMatch() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where name like 'Z%'");
		assertNull(r.rows);
	}

	@Test
	public void testNoIndexReturnsNull() throws Exception {
		QueryResult r = SqlQueryEngine.query("select * from test where city = 'Beijing'");
		assertEquals("no_index", r.method);
		assertNull(r.rows);
	}

	@Test
	public void testPlanCache() throws Exception {
		assertEquals(0, SqlQueryEngine.planCacheSize());
		SqlQueryEngine.query("select * from test where id = '1'");
		assertEquals(1, SqlQueryEngine.planCacheSize());
		SqlQueryEngine.query("select * from test where id = '2'");
		assertEquals(1, SqlQueryEngine.planCacheSize());
		SqlQueryEngine.query("select * from test where name = 'Alice'");
		assertEquals(2, SqlQueryEngine.planCacheSize());
	}

	@Test
	public void testInvalidSql() {
		try {
			SqlQueryEngine.query("not a sql");
			fail("should throw");
		} catch (Exception e) {
			assertTrue(e.getMessage().contains("parse") || e.getMessage().contains("SQL"));
		}
	}

	@Test
	public void testTableNotFound() {
		try {
			SqlQueryEngine.query("select * from nonexistent where id = 1");
			fail("should throw");
		} catch (Exception e) {
			assertTrue(e.getMessage().contains("not found"));
		}
	}
}
