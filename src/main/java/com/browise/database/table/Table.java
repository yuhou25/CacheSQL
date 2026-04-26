package com.browise.database.table;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.browise.core.exception.utilException;
import com.browise.core.util.DBUtil;
import com.browise.database.btree.BPTree;
import com.browise.database.index.CompositeKey;

/* 内存表：数据行以ArrayList存储，B+树索引支持等值/范围查询 */
/* In-memory table: rows stored in ArrayList, B+ tree indexes for point/range queries */
public class Table {

	/* 行数据存储，下标即rowIdx，被B+树索引用于定位行 */
	/* Row storage where array index = rowIdx, used by B+ tree indexes to locate rows */
	private final rowSet data = new rowSet();
	private String[] columnNames;
	private final List<HashMap<String, Object>> mete = new ArrayList<HashMap<String, Object>>();
	private String name;
	/* B+树索引，key=列名，value=该列的B+树实例。查询时先通过列名找到对应B+树 */
	/* B+ tree indexes: key=column name, value=B+ tree instance for that column */
	private final HashMap<String, BPTree> index = new HashMap<String, BPTree>();
	/* 联合索引定义，key=索引名(如"A+B")，value=列名数组 */
	/* Composite index definitions: key=index name (e.g. "A+B"), value=column name array */
	private final HashMap<String, String[]> compositeIndexDefs = new HashMap<String, String[]>();
	/* 索引列的Java类型，用于查询时类型转换（Long/Double/String） */
	/* Java type of indexed columns, used for type conversion during queries (Long/Double/String) */
	private final HashMap<String, Class<?>> indexColumnType = new HashMap<String, Class<?>>();
	private String sql;
	/* PreparedStatement绑定参数，来自config.properties的cache.table.X.params */
	/* PreparedStatement bind parameters from config.properties cache.table.X.params */
	private String[] queryParams;

	private long ttlMs = 0;
	private int maxSize = 0;
	private int evictCursor = 0;
	private static final int EVICT_BATCH = 100;
	/* 查询结果行数上限，防止大结果集OOM。所有get*方法返回前截断 */
	/* Max rows returned by queries to prevent OOM. All get* methods truncate before returning */
	private static final int MAX_RESULT_ROWS = 10000;
	private int lruCursor = 0;

	public void setTTL(long milliseconds) {
		this.ttlMs = milliseconds;
	}

	public long getTTL() {
		return ttlMs;
	}

	public void setMaxSize(int maxSize) {
		this.maxSize = maxSize;
	}

	public int getMaxSize() {
		return maxSize;
	}

	/**
	 * 注册联合索引定义
	 * Register a composite index definition
	 */
	public void addCompositeIndex(String indexName, String[] columns) {
		compositeIndexDefs.put(indexName, columns);
	}

	public Map<String, Class<?>> indexColumnType() {
		return indexColumnType;
	}

	public String getSql() {
		return sql;
	}

	public void setSql(String sql) {
		this.sql = sql;
	}

	public void setQueryParams(String[] params) {
		this.queryParams = params;
	}

	public String[] getQueryParams() {
		return queryParams;
	}

	/* 从数据库全量加载数据，构建行数据和B+树索引。synchronized保证加载期间无并发读写 */
	/* Full load from database, build rows and B+ tree indexes. synchronized prevents concurrent R/W during load */
	public synchronized void init() throws utilException {
		PreparedStatement pstatement = null;
		ResultSet result = null;
		Connection conn = null;
		try {
			conn = DBUtil.getConnection();
			pstatement = conn.prepareStatement(sql);
			if (queryParams != null) {
				for (int i = 0; i < queryParams.length; i++) {
					bindParam(pstatement, i + 1, queryParams[i].trim());
				}
			}
			result = pstatement.executeQuery();
			ResultSetMetaData rsmd = result.getMetaData();
			int colCount = rsmd.getColumnCount();
			columnNames = new String[colCount];
			for (int i = 0; i < colCount; i++) {
				String colName = rsmd.getColumnName(i + 1);
				columnNames[i] = colName;
				HashMap<String, Object> obj1 = new HashMap<String, Object>();
				mete.add(obj1);
				obj1.put("name", colName);
				obj1.put("type", rsmd.getColumnTypeName(i + 1));
			}

			int estimatedRows = estimateRowCount(conn);
			if (estimatedRows > 0) {
				long estimatedBytes = estimateMemory(estimatedRows, colCount, index.size());
				checkMemory(estimatedBytes, estimatedRows);
			}

			while (result.next()) {
				if (maxSize > 0 && data.activeCount() >= maxSize) {
					evictLRU(1);
				}
				Row row1 = new Row(columnNames);
				data.append(row1);
				int size = data.size() - 1;
				for (int i = 0; i < colCount; i++) {
					Object value = result.getObject(i + 1);
					row1.set(i, value);

					String key = columnNames[i];
					if (index.get(key) != null && !compositeIndexDefs.containsKey(key)) {
						Comparable valuekey = toKey(value);
						index.get(key).insertOrUpdate(valuekey, size, false);
						if (!indexColumnType.containsKey(key) && value != null) {
							indexColumnType.put(key, value.getClass());
						}
					}
				}

				for (Map.Entry<String, String[]> entry : compositeIndexDefs.entrySet()) {
					String indexName = entry.getKey();
					String[] cols = entry.getValue();
					BPTree tree = index.get(indexName);
					if (tree == null) continue;
					HashMap<String, Integer> colIndex = buildColumnIndex();
					Object[] vals = new Object[cols.length];
					for (int c = 0; c < cols.length; c++) {
						Integer ci = colIndex.get(cols[c].toUpperCase());
						vals[c] = ci != null ? row1.get(ci) : null;
					}
					tree.insertOrUpdate(CompositeKey.of(vals), size, false);
				}
			}

		} catch (utilException e) {
			throw e;
		} catch (Exception e) {
			throw new utilException(e.getMessage(), -1001);
		} finally {
			if (result != null) try { result.close(); } catch (Exception ignored) {}
			if (pstatement != null) try { pstatement.close(); } catch (Exception ignored) {}
			/* 归还连接到连接池，而非关闭 / Return connection to pool instead of closing */
			DBUtil.returnConnection(conn);
		}
		/* 加载完成后冻结所有行，防止应用层误修改。读操作零拷贝返回Row引用 */
		/* Freeze all rows after loading to prevent accidental modification. Reads return Row references (zero-copy) */
		for (int i = 0; i < data.size(); i++) {
			try { data.get(i).freeze(); } catch (Exception ignored) {}
		}
	}

	/**
	 * 初始化表名
	 * Initialize table with a given name
	 */
	public void init(String name) {
		this.name = name;
	}

	/**
	 * 检查行是否TTL过期 → 跳过超时行
	 * Check if row is TTL-expired → skip timed-out rows
	 */
	private boolean isExpired(Row row) {
		if (ttlMs <= 0) return false;
		return (System.currentTimeMillis() - row.getLastAccessTime()) > ttlMs;
	}

	/* LRU淘汰：扫描EVICT_BATCH(100)个行，淘汰最久未访问的。淘汰时同步清理B+树索引条目 */
	/* LRU eviction: scan EVICT_BATCH(100) rows, evict least recently accessed. Also cleans B+ tree index entries */
	private void evictLRU(int count) {
		for (int evicted = 0; evicted < count; ) {
			int total = data.size();
			if (total == 0) break;
			long oldestTime = Long.MAX_VALUE;
			int oldestIdx = -1;
			int scanned = 0;
			while (scanned < EVICT_BATCH && scanned < total) {
				if (lruCursor >= total) lruCursor = 0;
				Row row;
				try { row = data.get(lruCursor); } catch (Exception e) { lruCursor++; scanned++; continue; }
				if (!row.isDelete() && row.getLastAccessTime() < oldestTime) {
					oldestTime = row.getLastAccessTime();
					oldestIdx = lruCursor;
				}
				lruCursor++;
				scanned++;
			}
			if (oldestIdx < 0) break;
			try {
				/* 先提取索引值再删除，因为row.get()在isDelete=true时返回null */
				/* Extract index values BEFORE delete, since row.get() returns null when isDelete=true */
				Row oldestRow = data.get(oldestIdx);
				Object[] oldIdxValues = extractIndexValues(oldestRow);
				data.delete(oldestIdx);
				removeFromIndexesWithValues(oldestIdx, oldIdxValues);
			} catch (Exception e) {
				break;
			}
			evicted++;
		}
	}

	/* 等值查询：通过B+树O(logN)定位，返回Row引用列表(零拷贝)。跳过已删除行 */
	/* Point query: O(logN) via B+ tree, returns Row reference list (zero-copy). Skips deleted rows */
	public Object get(Object name, Object val) throws NumberFormatException, Exception {
		BPTree index1 = index.get(name);
		if (index1 != null) {
			Comparable key = toKeyForColumn(String.valueOf(name), val);
			ArrayList<Object> addrs = (ArrayList<Object>) index1.get(key);
			if (addrs == null) {
				return null;
			}
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < addrs.size(); i++) {
				int rowIdx = Integer.parseInt(String.valueOf(addrs.get(i)));
				Row row = data.get(rowIdx);
				if (row.isDelete()) continue;
				row.touch();
				list.add(row);
			}
			return list.isEmpty() ? null : limitResult(list);
		}
		return null;
	}

	/**
	 * 范围查询：列值 < val，走B+树索引
	 * Range query: column value < val, via B+ tree index
	 */
	public Object getLessThen(Object name, Object val) throws Exception {
		BPTree index1 = index.get(name);
		if (index1 != null) {
			Comparable key = toKeyForColumn(String.valueOf(name), val);
			List<List> addrs = index1.getLessThen(key);
			if (addrs == null) return null;
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < addrs.size(); i++) {
				List list1 = addrs.get(i);
				for (int j = 0; j < list1.size(); j++) {
					int rowIdx = Integer.parseInt(String.valueOf(list1.get(j)));
					Row row = data.get(rowIdx);
					if (row.isDelete()) continue;
					row.touch();
					list.add(row);
				}
			}
			return list.isEmpty() ? null : limitResult(list);
		}
		return null;
	}

	/**
	 * 范围查询：列值 > val，走B+树索引
	 * Range query: column value > val, via B+ tree index
	 */
	public Object getMoreThen(Object name, Object val) throws Exception {
		BPTree index1 = index.get(name);
		if (index1 != null) {
			Comparable key = toKeyForColumn(String.valueOf(name), val);
			List<List> addrs = index1.getMoreThen(key);
			if (addrs == null) return null;
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < addrs.size(); i++) {
				List list1 = addrs.get(i);
				for (int j = 0; j < list1.size(); j++) {
					int rowIdx = Integer.parseInt(String.valueOf(list1.get(j)));
					Row row = data.get(rowIdx);
					if (row.isDelete()) continue;
					row.touch();
					list.add(row);
				}
			}
			return list.isEmpty() ? null : limitResult(list);
		}
		return null;
	}

	/**
	 * 范围查询：列值 <= val = getLessThen ∪ get
	 * Range query: column value <= val = getLessThen ∪ get
	 */
	public Object getLessThenEquals(Object name, Object val) throws Exception {
		List<Row> list = new ArrayList<Row>();
		Object lessResult = getLessThen(name, val);
		if (lessResult instanceof List) {
			list.addAll((List) lessResult);
		}
		Object equalResult = get(name, val);
		if (equalResult instanceof List) {
			list.addAll((List) equalResult);
		}
		return list.isEmpty() ? null : limitResult((List<Object>) (List<?>) list);
	}

	/**
	 * 范围查询：列值 >= val = get ∪ getMoreThen
	 * Range query: column value >= val = get ∪ getMoreThen
	 */
	public Object getMoreThenEquals(Object name, Object val) throws Exception {
		List<Row> list = new ArrayList<Row>();
		Object equalResult = get(name, val);
		if (equalResult instanceof List) {
			list.addAll((List) equalResult);
		}
		Object moreResult = getMoreThen(name, val);
		if (moreResult instanceof List) {
			list.addAll((List) moreResult);
		}
		return list.isEmpty() ? null : limitResult((List<Object>) (List<?>) list);
	}

	/**
	 * 范围查询：val1 < 列值 < val2，开区间
	 * Range query: val1 < column value < val2, open interval
	 */
	public Object getMoreAndLessThen(Object name, Object val1, Object val2) throws Exception {
		BPTree index1 = index.get(name);
		if (index1 != null) {
			String col = String.valueOf(name);
			Comparable key1 = toKeyForColumn(col, val1);
			Comparable key2 = toKeyForColumn(col, val2);
			List<List> addrs = index1.getMoreAndLessThen(key1, key2);
			if (addrs == null) return null;
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < addrs.size(); i++) {
				List list1 = addrs.get(i);
				for (int j = 0; j < list1.size(); j++) {
					int rowIdx = Integer.parseInt(String.valueOf(list1.get(j)));
					Row row = data.get(rowIdx);
					if (row.isDelete()) continue;
					row.touch();
					list.add(row);
				}
			}
			return list.isEmpty() ? null : limitResult(list);
		}
		return null;
	}

	/**
	 * 范围查询：valFrom <= 列值 <= valTo，闭区间
	 * Range query: valFrom <= column value <= valTo, closed interval
	 */
	public Object getRange(Object name, Object valFrom, Object valTo) throws Exception {
		BPTree index1 = index.get(name);
		if (index1 != null) {
			String col = String.valueOf(name);
			Comparable keyFrom = toKeyForColumn(col, valFrom);
			Comparable keyTo = toKeyForColumn(col, valTo);
			List<List> addrs = index1.getRange(keyFrom, keyTo);
			if (addrs == null) return null;
			List<Object> list = new ArrayList<Object>();
			for (int i = 0; i < addrs.size(); i++) {
				List list1 = addrs.get(i);
				for (int j = 0; j < list1.size(); j++) {
					int rowIdx = Integer.parseInt(String.valueOf(list1.get(j)));
					Row row = data.get(rowIdx);
					if (row.isDelete()) continue;
					row.touch();
					list.add(row);
				}
			}
			return list.isEmpty() ? null : limitResult(list);
		}
		return null;
	}

	/* 索引键类型转换：Number→Long/Double，其他→String。确保查询参数与索引键类型一致 */
	/* Index key type conversion: Number→Long/Double, others→String. Ensures query param matches index key type */
	private Comparable toKey(Object value) {
		if (value == null) return "";
		if (value instanceof Number) {
			return Long.valueOf(((Number) value).longValue());
		}
		return String.valueOf(value);
	}

	/**
	 * 按列类型将查询参数转为Comparable → 匹配索引键类型
	 * Convert query param to Comparable per column type → match index key type
	 */
	private Comparable toKeyForColumn(String column, Object value) {
		if (value == null) return "";
		Class<?> colType = indexColumnType.get(column);
		if (colType == Integer.class || colType == Long.class || colType == java.math.BigDecimal.class) {
			if (value instanceof Number) {
				return Long.valueOf(((Number) value).longValue());
			}
			try {
				return Long.valueOf(Long.parseLong(String.valueOf(value)));
			} catch (NumberFormatException e) {
				return String.valueOf(value);
			}
		}
		if (colType == Double.class || colType == Float.class) {
			if (value instanceof Number) {
				return Double.valueOf(((Number) value).doubleValue());
			}
			try {
				return Double.valueOf(Double.parseDouble(String.valueOf(value)));
			} catch (NumberFormatException e) {
				return String.valueOf(value);
			}
		}
		if (colType == String.class) {
			return String.valueOf(value);
		}
		return toKey(value);
	}

	public String getTableName() {
		return name;
	}

	/* 插入行：1)主键已存在且行已删除→复用槽位 2)主键已存在且行活跃→幂等更新 3)新行→追加 */
	/* Insert row: 1) key exists & row deleted → reuse slot 2) key exists & row active → idempotent update 3) new → append */
	/* synchronized保证并发写安全 / synchronized for concurrent write safety */
	public synchronized void insert(String indexColumn, Object keyValue, HashMap<String, Object> newData) {
		BPTree tree = index.get(indexColumn);
		if (tree == null) return;

		Comparable key = toKeyForColumn(indexColumn, keyValue);
		ArrayList<Object> addrs = (ArrayList<Object>) tree.get(key);

		if (addrs != null) {
			for (int i = 0; i < addrs.size(); i++) {
				int rowIdx = Integer.parseInt(String.valueOf(addrs.get(i)));
				Row row;
				try { row = data.get(rowIdx); } catch (Exception e) { continue; }

				/* 复用已删除的槽位：软删除的副产品——isDelete=true的行仍占ArrayList位置，insert可原地复用 */
			/* Reuse deleted slot: side benefit of soft delete — isDelete=true rows still occupy ArrayList position, insert can reuse in-place */
			/* 复用优势：零ArrayList增长、零B+树新增节点、内存恒定。时序：unfreeze→setDelete(false)→extractIndexValues→fillRow→refreeze */
			/* Reuse advantage: zero ArrayList growth, zero new B+ tree nodes, constant memory. Order: unfreeze→setDelete(false)→extractIndexValues→fillRow→refreeze */
			if (row.isDelete()) {
					row.unfreeze();
					row.setDelete(false);
					Object[] oldIndexValues = extractIndexValues(row);
					fillRow(row, newData);
					row.freeze();
					row.touch();
					rebuildIndexes(rowIdx, oldIndexValues, row);
					return;
				}
			}
			/* 主键已存在且行活跃→幂等更新 / Key exists and row active → idempotent update */
			int rowIdx = Integer.parseInt(String.valueOf(addrs.get(0)));
			Row row;
			try { row = data.get(rowIdx); } catch (Exception e) { return; }
			updateRowInternal(row, rowIdx, newData);
			return;
		}

		/* 全新行：追加到rowSet，构建所有索引，冻结 */
		/* Brand new row: append to rowSet, build all indexes, freeze */
		Row row = new Row(columnNames != null ? columnNames : newData.keySet().toArray(new String[0]));
		fillRow(row, newData);
		data.append(row);
		int newIdx = data.size() - 1;
		for (Map.Entry<String, BPTree> entry : index.entrySet()) {
			String col = entry.getKey();
			Object val = row.get(col);
			if (val != null) {
				Comparable idxKey = toKeyForColumn(col, val);
				entry.getValue().insertOrUpdate(idxKey, newIdx, false);
			}
		}
		row.freeze();
		row.touch();
	}

	/* 更新行：解冻→填数据→重新冻结→增量更新变化的B+树索引条目(仅更新值改变的列) */
	/* Update row: unfreeze → fill data → refreeze → incrementally update changed B+ tree entries (only changed columns) */
	public synchronized void update(String indexColumn, Object keyValue, HashMap<String, Object> newData) {
		BPTree tree = index.get(indexColumn);
		if (tree == null) return;

		Comparable key = toKeyForColumn(indexColumn, keyValue);
		ArrayList<Object> addrs = (ArrayList<Object>) tree.get(key);
		if (addrs == null) return;

		for (int i = 0; i < addrs.size(); i++) {
			int rowIdx = Integer.parseInt(String.valueOf(addrs.get(i)));
			Row row;
			try { row = data.get(rowIdx); } catch (Exception e) { continue; }
			if (!row.isDelete()) {
				updateRowInternal(row, rowIdx, newData);
				return;
			}
		}
	}

	/* 删除行：软删除——标记isDelete=true，Row留在ArrayList原位。同时从所有B+树索引中移除该rowIdx */
	/* Delete row: soft delete — marks isDelete=true, Row stays in ArrayList at original position. Also removes rowIdx from all B+ tree indexes */
	/* 软删除优势：O(1)完成，不触发ArrayList移位、不导致其他rowIdx失效。缺点：deleted行占内存，需compact()回收 */
	/* Soft delete advantage: O(1), no ArrayList shift, no rowIdx invalidation. Downside: deleted rows occupy memory, need compact() to reclaim */
	public synchronized void delete(String indexColumn, Object keyValue) {
		BPTree tree = index.get(indexColumn);
		if (tree == null) return;

		Comparable key = toKeyForColumn(indexColumn, keyValue);
		ArrayList<Object> addrs = (ArrayList<Object>) tree.get(key);
		if (addrs == null) return;

		for (int i = 0; i < addrs.size(); i++) {
			int rowIdx = Integer.parseInt(String.valueOf(addrs.get(i)));
			Row row;
			try { row = data.get(rowIdx); } catch (Exception e) { continue; }
			if (!row.isDelete()) {
				Object[] oldIndexValues = extractIndexValues(row);
				row.unfreeze();
				row.setDelete(true);
				row.freeze();
				removeFromIndexesWithValues(rowIdx, oldIndexValues);
				return;
			}
		}
	}

	/* 内部更新：提取旧索引值→解冻→填充→冻结→增量重建索引。注意extractIndexValues必须在unfreeze之后 */
	/* Internal update: extract old index values → unfreeze → fill → freeze → incremental rebuild. extractIndexValues MUST be after unfreeze */
	private void updateRowInternal(Row row, int rowIdx, HashMap<String, Object> newData) {
		Object[] oldIndexValues = extractIndexValues(row);
		row.unfreeze();
		fillRow(row, newData);
		row.freeze();
		row.touch();
		rebuildIndexes(rowIdx, oldIndexValues, row);
	}

	/**
	 * 将Map数据逐列填入Row对象
	 * Fill row fields from a HashMap column by column
	 */
	private void fillRow(Row row, HashMap<String, Object> newData) {
		for (Map.Entry<String, Object> entry : newData.entrySet()) {
			row.put(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * 提取行中所有索引列的值 → 用于增量索引重建
	 * Extract all indexed column values from a row → for incremental index rebuild
	 */
	private Object[] extractIndexValues(Row row) {
		Object[] values = new Object[index.size()];
		int i = 0;
		for (String col : index.keySet()) {
			values[i++] = row.get(col);
		}
		return values;
	}

	/* 增量重建索引：比较每列的oldKey和newKey，仅更新发生变化的列。oldList.remove用Integer.valueOf保证对象相等 */
	/* Incremental index rebuild: compare oldKey vs newKey per column, only update changed ones. Integer.valueOf for object equality */
	private void rebuildIndexes(int rowIdx, Object[] oldIndexValues, Row newRow) {
		int i = 0;
		for (Map.Entry<String, BPTree> entry : index.entrySet()) {
			String col = entry.getKey();
			BPTree tree = entry.getValue();

			Object oldVal = oldIndexValues[i++];
			Object newVal = newRow.get(col);

			Comparable oldKey = oldVal != null ? toKeyForColumn(col, oldVal) : null;
			Comparable newKey = newVal != null ? toKeyForColumn(col, newVal) : null;

			boolean changed = (oldKey == null && newKey != null)
					|| (oldKey != null && newKey == null)
					|| (oldKey != null && !oldKey.equals(newKey));

			if (changed) {
				if (oldKey != null) {
					ArrayList<Object> oldList = (ArrayList<Object>) tree.get(oldKey);
					if (oldList != null) {
						oldList.remove(Integer.valueOf(rowIdx));
						if (oldList.isEmpty()) {
							tree.remove(oldKey);
						}
					}
				}
				if (newKey != null) {
					tree.insertOrUpdate(newKey, rowIdx, false);
				}
			}
		}
	}

	/**
	 * 清空所有行数据和索引 → 重置表状态
	 * Clear all rows and indexes → reset table state
	 */
	public synchronized void truncate() {
		data.clear();
		for (BPTree tree : index.values()) {
			tree.clear();
		}
		indexColumnType.clear();
		evictCursor = 0;
	}

	public String[] getColumnNames() {
		return columnNames;
	}

	public rowSet getData() {
		return data;
	}

	public void setData(rowSet data) {
	}

	public List<HashMap<String, Object>> getMete() {
		return mete;
	}

	public void setMete(List<HashMap<String, Object>> mete) {
	}

	public HashMap<String, BPTree> getIndex() {
		return index;
	}

	public void setIndex(HashMap<String, BPTree> indexMap) {
		this.index.clear();
		this.index.putAll(indexMap);
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setColumnNames(String[] cols) {
		this.columnNames = cols;
	}

	/**
	 * 通过 COUNT(*) 估算加载行数 → 用于内存预检
	 * Estimate row count via COUNT(*) → for memory pre-check
	 */
	private int estimateRowCount(Connection conn) {
		PreparedStatement stmt = null;
		ResultSet rs = null;
		try {
			String countSql = "SELECT COUNT(*) FROM (" + sql + ") _t";
			stmt = conn.prepareStatement(countSql);
			if (queryParams != null) {
				for (int i = 0; i < queryParams.length; i++) {
					bindParam(stmt, i + 1, queryParams[i].trim());
				}
			}
			stmt.setQueryTimeout(30);
			rs = stmt.executeQuery();
			if (rs.next()) return rs.getInt(1);
		} catch (Exception e) {
		} finally {
			if (rs != null) try { rs.close(); } catch (Exception ignored) {}
			if (stmt != null) try { stmt.close(); } catch (Exception ignored) {}
		}
		return -1;
	}

	/* PreparedStatement参数绑定：自动推断类型 Long > Double > String */
	/* PreparedStatement parameter binding: auto-detect type Long > Double > String */
	private void bindParam(PreparedStatement stmt, int index, String param) throws Exception {
		try {
			if (param.contains(".")) {
				stmt.setDouble(index, Double.parseDouble(param));
			} else {
				stmt.setLong(index, Long.parseLong(param));
			}
		} catch (NumberFormatException e) {
			stmt.setString(index, param);
		}
	}

	/**
	 * 估算全量加载所需内存（行开销+列值+索引）
	 * Estimate memory needed for full load (row overhead + column values + indexes)
	 */
	private long estimateMemory(int rows, int cols, int indexCount) {
		long rowOverhead = 168;
		long valuesArray = 16 + 8L * cols;
		long avgColValue = 40;
		long valuesTotal = cols * avgColValue;
		long indexPerRow = indexCount * 64L;
		long listOverhead = 8;
		long bytesPerRow = rowOverhead + valuesArray + valuesTotal + indexPerRow + listOverhead;
		long treeOverhead = indexCount * rows * 20L;
		return (long) rows * bytesPerRow + treeOverhead;
	}

	/**
	 * 内存预检：预估量 > 可用内存 → 抛出OOM异常
	 * Memory pre-check: estimated > available → throw OOM exception
	 */
	private void checkMemory(long estimatedBytes, int rows) throws utilException {
		Runtime rt = Runtime.getRuntime();
		long maxMem = rt.maxMemory();
		long usedMem = rt.totalMemory() - rt.freeMemory();
		long available = maxMem - usedMem;
		long safetyMargin = (long) (estimatedBytes * 0.15);

		long needMB = (estimatedBytes + safetyMargin) / 1024 / 1024;
		long availMB = available / 1024 / 1024;

		System.out.println("[memory] table=" + name + " estimatedRows=" + rows
			+ " needMemory=" + needMB + "MB"
			+ " available=" + availMB + "MB"
			+ " jvmMax=" + (maxMem / 1024 / 1024) + "MB");

		if (estimatedBytes + safetyMargin > available) {
			String msg = "Insufficient memory for table '" + name + "': "
				+ "need " + needMB + "MB (rows=" + rows + "), "
				+ "available " + availMB + "MB, "
				+ "jvmMax=" + (maxMem / 1024 / 1024) + "MB. "
				+ "Please increase -Xmx.";
			System.err.println("[FATAL] " + msg);
			throw new utilException(msg, -1002);
		}
	}

	/**
	 * 构建列名→列序号的映射 → 用于快速按名称定位列
	 * Build column name → column index map → for fast column lookup by name
	 */
	private HashMap<String, Integer> buildColumnIndex() {
		HashMap<String, Integer> map = new HashMap<String, Integer>();
		if (columnNames != null) {
			for (int i = 0; i < columnNames.length; i++) {
				map.put(columnNames[i].toUpperCase(), i);
			}
		}
		return map;
	}

	/**
	 * 联合索引等值查询：CompositeKey → B+树定位
	 * Composite index point query: CompositeKey → B+ tree lookup
	 */
	public Object getComposite(String indexName, Object[] values) throws Exception {
		BPTree tree = index.get(indexName);
		if (tree == null) return null;
		CompositeKey key = CompositeKey.of(values);
		ArrayList<Object> addrs = (ArrayList<Object>) tree.get(key);
		if (addrs == null) return null;
		List<Object> list = new ArrayList<Object>();
		for (int i = 0; i < addrs.size(); i++) {
			int rowIdx = Integer.parseInt(String.valueOf(addrs.get(i)));
			Row row = data.get(rowIdx);
			if (row.isDelete()) continue;
			row.touch();
			list.add(row);
		}
		return list.isEmpty() ? null : limitResult(list);
	}

	/**
	 * 扫描全表删除TTL过期行 → 返回清理数量
	 * Scan all rows and delete TTL-expired ones → return count cleaned
	 */
	public int cleanExpired() {
		if (ttlMs <= 0) return 0;
		int cleaned = 0;
		int total = data.size();
		for (int i = 0; i < total; i++) {
			Row row;
			try { row = data.get(i); } catch (Exception e) { continue; }
			if (!row.isDelete() && isExpired(row)) {
				try {
					Object[] oldIdxValues = extractIndexValues(row);
					data.delete(i);
					removeFromIndexesWithValues(i, oldIdxValues);
					cleaned++;
				} catch (Exception e) { }
			}
		}
		return cleaned;
	}

	/* 压缩：移除ArrayList中所有已删除行，重建全部B+树索引(因为rowIdx变了) */
	/* Compact: remove all deleted rows from ArrayList, rebuild all B+ tree indexes (rowIdx changes) */
	public synchronized void compact() {
		data.compact();
		for (BPTree tree : index.values()) {
			tree.clear();
		}
		for (int i = 0; i < data.size(); i++) {
			Row row;
			try { row = data.get(i); } catch (Exception e) { continue; }
			for (Map.Entry<String, BPTree> entry : index.entrySet()) {
				String col = entry.getKey();
				if (compositeIndexDefs.containsKey(col)) continue;
				Object val = row.get(col);
				if (val != null) {
					entry.getValue().insertOrUpdate(toKeyForColumn(col, val), i, false);
				}
			}
			for (Map.Entry<String, String[]> cEntry : compositeIndexDefs.entrySet()) {
				BPTree tree = index.get(cEntry.getKey());
				if (tree == null) continue;
				HashMap<String, Integer> colIdx = buildColumnIndex();
				String[] cols = cEntry.getValue();
				Object[] vals = new Object[cols.length];
				for (int c = 0; c < cols.length; c++) {
					Integer ci = colIdx.get(cols[c].toUpperCase());
					vals[c] = ci != null ? row.get(ci) : null;
				}
				tree.insertOrUpdate(CompositeKey.of(vals), i, false);
			}
		}
		System.out.println("[compact] table=" + name + " rows=" + data.size());
	}

	/* 从B+树索引中移除指定行的所有条目。oldList.remove(Integer.valueOf)保证按对象删除而非按下标 */
	/* Remove all entries for a given row from B+ tree indexes. Integer.valueOf ensures object equality, not index removal */
	private void removeFromIndexesWithValues(int rowIdx, Object[] oldIndexValues) {
		int i = 0;
		for (Map.Entry<String, BPTree> entry : index.entrySet()) {
			BPTree tree = entry.getValue();
			Object val = oldIndexValues[i++];
			if (val == null) continue;
			Comparable key = toKeyForColumn(entry.getKey(), val);
			ArrayList<Object> list = (ArrayList<Object>) tree.get(key);
			if (list != null) {
				list.remove(Integer.valueOf(rowIdx));
				if (list.isEmpty()) {
					tree.remove(key);
				}
			}
		}
	}

	/**
	 * 结果集截断 → 防止OOM，上限MAX_RESULT_ROWS
	 * Truncate result set → prevent OOM, capped at MAX_RESULT_ROWS
	 */
	private List<Object> limitResult(List<Object> list) {
		if (list != null && list.size() > MAX_RESULT_ROWS) {
			return new ArrayList<Object>(list.subList(0, MAX_RESULT_ROWS));
		}
		return list;
	}
}
