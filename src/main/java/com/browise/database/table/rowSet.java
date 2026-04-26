package com.browise.database.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/* 行集合：ArrayList<Row>的线程安全封装，记录总数和活跃数(排除已删除) */
/* Row set: thread-safe ArrayList<Row> wrapper, tracks total count and active count (excluding deleted) */
/* 软删除策略：delete()只标记isDelete=true+activeCount--，不从ArrayList移除。原因： */
/* Soft delete strategy: delete() only marks isDelete=true + activeCount--, doesn't remove from ArrayList. Reason: */
/*   1. ArrayList.remove(idx)是O(N)操作（需拷贝后续元素） / ArrayList.remove(idx) is O(N) */
/*   2. 移除后所有后续元素的rowIdx减1，B+树索引中存储的全部rowIdx失效 / Shifting invalidates all rowIdx in B+ tree */
/*   3. 累积的deleted行可通过compact()一次性清理并重建索引 / Accumulated deleted rows cleaned by compact() with index rebuild */
public class rowSet {
	
	private final List<Row> list = new ArrayList<Row>();
	private int count = 0;
	private int activeCount = 0;

	public List<Row> getList() {
		return list;
	}

	public int getCount() {
		return count;
	}

	/**
	 * 追加行到集合末尾，增加计数
	 * Append a row to the end, increment counts
	 */
	public synchronized void append(Row obj) {
		list.add(obj);
		count++;
		activeCount++;
	}

	/**
	 * 按下标取行，越界抛异常
	 * Get row by index, throws if index out of bounds
	 */
	public Row get(int i) throws Exception {
		if (i >= list.size()) throw new Exception("没有那么多数据!");
		return list.get(i);
	}

	public int count() {
		return count;
	}

	public int activeCount() {
		return activeCount;
	}

	public int size() {
		return list.size();
	}

	/**
	 * 清空所有行，重置计数
	 * Clear all rows, reset counters
	 */
	public synchronized void clear() {
		list.clear();
		count = 0;
		activeCount = 0;
	}

	/**
	 * 更新指定行的列值
	 * Update column values of the specified row
	 */
	public synchronized void update(int i, HashMap<String, Object> data) {
		Iterator<Map.Entry<String, Object>> keys = data.entrySet().iterator();
		Row row1 = list.get(i);
		if (row1 != null) {
			while (keys.hasNext()) {
				Map.Entry entry = (Map.Entry) keys.next();
				Object key = entry.getKey();
				Object val = entry.getValue();
				row1.put(String.valueOf(key), val);
			}
		}
	}

	/**
	 * 软删除指定行，减少计数
	 * Soft-delete the specified row, decrement counts
	 */
	public synchronized void delete(int i) {
		if (!list.get(i).isDelete()) {
			list.get(i).setDelete(true);
			count--;
			activeCount--;
		}
	}

	/**
	 * 插入新行并返回其下标
	 * Insert a new row and return its index
	 */
	public synchronized int insert(HashMap<String, Object> data) {
		Iterator<Map.Entry<String, Object>> keys = data.entrySet().iterator();
		Row row1 = new Row();
		if (row1 != null) {
			while (keys.hasNext()) {
				Map.Entry entry = (Map.Entry) keys.next();
				Object key = entry.getKey();
				Object val = entry.getValue();
				row1.put(String.valueOf(key), val);
			}
		}
		list.add(row1);
		count++;
		activeCount++;
		return list.size() - 1;
	}

	public int deletedCount() {
		return count - activeCount;
	}

	/* 压缩：从后向前遍历ArrayList移除已删除行，避免前删导致下标偏移。压缩后需重建B+树索引 */
	/* Compact: iterate ArrayList backwards removing deleted rows, avoiding index shift from front deletion. B+ tree indexes must be rebuilt after compact */
	public synchronized void compact() {
		int removed = 0;
		for (int i = list.size() - 1; i >= 0; i--) {
			if (list.get(i).isDelete()) {
				list.remove(i);
				removed++;
			}
		}
		count -= removed;
		activeCount = count;
	}

	public static void main(String[] arg) throws Exception {
		rowSet obj = new rowSet();
		for (int i = 0; i < 10; i++) {
			Row r = new Row();
			r.put("baz001", i);
			obj.append(r);
		}
		for (int i = 0; i < obj.count(); i++) {
			System.out.println(obj.get(i).get("baz001"));
		}
		HashMap map = new HashMap();
		map.put("baz001", "修改");
		obj.update(3, map);
		map.put("baz001", "新增");
		obj.insert(map);
		for (int i = 0; i < obj.size(); i++) {
			System.out.println(obj.get(i).get("baz001"));
		}
	}
}
