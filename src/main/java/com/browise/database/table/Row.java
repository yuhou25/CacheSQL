package com.browise.database.table;

import java.util.HashMap;
import java.util.Map;

/* 行数据：双模式存储——有columnNames时用values[]数组(高效)，否则用HashMap(灵活) */
/* Row data: dual-mode storage — values[] array when columnNames set (efficient), HashMap otherwise (flexible) */
/* 冻结机制：加载后freeze()，put/set/setDelete抛异常；写操作需unfreeze→修改→refreeze */
/* Freeze mechanism: freeze() after loading, put/set/setDelete throw when frozen; writes need unfreeze → modify → refreeze */
/* 软删除设计：delete不移动ArrayList元素，只标记isDelete=true。原因：避免O(N)数组拷贝、避免所有rowIdx失效 */
/* Soft delete design: delete doesn't shift ArrayList elements, only sets isDelete=true. Reason: avoids O(N) array copy and invalidating all rowIdx */
/* 连锁影响：get(String)/get(int)检查isDelete→返回null；查询方法遍历时跳过deleted行；B+树索引在delete时同步清理 */
/* Chain effect: get(String)/get(int) check isDelete → return null; query methods skip deleted rows; B+ tree indexes cleaned on delete */
public class Row {
	private String[] columnNames;
	private Object[] values;
	private HashMap<String, Object> fallback;
	/* volatile保证多线程可见性：一个线程标记删除，其他线程立即可见 */
	/* volatile ensures multi-thread visibility: one thread marks deleted, others see it immediately */
	/* 软删除核心字段：true=逻辑删除，Row留在ArrayList原位，get()返回null，查询跳过 */
	/* Soft delete core field: true=logical deletion, Row stays in ArrayList at original position, get() returns null, queries skip */
	private volatile boolean isDelete = false;
	private volatile long lastAccessTime = System.currentTimeMillis();
	/* 冻结标志：true时put/set/setDelete抛IllegalStateException，防止应用层误修改内部数据 */
	/* Frozen flag: when true, put/set/setDelete throw IllegalStateException, preventing app from modifying internal data */
	private volatile boolean frozen = false;

	/**
	 * 构造行：使用 HashMap 存储（灵活模式，无需预定义列名）
	 * Construct row: uses HashMap storage (flexible mode, no predefined column names)
	 */
	public Row() {
		this.fallback = new HashMap<String, Object>();
	}

	/**
	 * 构造行：使用预定义列名数组模式（高效，O(1) 列访问）
	 * Construct row: uses predefined column name array (efficient, O(1) column access)
	 */
	public Row(String[] columnNames) {
		this.columnNames = columnNames;
		this.values = new Object[columnNames.length];
	}

	void freeze() {
		this.frozen = true;
	}

	void unfreeze() {
		this.frozen = false;
	}

	public void touch() {
		this.lastAccessTime = System.currentTimeMillis();
	}

	public long getLastAccessTime() {
		return lastAccessTime;
	}

	/**
	 * 按列名写入值（冻结时抛异常），匹配列名时忽略大小写
	 * Set value by column name (throws if frozen), case-insensitive column matching
	 */
	public void put(String key, Object value) {
		if (frozen) throw new IllegalStateException("row is read-only");
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				if (columnNames[i].equalsIgnoreCase(key)) {
					values[i] = value;
					return;
				}
			}
		} else {
			fallback.put(key, value);
		}
	}

	/** isDelete=true时返回null——这影响extractIndexValues的调用时机：必须在setDelete(false)之后
	/* Returns null when isDelete=true — affects extractIndexValues timing: MUST be called after setDelete(false)
	/* isDelete=true时返回null——这影响extractIndexValues的调用时机：必须在setDelete(false)之后 */
	/* Returns null when isDelete=true — affects extractIndexValues timing: MUST be called after setDelete(false) */
	public Object get(String key) {
		if (isDelete) return null;
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				if (columnNames[i].equalsIgnoreCase(key)) {
					return values[i];
				}
			}
			return null;
		} else {
			return fallback.get(key);
		}
	}

	/**
	 * 按下标写入值（冻结时抛异常），仅数组模式有效
	 * Set value by index (throws if frozen), only valid in array mode
	 */
	public void set(int index, Object value) {
		if (frozen) throw new IllegalStateException("row is read-only");
		if (columnNames != null) {
			values[index] = value;
		}
	}

	/**
	 * 按下标取值，已软删除返回 null
	 * Get value by index, returns null if soft-deleted
	 */
	public Object get(int index) {
		if (isDelete) return null;
		if (columnNames != null) {
			return values[index];
		}
		return null;
	}

	public boolean isDelete() {
		return isDelete;
	}

	/**
	 * 设置软删除标志（冻结时抛异常），删除后 get() 返回 null、查询跳过
	 * Set soft-delete flag (throws if frozen), get() returns null and queries skip when deleted
	 */
	public void setDelete(boolean isDelete) {
		if (frozen) throw new IllegalStateException("row is read-only");
		this.isDelete = isDelete;
	}

	/**
	 * 返回行数据的副本（HashMap），不包括 null 值
	 * Return a copy of row data (HashMap), excluding null values
	 */
	public HashMap<String, Object> getData() {
		HashMap<String, Object> map = new HashMap<String, Object>();
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				if (values[i] != null) {
					map.put(columnNames[i], values[i]);
				}
			}
		} else if (fallback != null) {
			map.putAll(fallback);
		}
		return map;
	}

	public void setData(HashMap<String, Object> data) {
		if (frozen) throw new IllegalStateException("row is read-only");
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				values[i] = data.get(columnNames[i]);
			}
		} else {
			this.fallback = data;
		}
	}

	public String[] getColumnNames() {
		return columnNames;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder("[");
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				if (values[i] != null) {
					if (sb.length() > 1) sb.append(",");
					sb.append(columnNames[i]).append(":").append(values[i]);
				}
			}
		} else if (fallback != null) {
			for (Map.Entry<String, Object> entry : fallback.entrySet()) {
				if (sb.length() > 1) sb.append(",");
				sb.append(entry.getKey()).append(":").append(entry.getValue());
			}
		}
		sb.append("]");
		return sb.toString();
	}
}
