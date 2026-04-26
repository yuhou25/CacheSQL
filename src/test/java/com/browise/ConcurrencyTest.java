package com.browise;

import static org.junit.Assert.*;
import org.junit.Before;
import org.junit.Test;

import com.browise.core.exception.utilException;
import com.browise.database.database;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyTest {

	private Table table;

	@Before
	public void setup() throws utilException {
		database.removeTable("concurrent_test");
		String[] indexes = {"id"};
		table = database.load("concurrent_test", "concurrent_test", indexes);

		String[] cols = {"id", "name"};
		for (int i = 0; i < 1000; i++) {
			Row row = new Row(cols);
			row.set(0, String.valueOf(i));
			row.set(1, "name" + i);
			table.getData().append(row);
			int idx = table.getData().size() - 1;
			table.getIndex().get("id").insertOrUpdate(String.valueOf(i), idx, false);
		}
	}

	@Test
	public void testConcurrentReads() throws Exception {
		int threads = 16;
		int perThread = 1000;
		AtomicInteger success = new AtomicInteger(0);
		AtomicInteger errors = new AtomicInteger(0);
		CountDownLatch latch = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			final int offset = t;
			new Thread(() -> {
				try {
					for (int i = 0; i < perThread; i++) {
						String key = String.valueOf((offset * perThread + i) % 1000);
						Object result = table.get("id", key);
						if (result != null) success.incrementAndGet();
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				}
				latch.countDown();
			}).start();
		}

		latch.await();
		assertEquals(0, errors.get());
		assertEquals((long) threads * perThread, success.get());
	}

	@Test
	public void testConcurrentRangeQueries() throws Exception {
		int threads = 8;
		AtomicInteger errors = new AtomicInteger(0);
		CountDownLatch latch = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			new Thread(() -> {
				try {
					for (int i = 0; i < 100; i++) {
						table.getMoreAndLessThen("id", "100", "500");
					}
				} catch (Exception e) {
					errors.incrementAndGet();
				}
				latch.countDown();
			}).start();
		}

		latch.await();
		assertEquals(0, errors.get());
	}
}
