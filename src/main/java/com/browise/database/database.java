package com.browise.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.browise.core.exception.utilException;
import com.browise.core.util.DBUtil;
import com.browise.database.btree.BPTree;
import com.browise.database.table.Table;

/* 数据库管理：表注册、加载、定时刷新。ConcurrentHashMap保证表级操作的线程安全 */
/* Database manager: table registration, loading, scheduled refresh. ConcurrentHashMap for thread-safe table-level ops */
public class database {
	private static final ConcurrentHashMap<String, Table> tables = new ConcurrentHashMap<String, Table>();
	private static ScheduledExecutorService scheduler;
	private static long refreshInterval = DBUtil.getConfigInt("cache.refreshInterval", 0);

	/**
	 * 按名称获取已注册的表
	 * Get registered table by name
	 */
	public static Table getTable(String name) {
		return tables.get(name);
	}

	/**
	 * 加载表：注册表名/SQL/索引，首次加载时执行 SQL 拉取数据；已存在则直接返回
	 * Load table: register name/SQL/indexes, execute SQL on first load; return existing if already present
	 */
	public static Table load(String name, String sql, String[] indexes) throws utilException {
		Table existing = tables.get(name);
		if (existing != null) return existing;

		Table obj = new Table();
		obj.init(name);
		obj.setSql(sql);
		if (indexes != null) {
			HashMap<String, BPTree> indexMap = new HashMap<String, BPTree>();
			for (String idx : indexes) {
				if (idx.contains("+")) {
					String[] cols = idx.split("\\+");
					indexMap.put(idx, new BPTree(256));
					obj.addCompositeIndex(idx, cols);
				} else {
					indexMap.put(idx, new BPTree(256));
				}
			}
			obj.setIndex(indexMap);
		}
		if (sql != null && !sql.equals(name)) {
			obj.init();
		}
		Table prev = tables.putIfAbsent(name, obj);
		return prev != null ? prev : obj;
	}

	/**
	 * 加载空表：仅注册表名，不执行 SQL（用于纯写入不需要初始数据的场景）
	 * Load empty table: register name only, no SQL execution (for write-only scenarios without initial data)
	 */
	public static int load(String name) {
		Table obj = tables.get(name);
		if (obj == null) {
			Table fresh = new Table();
			fresh.init(name);
			Table prev = tables.putIfAbsent(name, fresh);
		}
		return 0;
	}

	/* 从配置文件加载表：读取cache.table.<name>.sql/params/indexes，init()完成后才发布到tables map */
	/* Load table from config: reads cache.table.<name>.sql/params/indexes, publishes to tables map only after init() completes */
	/* 消除SQL注入风险：SQL来自配置文件而非HTTP参数(C-6修复) */
	/* Eliminates SQL injection: SQL comes from config file, not HTTP parameters (C-6 fix) */
	public static Table loadFromConfig(String name) throws utilException {
		String sql = DBUtil.getConfig("cache.table." + name + ".sql", null);
		if (sql == null || sql.trim().isEmpty()) {
			throw new utilException("No SQL configured for table '" + name
				+ "'. Add cache.table." + name + ".sql to config.properties", -9005);
		}
		sql = sql.trim();

		String indexStr = DBUtil.getConfig("cache.table." + name + ".indexes", "");
		String paramsStr = DBUtil.getConfig("cache.table." + name + ".params", null);

		String[] indexes = null;
		if (!indexStr.trim().isEmpty()) {
			String[] raw = indexStr.split(",");
			indexes = new String[raw.length];
			for (int i = 0; i < raw.length; i++) {
				indexes[i] = raw[i].trim();
			}
		}

		String[] params = null;
		if (paramsStr != null && !paramsStr.trim().isEmpty()) {
			params = paramsStr.split(",");
		}

		Table existing = tables.get(name);
		if (existing != null) return existing;

		Table obj = new Table();
		obj.init(name);
		obj.setSql(sql);
		if (params != null) {
			obj.setQueryParams(params);
		}
		if (indexes != null) {
			HashMap<String, BPTree> indexMap = new HashMap<String, BPTree>();
			for (String idx : indexes) {
				if (idx.contains("+")) {
					String[] cols = idx.split("\\+");
					indexMap.put(idx, new BPTree(256));
					obj.addCompositeIndex(idx, cols);
				} else {
					indexMap.put(idx, new BPTree(256));
				}
			}
			obj.setIndex(indexMap);
		}
		if (!sql.equals(name)) {
			obj.init();
		} else {
			String colStr = DBUtil.getConfig("cache.table." + name + ".columns", "");
			if (!colStr.trim().isEmpty()) {
				String[] cols = colStr.split(",");
				for (int i = 0; i < cols.length; i++) {
					cols[i] = cols[i].trim();
				}
				obj.setColumnNames(cols);
			}
		}
		Table prev = tables.putIfAbsent(name, obj);
		return prev != null ? prev : obj;
	}

	/**
	 * 从配置文件批量加载所有 cache.table.<name> 表
	 * Batch load all cache.table.<name> tables from config
	 */
	public static int loadAllFromConfig() {
		int loaded = 0;
		for (String key : DBUtil.getConfigKeys()) {
			if (key.startsWith("cache.table.") && key.endsWith(".sql")) {
				String name = key.substring("cache.table.".length(), key.length() - ".sql".length());
				try {
					Table t = loadFromConfig(name);
					if (t != null) {
						loaded++;
						System.out.println("[cache] table '" + name + "' loaded, rows=" + t.getData().activeCount());
					}
				} catch (Exception e) {
					System.err.println("[cache] failed to load table '" + name + "': " + e.getMessage());
				}
			}
		}
		System.out.println("[cache] auto-loaded " + loaded + " table(s) from config");
		return loaded;
	}

	/**
	 * 返回所有已注册表的元信息列表（名称+SQL）
	 * Return metadata list of all registered tables (name + SQL)
	 */
	public static List<Map<String, Object>> listTables() {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (Map.Entry<String, Table> entry : tables.entrySet()) {
			Map<String, Object> info = new HashMap<String, Object>();
			info.put("name", entry.getKey());
			info.put("sql", entry.getValue().getSql());
			list.add(info);
		}
		return list;
	}

	public static boolean removeTable(String name) {
		return tables.remove(name) != null;
	}

	/**
	 * 启动定时刷新线程，按 cache.refreshInterval 间隔重新加载所有表数据
	 * Start scheduled refresh thread, reloads all table data at cache.refreshInterval
	 */
	public static synchronized void startAutoRefresh() {
		if (refreshInterval <= 0 || scheduler != null) return;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "cache-refresh");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleAtFixedRate(() -> {
			try {
				refreshAll();
			} catch (Exception e) {
				System.out.println("[refresh] error: " + e.getMessage());
			}
		}, refreshInterval, refreshInterval, TimeUnit.MILLISECONDS);
		System.out.println("[refresh] auto refresh started, interval=" + refreshInterval + "ms");
	}

	/**
	 * 停止定时刷新线程
	 * Stop the scheduled refresh thread
	 */
	public static synchronized void stopAutoRefresh() {
		if (scheduler != null) {
			scheduler.shutdown();
			scheduler = null;
			System.out.println("[refresh] stopped");
		}
	}

	/* 定时刷新：为每个表创建新Table实例加载最新数据，完成后原子替换map中的引用。refresh期间查询不受影响 */
	/* Scheduled refresh: creates new Table instance per table with latest data, atomically swaps map reference. Queries unaffected during refresh */
	public static void refreshAll() throws Exception {
		for (Map.Entry<String, Table> entry : tables.entrySet()) {
			String name = entry.getKey();
			Table old = entry.getValue();
			if (old.getSql() == null || old.getSql().isEmpty()) continue;

			String sql = old.getSql();
			HashMap<String, BPTree> oldIndexes = new HashMap<String, BPTree>();
			for (Map.Entry<String, BPTree> idx : old.getIndex().entrySet()) {
				oldIndexes.put(idx.getKey(), idx.getValue());
			}

			Table fresh = new Table();
			fresh.init(name);
			fresh.setSql(sql);
			if (old.getQueryParams() != null) {
				fresh.setQueryParams(old.getQueryParams());
			}
			if (!oldIndexes.isEmpty()) {
				HashMap<String, BPTree> indexMap = new HashMap<String, BPTree>();
				for (Map.Entry<String, BPTree> idx : oldIndexes.entrySet()) {
					if (idx.getKey().contains("+")) {
						String[] cols = idx.getKey().split("\\+");
						indexMap.put(idx.getKey(), new BPTree(256));
						fresh.addCompositeIndex(idx.getKey(), cols);
					} else {
						indexMap.put(idx.getKey(), new BPTree(256));
					}
				}
				fresh.setIndex(indexMap);
			}
			fresh.init();
			tables.put(name, fresh);
			System.out.println("[refresh] " + name + " reloaded, rows=" + fresh.getData().activeCount());
		}
	}

	public static int tableCount() {
		return tables.size();
	}
}
