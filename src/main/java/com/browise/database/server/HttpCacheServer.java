package com.browise.database.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.browise.core.util.DBUtil;
import com.browise.database.Main;
import com.browise.database.SqlQueryEngine;
import com.browise.database.SqlQueryEngine.QueryResult;
import com.browise.database.database;
import com.browise.database.replication.ReplicationManager;
import com.browise.database.table.Row;
import com.browise.database.table.Table;

/* HTTP缓存服务：通过HttpServerEngine接口解耦底层HTTP实现（JDK/Undertow） */
/* HTTP cache server: decoupled from HTTP implementation via HttpServerEngine interface (JDK/Undertow) */
/* 默认JDK引擎零依赖，配置server.http.engine=undertow切换高性能引擎 */
/* Default JDK engine has zero dependency, set server.http.engine=undertow for high-performance engine */
public class HttpCacheServer {

	private HttpServerEngine engine;
	private int port;
	private int threads;
	private LinkedHashMap<String, HttpServerEngine.RouteHandler> pendingRoutes = new LinkedHashMap<String, HttpServerEngine.RouteHandler>();

	/**
	 * 构造HTTP缓存服务（默认线程数）
	 * Construct HTTP cache server with default thread count
	 */
	public HttpCacheServer(int port) throws Exception {
		this(port, 0);
	}

	/**
	 * 构造HTTP缓存服务，指定端口和线程数，自动创建引擎
	 * Construct HTTP cache server with port and thread count, auto-creates engine
	 */
	public HttpCacheServer(int port, int threads) throws Exception {
		this.port = port;
		this.threads = threads;
		this.engine = createEngine();
		collectRoutes();
	}

	/**
	 * 构造HTTP缓存服务，使用外部传入的引擎实例
	 * Construct HTTP cache server with an externally provided engine instance
	 */
	public HttpCacheServer(int port, int threads, HttpServerEngine engine) throws Exception {
		this.port = port;
		this.threads = threads;
		this.engine = engine;
		collectRoutes();
	}

	/**
	 * 根据配置创建HTTP引擎（优先Undertow，回退JDK）
	 * Create HTTP engine based on config (prefer Undertow, fallback to JDK)
	 */
	private static HttpServerEngine createEngine() {
		String engineName = DBUtil.getConfig("server.http.engine", "jdk").trim().toLowerCase();
		if ("undertow".equals(engineName)) {
			try {
				return (HttpServerEngine) Class.forName("com.browise.database.server.UndertowEngine").newInstance();
			} catch (Exception e) {
				System.err.println("[http] Undertow engine not available, falling back to JDK: " + e.getMessage());
			}
		}
		return new JdkEngine();
	}

	/**
	 * 收集所有路由处理器到待注册列表
	 * Collect all route handlers into pending registration list
	 */
	private void collectRoutes() {
		pendingRoutes.put("/cache/load", this::handleLoad);
		pendingRoutes.put("/cache/query", this::handleQuery);
		pendingRoutes.put("/cache/get", this::handleGet);
		pendingRoutes.put("/cache/less", this::handleLess);
		pendingRoutes.put("/cache/more", this::handleMore);
		pendingRoutes.put("/cache/range", this::handleRange);
		pendingRoutes.put("/cache/composite", this::handleComposite);
		pendingRoutes.put("/cache/ttl", this::handleTTL);
		pendingRoutes.put("/cache/maxSize", this::handleMaxSize);
		pendingRoutes.put("/cache/stats", this::handleStats);
		pendingRoutes.put("/cache/clean", this::handleClean);
		pendingRoutes.put("/cache/truncate", this::handleTruncate);
		pendingRoutes.put("/cache/list", this::handleList);
		pendingRoutes.put("/cache/metrics", this::handleMetrics);
		pendingRoutes.put("/cache/refresh", this::handleRefresh);
		pendingRoutes.put("/cache/health", this::handleHealth);
		pendingRoutes.put("/cache/compact", this::handleCompact);
		pendingRoutes.put("/cache/insert", this::handleInsert);
		pendingRoutes.put("/cache/update", this::handleUpdate);
		pendingRoutes.put("/cache/delete", this::handleDelete);
		pendingRoutes.put("/", this::handleHelp);
	}

	/**
	 * 将收集的路由注册到HTTP引擎
	 * Register all collected routes to the HTTP engine
	 */
	private void registerRoutes() {
		for (Map.Entry<String, HttpServerEngine.RouteHandler> entry : pendingRoutes.entrySet()) {
			engine.registerRoute(entry.getKey(), entry.getValue());
		}
	}

	/**
	 * 启动HTTP服务，注册路由并启动引擎
	 * Start HTTP server, register routes and start engine
	 */
	public void start() {
		registerRoutes();
		engine.start(port, threads);
	}

	/**
	 * 停止HTTP服务
	 * Stop HTTP server
	 */
	public void stop() {
		engine.stop();
	}

	/* ========== Route Handlers ========== */

	/**
	 * 获取表或返回错误响应
	 * Get table by name or send error response if not found
	 */
	private Table getTableOrError(String tableName, HttpServerEngine.Response resp) {
		if (tableName == null) {
			resp.sendError(400, "Missing table");
			return null;
		}
		Table t = database.getTable(tableName);
		if (t == null) {
			resp.sendError(404, "Table not found: " + tableName);
			return null;
		}
		return t;
	}

	/**
	 * POST /cache/load — 从数据库加载表到缓存
	 * Load table from database into cache
	 */
	private void handleLoad(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		if (!"POST".equals(req.getMethod())) {
			resp.sendError(405, "Method Not Allowed");
			return;
		}
		String table = req.getBodyParams().get("table");
		if (table == null || table.isEmpty()) {
			resp.sendError(400, "Missing 'table' parameter");
			return;
		}
		if (!table.matches("[a-zA-Z0-9_]+")) {
			resp.sendError(400, "Invalid table name");
			return;
		}
		Table t = database.loadFromConfig(table);
		resp.sendJson(200, jsonResult(0, "loaded", "{\"table\":\"" + table + "\",\"rows\":" + t.getData().activeCount() + "}"));
	}

	/**
	 * GET/POST /cache/query — 执行SQL查询（从缓存或数据库）
	 * Execute SQL query (from cache or database)
	 */
	private void handleQuery(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String sql = params.get("sql");
		if (sql == null || sql.isEmpty()) {
			resp.sendError(400, "Missing 'sql' parameter");
			return;
		}
		QueryResult qr = SqlQueryEngine.query(sql);
		if ("no_index".equals(qr.method)) {
			resp.sendJson(200, jsonResult(1, "no index for this query", "[]"));
		} else if (qr.rows == null || qr.rows.isEmpty()) {
			resp.sendJson(200, jsonResult(0, "empty", "[]"));
		} else {
			resp.sendJson(200, jsonResult(0, "ok", rowsToJson(qr.rows)));
		}
	}

	/**
	 * GET /cache/get — 按列精确匹配查询
	 * Exact match query by column
	 */
	private void handleGet(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		Object result = t.get(column, parseValue(value));
		resp.sendJson(200, jsonResult(0, "ok", rowsToJson(result)));
	}

	/**
	 * GET /cache/less — 小于条件查询
	 * Less-than query
	 */
	private void handleLess(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		Object result = t.getLessThen(column, parseValue(value));
		resp.sendJson(200, jsonResult(0, "ok", rowsToJson(result)));
	}

	/**
	 * GET /cache/more — 大于条件查询
	 * Greater-than query
	 */
	private void handleMore(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		Object result = t.getMoreThen(column, parseValue(value));
		resp.sendJson(200, jsonResult(0, "ok", rowsToJson(result)));
	}

	/**
	 * GET /cache/range — 范围查询（between）
	 * Range query (between)
	 */
	private void handleRange(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		String column = params.get("column");
		String from = params.get("from");
		String to = params.get("to");
		if (column == null || from == null || to == null) {
			resp.sendError(400, "Missing column/from/to");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		Object result = t.getMoreAndLessThen(column, parseValue(from), parseValue(to));
		resp.sendJson(200, jsonResult(0, "ok", rowsToJson(result)));
	}

	/**
	 * GET /cache/composite — 复合索引查询
	 * Composite index query
	 */
	private void handleComposite(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		String indexName = params.get("index");
		String valuesStr = params.get("values");
		if (indexName == null || valuesStr == null) {
			resp.sendError(400, "Missing index/values");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		String[] parts = valuesStr.split(",");
		Object[] values = new Object[parts.length];
		for (int i = 0; i < parts.length; i++) {
			values[i] = parseValue(parts[i]);
		}
		Object result = t.getComposite(indexName, values);
		resp.sendJson(200, jsonResult(0, "ok", rowsToJson(result)));
	}

	/**
	 * POST /cache/ttl — 设置表的TTL（毫秒）
	 * Set table TTL in milliseconds
	 */
	private void handleTTL(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String ms = params.get("ms");
		if (ms == null) { resp.sendError(400, "Missing ms"); return; }
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		t.setTTL(Long.parseLong(ms));
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"ttl\":" + ms + "}"));
	}

	/**
	 * POST /cache/maxSize — 设置表的最大行数
	 * Set table max row count
	 */
	private void handleMaxSize(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String size = params.get("size");
		if (size == null) { resp.sendError(400, "Missing size"); return; }
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		t.setMaxSize(Integer.parseInt(size));
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"maxSize\":" + size + "}"));
	}

	/**
	 * GET /cache/stats — 查看表统计信息
	 * View table statistics
	 */
	private void handleStats(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.getQueryParams();
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		String table = params.get("table");
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"table\":\"").append(table).append("\",");
		sb.append("\"totalRows\":").append(t.getData().size()).append(",");
		sb.append("\"activeRows\":").append(t.getData().activeCount()).append(",");
		sb.append("\"ttl\":").append(t.getTTL()).append(",");
		sb.append("\"maxSize\":").append(t.getMaxSize()).append(",");
		sb.append("\"indexes\":[");
		if (t.getIndex() != null) {
			boolean first = true;
			for (String idx : t.getIndex().keySet()) {
				if (!first) sb.append(",");
				sb.append("\"").append(idx).append("\"");
				first = false;
			}
		}
		sb.append("]}");
		resp.sendJson(200, jsonResult(0, "ok", sb.toString()));
	}

	/**
	 * POST /cache/clean — 清理表中过期行
	 * Clean expired rows from table
	 */
	private void handleClean(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		String table = params.get("table");
		int cleaned = t.cleanExpired();
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + table + "\",\"cleaned\":" + cleaned + "}"));
	}

	/**
	 * POST /cache/truncate — 清空表数据
	 * Truncate all table data
	 */
	private void handleTruncate(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		String table = params.get("table");
		t.truncate();
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + table + "\",\"truncated\":true}"));
	}

	/**
	 * GET /cache/list — 列出所有表
	 * List all tables
	 */
	private void handleList(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		StringBuilder sb = new StringBuilder("[");
		List<Map<String, Object>> tables = database.listTables();
		for (int i = 0; i < tables.size(); i++) {
			if (i > 0) sb.append(",");
			Map<String, Object> info = tables.get(i);
			sb.append("{\"name\":\"").append(info.get("name")).append("\"");
			sb.append(",\"sql\":\"").append(escapeJson(String.valueOf(info.get("sql")))).append("\"}");
		}
		sb.append("]");
		resp.sendJson(200, jsonResult(0, "ok", sb.toString()));
	}

	/**
	 * GET /cache/metrics — 查看系统指标（内存、线程、表详情）
	 * View system metrics (memory, threads, table details)
	 */
	private void handleMetrics(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Runtime rt = Runtime.getRuntime();
		long totalMem = rt.totalMemory();
		long freeMem = rt.freeMemory();
		long usedMem = totalMem - freeMem;
		long maxMem = rt.maxMemory();
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"tables\":").append(database.tableCount()).append(",");
		sb.append("\"memory\":{");
		sb.append("\"used\":").append(usedMem).append(",");
		sb.append("\"total\":").append(totalMem).append(",");
		sb.append("\"max\":").append(maxMem).append(",");
		sb.append("\"free\":").append(freeMem);
		sb.append("},");
		sb.append("\"threads\":").append(Thread.activeCount()).append(",");
		sb.append("\"tables_detail\":[");
		List<Map<String, Object>> tables = database.listTables();
		for (int i = 0; i < tables.size(); i++) {
			if (i > 0) sb.append(",");
			String name = (String) tables.get(i).get("name");
			Table t = database.getTable(name);
			sb.append("{\"name\":\"").append(name).append("\"");
			sb.append(",\"rows\":").append(t.getData().activeCount());
			sb.append(",\"capacity\":").append(t.getData().size());
			if (t.getIndex() != null) {
				sb.append(",\"indexes\":").append(t.getIndex().size());
			}
			sb.append("}");
		}
		sb.append("]}");
		resp.sendJson(200, jsonResult(0, "ok", sb.toString()));
	}

	/**
	 * POST /cache/refresh — 从数据库刷新所有表
	 * Refresh all tables from database
	 */
	private void handleRefresh(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		if (!"POST".equals(req.getMethod())) {
			resp.sendError(405, "Method Not Allowed");
			return;
		}
		database.refreshAll();
		resp.sendJson(200, jsonResult(0, "refreshed", "{\"tables\":" + database.tableCount() + "}"));
	}

	/**
	 * GET / — 返回帮助信息，列出所有可用端点
	 * Return help info listing all available endpoints
	 */
	private void handleHelp(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		String help = "{\"endpoints\":[" +
			"\"POST /cache/load      {table}\"," +
			"\"GET|POST /cache/query ?sql=select...\"," +
			"\"GET  /cache/get       ?table&column&value\"," +
			"\"GET  /cache/less       ?table&column&value\"," +
			"\"GET  /cache/more       ?table&column&value\"," +
			"\"GET  /cache/range      ?table&column&from&to\"," +
			"\"GET  /cache/composite ?table&index&values\"," +
			"\"POST /cache/ttl       {table,ms}\"," +
			"\"POST /cache/maxSize   {table,size}\"," +
			"\"GET  /cache/stats     ?table\"," +
			"\"POST /cache/clean     {table}\"," +
			"\"POST /cache/truncate  {table}\"," +
			"\"GET  /cache/list\"," +
			"\"GET  /cache/metrics\"," +
			"\"POST /cache/refresh\"," +
			"\"GET  /cache/health\"," +
			"\"POST /cache/compact  {table}\"," +
			"\"POST /cache/insert\"," +
			"\"POST /cache/update\"," +
			"\"POST /cache/delete\"," +
			"\"GET  /\"]}";
		resp.sendJson(200, jsonResult(0, "CacheSQL", help));
	}

	/**
	 * GET /cache/health — 健康检查（含内存和连接池状态）
	 * Health check with memory and connection pool status
	 */
	private void handleHealth(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Runtime rt = Runtime.getRuntime();
		long usedMem = rt.totalMemory() - rt.freeMemory();
		long maxMem = rt.maxMemory();
		boolean healthy = usedMem < maxMem * 0.9;
		StringBuilder sb = new StringBuilder("{");
		sb.append("\"status\":\"").append(healthy ? "UP" : "WARN").append("\",");
		sb.append("\"tables\":").append(database.tableCount()).append(",");
		sb.append("\"memory\":{\"used\":").append(usedMem / 1024 / 1024);
		sb.append(",\"max\":").append(maxMem / 1024 / 1024).append("},");
		sb.append("\"pool\":{\"active\":").append(DBUtil.poolActive());
		sb.append(",\"idle\":").append(DBUtil.poolIdle()).append("}");
		sb.append("}");
		resp.sendJson(healthy ? 200 : 503, jsonResult(healthy ? 0 : 1, healthy ? "UP" : "WARN", sb.toString()));
	}

	/**
	 * POST /cache/compact — 压缩表，回收已删除行的空间
	 * Compact table to reclaim space from deleted rows
	 */
	private void handleCompact(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		t.compact();
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"rows\":" + t.getData().activeCount() + "}"));
	}

	/**
	 * POST /cache/insert — 插入新行（支持复制同步）
	 * Insert a new row (with replication sync)
	 */
	private void handleInsert(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		HashMap<String, Object> data = extractDataParams(params);
		ReplicationManager.insert(t, column, parseValue(value), data);
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"op\":\"insert\"}"));
	}

	/**
	 * POST /cache/update — 更新行（支持复制同步）
	 * Update a row (with replication sync)
	 */
	private void handleUpdate(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		HashMap<String, Object> data = extractDataParams(params);
		ReplicationManager.update(t, column, parseValue(value), data);
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"op\":\"update\"}"));
	}

	/**
	 * POST /cache/delete — 删除行（支持复制同步）
	 * Delete a row (with replication sync)
	 */
	private void handleDelete(HttpServerEngine.Request req, HttpServerEngine.Response resp) throws Exception {
		Map<String, String> params = req.params();
		String column = params.get("column");
		String value = params.get("value");
		if (column == null || value == null) {
			resp.sendError(400, "Missing column/value");
			return;
		}
		Table t = getTableOrError(params.get("table"), resp);
		if (t == null) return;
		ReplicationManager.delete(t, column, parseValue(value));
		resp.sendJson(200, jsonResult(0, "ok", "{\"table\":\"" + params.get("table") + "\",\"op\":\"delete\"}"));
	}

	/* ========== Utility Methods ========== */

	/**
	 * 将字符串值解析为合适类型（尝试转为Long，失败则保留字符串）
	 * Parse string value to appropriate type (try Long, fallback to String)
	 */
	private Object parseValue(String value) {
		if (value == null || value.isEmpty()) return value;
		if (value.length() > 1 && value.startsWith("0")) return value;
		try { return Long.parseLong(value); } catch (NumberFormatException e) { return value; }
	}

	/**
	 * 从请求参数中提取数据字段（排除table/column/value）
	 * Extract data fields from request params (excluding table/column/value)
	 */
	private HashMap<String, Object> extractDataParams(Map<String, String> params) {
		HashMap<String, Object> data = new HashMap<String, Object>();
		String indexColumn = params.get("column");
		String indexValue = params.get("value");
		if (indexColumn != null && indexValue != null) {
			data.put(indexColumn, parseValue(indexValue));
		}
		for (Map.Entry<String, String> entry : params.entrySet()) {
			String key = entry.getKey();
			if (!key.equals("table") && !key.equals("column") && !key.equals("value")) {
				data.put(key, parseValue(entry.getValue()));
			}
		}
		return data;
	}

	/**
	 * 将查询结果对象转为JSON数组字符串
	 * Convert query result Object to JSON array string
	 */
	private String rowsToJson(Object result) {
		if (result == null) return "[]";
		if (!(result instanceof List)) return "[]";
		List<?> rows = (List<?>) result;
		return rowsToJson(rows);
	}

	/**
	 * 将行列表转为JSON数组字符串
	 * Convert row list to JSON array string
	 */
	private String rowsToJson(List<?> rows) {
		if (rows == null) return "[]";
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < rows.size(); i++) {
			if (i > 0) sb.append(",");
			Object obj = rows.get(i);
			if (obj instanceof Row) {
				sb.append(rowToJson((Row) obj));
			} else {
				sb.append("\"").append(escapeJson(String.valueOf(obj))).append("\"");
			}
		}
		sb.append("]");
		return sb.toString();
	}

	/**
	 * 将单行转为JSON对象字符串
	 * Convert a single Row to JSON object string
	 */
	private String rowToJson(Row row) {
		StringBuilder sb = new StringBuilder("{");
		String[] cols = row.getColumnNames();
		if (cols != null) {
			for (int i = 0; i < cols.length; i++) {
				if (i > 0) sb.append(",");
				Object val = row.get(i);
				sb.append("\"").append(cols[i]).append("\":");
				if (val instanceof Number) {
					sb.append(val);
				} else {
					sb.append("\"").append(escapeJson(String.valueOf(val))).append("\"");
				}
			}
		}
		sb.append("}");
		return sb.toString();
	}

	private String jsonResult(int code, String message, String data) {
		return "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":" + data + "}";
	}

	/**
	 * 转义JSON字符串中的特殊字符
	 * Escape special characters in JSON string
	 */
	private String escapeJson(String s) {
		if (s == null) return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}

	/**
	 * 入口方法，委托给Main.main
	 * Entry point, delegates to Main.main
	 */
	public static void main(String[] args) throws Exception {
		Main.main(args);
	}
}
