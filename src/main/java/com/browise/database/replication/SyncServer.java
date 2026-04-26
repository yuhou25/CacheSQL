package com.browise.database.replication;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import com.browise.database.database;
import com.browise.database.table.Table;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/* Slave端同步服务：接收Master推送的写操作，在本地Table回放 */
/* Slave sync server: receives write ops pushed from Master, replays on local Table */
public class SyncServer {

	private HttpServer server;
	private int port;
	/* Slave已成功回放的最新序列号，Master据此判断需要补发哪些op */
	/* Latest sequence number successfully replayed by slave, master uses this to determine which ops to catch up */
	private volatile long lastSeq = 0;

	/**
	 * 创建同步服务，注册/op /catchup /status /heartbeat四个HTTP端点
	 * Create sync server, register /op /catchup /status /heartbeat HTTP endpoints
	 * @param port 监听端口 / listening port
	 */
	public SyncServer(int port) throws IOException {
		this.port = port;
		this.server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/sync/op", this::handleOp);
		server.createContext("/sync/catchup", this::handleCatchup);
		server.createContext("/sync/status", this::handleStatus);
		server.createContext("/sync/heartbeat", this::handleHeartbeat);
	}

	/**
	 * 启动HTTP服务开始监听
	 * Start the HTTP server to begin listening
	 */
	public void start() {
		server.start();
		System.out.println("[sync] SyncServer started on port " + port);
	}

	/**
	 * 停止HTTP服务
	 * Stop the HTTP server
	 */
	public void stop() {
		server.stop(0);
	}

	public long getLastSeq() {
		return lastSeq;
	}

	/* 接收并回放单个写操作 / Receive and replay a single write operation */
	private void handleOp(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			sendError(exchange, 405, "Method Not Allowed");
			return;
		}
		try {
			Map<String, String> params = parseBody(exchange);
			String op = params.get("op");
			String tableName = params.get("table");
			String indexColumn = params.get("indexColumn");
			String keyValue = params.get("keyValue");
			String seqStr = params.get("seq");

			if (op == null || tableName == null) {
				sendError(exchange, 400, "Missing op or table");
				return;
			}

			Table table = database.getTable(tableName);
			if (table == null) {
				sendError(exchange, 404, "Table not found: " + tableName);
				return;
			}

			HashMap<String, Object> data = extractData(params);

			replayOp(table, op, indexColumn, keyValue, data);

			if (seqStr != null) {
				try { lastSeq = Long.parseLong(seqStr); } catch (NumberFormatException ignored) {}
			}

			sendJson(exchange, 200, "{\"code\":0,\"lastSeq\":" + lastSeq + "}");
		} catch (Exception e) {
			sendError(exchange, 500, e.getMessage());
		}
	}

	/* 批量回放：Master补发Slave缺失的多个op / Batch replay: master sends multiple missed ops */
	private void handleCatchup(HttpExchange exchange) throws IOException {
		if (!"POST".equals(exchange.getRequestMethod())) {
			sendError(exchange, 405, "Method Not Allowed");
			return;
		}
		try {
			Map<String, String> params = parseBody(exchange);
			String tableName = params.get("table");
			if (tableName == null) {
				sendError(exchange, 400, "Missing table");
				return;
			}
			Table table = database.getTable(tableName);
			if (table == null) {
				sendError(exchange, 404, "Table not found: " + tableName);
				return;
			}

			String opsStr = params.get("ops");
			if (opsStr == null || opsStr.isEmpty()) {
				sendJson(exchange, 200, "{\"code\":0,\"replayed\":0}");
				return;
			}

			String[] ops = opsStr.split("\\|");
			int replayed = 0;
			for (String opStr : ops) {
				String[] parts = opStr.split(";");
				if (parts.length < 3) continue;
				String op = parts[0];
				String indexColumn = parts[1];
				String keyValue = parts.length > 2 ? parts[2] : null;
				HashMap<String, Object> data = null;
				if (parts.length > 3 && !parts[3].isEmpty()) {
					data = new HashMap<String, Object>();
					for (String kv : parts[3].split(",")) {
						String[] pair = kv.split("=", 2);
						if (pair.length == 2) {
							data.put(pair[0], parseValue(pair[1]));
						}
					}
				}
				replayOp(table, op, indexColumn, keyValue, data);
				replayed++;
			}

			String seqStr = params.get("lastSeq");
			if (seqStr != null) {
				try { lastSeq = Long.parseLong(seqStr); } catch (NumberFormatException ignored) {}
			}

			sendJson(exchange, 200, "{\"code\":0,\"replayed\":" + replayed + ",\"lastSeq\":" + lastSeq + "}");
		} catch (Exception e) {
			sendError(exchange, 500, e.getMessage());
		}
	}

	/**
	 * 处理Slave状态查询请求，返回当前lastSeq
	 * Handle slave status query request, return current lastSeq
	 */
	private void handleStatus(HttpExchange exchange) throws IOException {
		sendJson(exchange, 200, "{\"lastSeq\":" + lastSeq + "}");
	}

	/**
	 * 处理心跳检测请求，返回UP状态
	 * Handle heartbeat check request, return UP status
	 */
	private void handleHeartbeat(HttpExchange exchange) throws IOException {
		sendJson(exchange, 200, "{\"status\":\"UP\"}");
	}

	/**
	 * 在本地Table上回放Master推送的写操作
	 * Replay a write operation pushed from master on the local table
	 */
	private void replayOp(Table table, String op, String indexColumn, String keyValue,
			HashMap<String, Object> data) {
		Object key = parseValue(keyValue);
		switch (op) {
		case "insert":
			table.insert(indexColumn, key, data);
			break;
		case "update":
			table.update(indexColumn, key, data);
			break;
		case "delete":
			table.delete(indexColumn, key);
			break;
		}
	}

	/**
	 * 从请求参数中提取业务数据字段（排除op/table/indexColumn/keyValue/seq）
	 * Extract business data fields from request params (excluding op/table/indexColumn/keyValue/seq)
	 */
	private HashMap<String, Object> extractData(Map<String, String> params) {
		HashMap<String, Object> data = new HashMap<String, Object>();
		for (Map.Entry<String, String> entry : params.entrySet()) {
			String key = entry.getKey();
			if (!key.equals("op") && !key.equals("table") && !key.equals("indexColumn")
					&& !key.equals("keyValue") && !key.equals("seq")) {
				data.put(key, parseValue(entry.getValue()));
			}
		}
		return data;
	}

	/**
	 * 解析字符串值为合适类型（优先尝试Long，失败则保留原字符串）
	 * Parse string value to appropriate type (prefer Long, fallback to original string)
	 */
	private Object parseValue(String value) {
		if (value == null || value.isEmpty()) return value;
		if (value.length() > 1 && value.startsWith("0")) return value;
		try { return Long.parseLong(value); } catch (NumberFormatException e) { return value; }
	}

	/**
	 * 解析HTTP请求体（application/x-www-form-urlencoded）为键值映射
	 * Parse HTTP request body (application/x-www-form-urlencoded) into key-value map
	 */
	private Map<String, String> parseBody(HttpExchange exchange) throws IOException {
		InputStream is = exchange.getRequestBody();
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int len;
		while ((len = is.read(buf)) != -1) {
			baos.write(buf, 0, len);
			if (baos.size() > 65536) break;
		}
		String body = new String(baos.toByteArray(), "UTF-8");
		Map<String, String> params = new HashMap<String, String>();
		for (String param : body.split("&")) {
			String[] pair = param.split("=", 2);
			if (pair.length == 2) {
				try {
					params.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
				} catch (Exception e) {
					params.put(pair[0], pair[1]);
				}
			}
		}
		return params;
	}

	/**
	 * 发送JSON格式的HTTP响应
	 * Send a JSON-formatted HTTP response
	 */
	private void sendJson(HttpExchange exchange, int code, String json) throws IOException {
		byte[] response = json.getBytes("UTF-8");
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, response.length);
		OutputStream os = exchange.getResponseBody();
		os.write(response);
		os.close();
	}

	/**
	 * 发送错误格式的JSON HTTP响应
	 * Send an error-formatted JSON HTTP response
	 */
	private void sendError(HttpExchange exchange, int code, String message) throws IOException {
		sendJson(exchange, code, "{\"code\":" + code + ",\"message\":\"" + message + "\"}");
	}
}
