package com.browise;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

import com.browise.database.btree.BPTree;

public class BPTreeTest {

	private BPTree tree;

	@Before
	public void setup() {
		tree = new BPTree(4);
	}

	@Test
	public void testInsertAndGet() {
		tree.insertOrUpdate(10L, 0, false);
		tree.insertOrUpdate(20L, 1, false);
		tree.insertOrUpdate(30L, 2, false);

		assertNotNull(tree.get(10L));
		assertNotNull(tree.get(20L));
		assertNotNull(tree.get(30L));
		assertNull(tree.get(40L));
	}

	@Test
	public void testInsertDuplicateKey() {
		tree.insertOrUpdate(10L, 0, false);
		tree.insertOrUpdate(10L, 1, false);

		java.util.ArrayList<?> result = (java.util.ArrayList<?>) tree.get(10L);
		assertNotNull(result);
		assertEquals(2, result.size());
	}

	@Test
	public void testInsertMany() {
		for (int i = 0; i < 100; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		for (int i = 0; i < 100; i++) {
			assertNotNull("key=" + i + " not found", tree.get((long) i));
		}
		assertNull(tree.get(100L));
	}

	@Test
	public void testInsertReverse() {
		for (int i = 99; i >= 0; i--) {
			tree.insertOrUpdate((long) i, i, false);
		}
		for (int i = 0; i < 100; i++) {
			assertNotNull(tree.get((long) i));
		}
	}

	@Test
	public void testInsertRandom() {
		java.util.Random r = new java.util.Random(42);
		java.util.Set<Long> keys = new java.util.TreeSet<Long>();
		for (int i = 0; i < 200; i++) {
			long k = r.nextInt(10000);
			keys.add(k);
			tree.insertOrUpdate(k, i, false);
		}
		for (Long k : keys) {
			assertNotNull("key=" + k + " not found", tree.get(k));
		}
	}

	@Test
	public void testStringKeys() {
		tree.insertOrUpdate("banana", 0, false);
		tree.insertOrUpdate("apple", 1, false);
		tree.insertOrUpdate("cherry", 2, false);
		tree.insertOrUpdate("date", 3, false);

		assertNotNull(tree.get("apple"));
		assertNotNull(tree.get("banana"));
		assertNotNull(tree.get("cherry"));
		assertNotNull(tree.get("date"));
		assertNull(tree.get("grape"));
	}

	@Test
	public void testChineseStringKeys() {
		tree.insertOrUpdate("张三", 0, false);
		tree.insertOrUpdate("李四", 1, false);
		tree.insertOrUpdate("王五", 2, false);

		assertNotNull(tree.get("张三"));
		assertNotNull(tree.get("李四"));
		assertNotNull(tree.get("王五"));
		assertNull(tree.get("赵六"));
	}

	@Test
	public void testGetLessThen() {
		for (int i = 0; i < 50; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		java.util.List<java.util.List> result = tree.getLessThen(25L);
		assertNotNull(result);
		int total = 0;
		for (java.util.List<?> list : result) total += list.size();
		assertEquals(25, total);
	}

	@Test
	public void testGetMoreThen() {
		for (int i = 0; i < 50; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		java.util.List<java.util.List> result = tree.getMoreThen(25L);
		assertNotNull(result);
		int total = 0;
		for (java.util.List<?> list : result) total += list.size();
		assertEquals(24, total);
	}

	@Test
	public void testGetMoreAndLessThen() {
		for (int i = 0; i < 100; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		java.util.List<java.util.List> result = tree.getMoreAndLessThen(20L, 30L);
		assertNotNull(result);
		int total = 0;
		for (java.util.List<?> list : result) total += list.size();
		assertEquals(10, total);
	}

	@Test
	public void testStringRangeQuery() {
		tree.insertOrUpdate("a001", 0, false);
		tree.insertOrUpdate("a002", 1, false);
		tree.insertOrUpdate("a003", 2, false);
		tree.insertOrUpdate("b001", 3, false);
		tree.insertOrUpdate("b002", 4, false);
		tree.insertOrUpdate("c001", 5, false);

		java.util.List<java.util.List> result = tree.getMoreAndLessThen("a001", "a999");
		assertNotNull(result);
		int total = 0;
		for (java.util.List<?> list : result) total += list.size();
		assertEquals(3, total);
	}

	@Test
	public void testRemove() {
		for (int i = 0; i < 20; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		assertTrue(tree.remove(10L));
		assertNull(tree.get(10L));
		assertFalse(tree.remove(10L));
		assertNotNull(tree.get(9L));
		assertNotNull(tree.get(11L));
	}

	@Test
	public void testClear() {
		for (int i = 0; i < 50; i++) {
			tree.insertOrUpdate((long) i, i, false);
		}
		tree.clear();
		for (int i = 0; i < 50; i++) {
			assertNull(tree.get((long) i));
		}
	}

	@Test
	public void testLargeDataset() {
		BPTree bigTree = new BPTree(64);
		int count = 10000;
		for (int i = 0; i < count; i++) {
			bigTree.insertOrUpdate((long) i, i, false);
		}
		for (int i = 0; i < count; i++) {
			assertNotNull("key=" + i, bigTree.get((long) i));
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void testInvalidOrder() {
		new BPTree(2);
	}
}
