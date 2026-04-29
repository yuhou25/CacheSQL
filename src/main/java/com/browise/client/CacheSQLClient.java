package com.browise.client;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * CacheSQL 客户端工具包 — 按表名自动路由到对应组，读负载均衡到主/从节点
 * CacheSQL client — auto-route by table name, read load-balance across master/slave
 *
 * 配置 / Config:
 *   cachesql.group.insurance.master=http://192.168.1.10:8080
 *   cachesql.group.insurance.slaves=http://192.168.1.11:8080,http://192.168.1.12:8080
 *   cachesql.group.insurance.tables=KCA2,KCA3
 *
 * 使用 / Usage:
 *   CacheSQLClient client = new CacheSQLClient("cachesql.properties");
 *   List<Map<String, Object>> rows = client.get("KCA2", "AAC001", "12345");
 *   boolean ok = client.insert("KCA2", "AAC001", "99999", data);
 */
public class CacheSQLClient {

	private final Map<String, TableGroup> tableToGroup = new LinkedHashMap<String, TableGroup>();
	private int connectTimeout = 5000;
	private int readTimeout = 10000;

	/**
	 * 从配置文件加载路由规则
	 * Load routing rules from config file
	 */
	public CacheSQLClient(String configFile) throws IOException {
		this(loadProperties(configFile));
	}

	/**
	 * 从 Properties 对象加载路由规则
	 * Load routing rules from Properties object
	 */
	public CacheSQLClient(Properties props) {
		connectTimeout = getInt(props, "cachesql.connectTimeout", 5000);
		readTimeout = getInt(props, "cachesql.readTimeout", 10000);
		for (String key : props.stringPropertyNames()) {
			if (!key.endsWith(".tables")) continue;
			String groupName = key.substring("cachesql.group.".length(), key.length() - ".tables".length());
			String master = props.getProperty("cachesql.group." + groupName + ".master", "").trim();
			String slavesStr = props.getProperty("cachesql.group." + groupName + ".slaves", "").trim();
			if (master.isEmpty()) continue;
			String[] slaves = slavesStr.isEmpty() ? new String[0] : slavesStr.split(",");
			TableGroup group = new TableGroup(master, trimAll(slaves));
			for (String table : props.getProperty(key).split(",")) {
				String t = table.trim();
				if (!t.isEmpty()) tableToGroup.put(t, group);
			}
		}
	}

	/* ========== Read API ========== */

	/**
	 * GET /cache/get — 索引等值查询
	 */
	public List<Map<String, Object>> get(String table, String column, Object value) throws Exception {
		return parseRows(tryReadUrls(buildGetUrls(table, column, value, "/cache/get")));
	}

	/**
	 * GET /cache/less — 小于查询
	 */
	public List<Map<String, Object>> getLessThen(String table, String column, Object value) throws Exception {
		return parseRows(tryReadUrls(buildGetUrls(table, column, value, "/cache/less")));
	}

	/**
	 * GET /cache/more — 大于查询
	 */
	public List<Map<String, Object>> getMoreThen(String table, String column, Object value) throws Exception {
		return parseRows(tryReadUrls(buildGetUrls(table, column, value, "/cache/more")));
	}

	/**
	 * GET /cache/range — 范围查询（闭区间）
	 */
	public List<Map<String, Object>> getRange(String table, String column, Object from, Object to) throws Exception {
		TableGroup group = findGroup(table);
		String path = "/cache/range"
			+ "?table=" + encode(table)
			+ "&column=" + encode(column)
			+ "&from=" + encode(String.valueOf(from))
			+ "&to=" + encode(String.valueOf(to));
		return parseRows(tryReadUrls(group.allReadUrls(path)));
	}

	/**
	 * GET /cache/query — SQL 查询
	 */
	public List<Map<String, Object>> query(String sql) throws Exception {
		TableGroup group = firstGroup();
		String path = "/cache/query?sql=" + encode(sql);
		return parseRows(tryReadUrls(group.allReadUrls(path)));
	}

	/* ========== Write API ========== */

	/**
	 * POST /cache/insert — 插入行（upsert 语义）
	 * 优先发 master，失败自动切换到组内其他节点
	 */
	public boolean insert(String table, String column, Object value, Map<String, Object> data) throws Exception {
		TableGroup group = findGroup(table);
		return execWrite(group.allUrls("/cache/insert"),
			buildForm(table, column, value, data));
	}

	/**
	 * POST /cache/update — 更新行
	 * 优先发 master，失败自动切换到组内其他节点
	 */
	public boolean update(String table, String column, Object value, Map<String, Object> data) throws Exception {
		TableGroup group = findGroup(table);
		return execWrite(group.allUrls("/cache/update"),
			buildForm(table, column, value, data));
	}

	/**
	 * POST /cache/delete — 删除行
	 * 优先发 master，失败自动切换到组内其他节点
	 */
	public boolean delete(String table, String column, Object value) throws Exception {
		TableGroup group = findGroup(table);
		return execWrite(group.allUrls("/cache/delete"),
			buildForm(table, column, value, null));
	}

	/* ========== Internal ========== */

	private String[] buildGetUrls(String table, String column, Object value, String path) throws Exception {
		TableGroup group = findGroup(table);
		String query = path
			+ "?table=" + encode(table)
			+ "&column=" + encode(column)
			+ "&value=" + encode(String.valueOf(value));
		return group.allReadUrls(query);
	}

	private String buildForm(String table, String column, Object value, Map<String, Object> data) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("table=").append(encode(table));
		sb.append("&column=").append(encode(column));
		sb.append("&value=").append(encode(String.valueOf(value)));
		if (data != null) {
			for (Map.Entry<String, Object> entry : data.entrySet()) {
				if (entry.getValue() != null) {
					sb.append("&").append(encode(entry.getKey()))
					  .append("=").append(encode(String.valueOf(entry.getValue())));
				}
			}
		}
		return sb.toString();
	}

	private TableGroup findGroup(String tableName) {
		TableGroup group = tableToGroup.get(tableName);
		if (group == null) throw new IllegalArgumentException("No group configured for table: " + tableName);
		return group;
	}

	private TableGroup firstGroup() {
		for (TableGroup group : tableToGroup.values()) return group;
		throw new IllegalStateException("No groups configured");
	}

	private String httpGet(String url) throws Exception {
		return tryReadUrls(new String[]{url});
	}

	private String tryReadUrls(String[] urls) throws Exception {
		String lastErr = null;
		for (String url : urls) {
			try {
				HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
				conn.setConnectTimeout(connectTimeout);
				conn.setReadTimeout(readTimeout);
				int code = conn.getResponseCode();
				InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
				String body = readStream(is);
				conn.disconnect();
				if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + body);
				return body;
			} catch (Exception e) {
				lastErr = e.getMessage();
			}
		}
		throw new RuntimeException("All nodes failed, last error: " + lastErr);
	}

	private boolean execWrite(String[] urls, String body) throws Exception {
		String lastErr = null;
		for (String url : urls) {
			try {
				return httpPost(url, body);
			} catch (Exception e) {
				lastErr = e.getMessage();
			}
		}
		throw new RuntimeException("All nodes failed for write, last error: " + lastErr);
	}

	private boolean httpPost(String url, String body) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setConnectTimeout(connectTimeout);
		conn.setReadTimeout(readTimeout);
		conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
		OutputStream os = conn.getOutputStream();
		os.write(body.getBytes("UTF-8"));
		os.close();
		int code = conn.getResponseCode();
		String respBody = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
		conn.disconnect();
		if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + respBody);
		return respBody.contains("\"code\":0");
	}

	private String readStream(InputStream is) throws IOException {
		if (is == null) return "";
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int n;
		while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
		is.close();
		return baos.toString("UTF-8");
	}

	/**
	 * 从服务器响应中解析 data 数组
	 * Parse the data array from server response JSON
	 * 输入示例: {"code":0,"message":"ok","data":[{"id":"001","name":"张三"}]}
	 */
	private List<Map<String, Object>> parseRows(String json) {
		int dataPos = json.indexOf("\"data\":");
		if (dataPos < 0) return Collections.emptyList();
		int arrStart = json.indexOf('[', dataPos);
		if (arrStart < 0) return Collections.emptyList();
		int arrEnd = json.lastIndexOf(']');
		if (arrEnd <= arrStart) return Collections.emptyList();

		String content = json.substring(arrStart + 1, arrEnd).trim();
		if (content.isEmpty()) return Collections.emptyList();

		List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
		int pos = 0;
		while (pos < content.length()) {
			int objStart = content.indexOf('{', pos);
			if (objStart < 0) break;
			int objEnd = matchBrace(content, objStart);
			if (objEnd < 0) break;
			rows.add(parseObject(content.substring(objStart + 1, objEnd)));
			pos = objEnd + 1;
		}
		return rows;
	}

	private int matchBrace(String s, int start) {
		int depth = 0;
		boolean inStr = false;
		for (int i = start; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inStr) {
				if (c == '\\') i++;
				else if (c == '"') inStr = false;
			} else {
				if (c == '"') inStr = true;
				else if (c == '{') depth++;
				else if (c == '}') { depth--; if (depth == 0) return i; }
			}
		}
		return -1;
	}

	private Map<String, Object> parseObject(String s) {
		Map<String, Object> map = new LinkedHashMap<String, Object>();
		boolean inStr = false;
		boolean valueQuoted = false;
		StringBuilder buf = new StringBuilder();
		String key = null;
		boolean readingKey = true;

		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (inStr) {
				if (c == '\\') { if (++i < s.length()) buf.append(s.charAt(i)); }
				else if (c == '"') { inStr = false; if (!readingKey) valueQuoted = true; }
				else buf.append(c);
			} else {
				if (c == '"') {
					inStr = true;
					if (!readingKey) { buf.setLength(0); valueQuoted = false; }
				} else if (c == ':') {
					if (readingKey) { key = buf.toString().trim(); buf.setLength(0); readingKey = false; }
				} else if (c == ',' || c == '}') {
					if (!readingKey) {
						String val = buf.toString().trim();
						if (key != null && !key.isEmpty()) {
							map.put(key, valueQuoted ? val : parseNumber(val));
						}
						buf.setLength(0);
						readingKey = true;
						valueQuoted = false;
					}
				} else if (!Character.isWhitespace(c) || !readingKey) {
					buf.append(c);
				}
			}
		}
		// Handle last value if no trailing comma
		if (!readingKey && key != null && !key.isEmpty()) {
			String val = buf.toString().trim();
			if (!val.isEmpty()) map.put(key, valueQuoted ? val : parseNumber(val));
		}
		return map;
	}

	/**
	 * 将未引号的 JSON 值解析为 Number
	 * Parse unquoted JSON value as Number
	 */
	private Object parseNumber(String s) {
		if (s == null || s.isEmpty()) return null;
		if (s.equals("null")) return null;
		if (s.equals("true")) return Boolean.TRUE;
		if (s.equals("false")) return Boolean.FALSE;
		try { return Long.parseLong(s); } catch (NumberFormatException e1) {}
		try { return Double.parseDouble(s); } catch (NumberFormatException e2) {}
		return s;
	}

	private String encode(String s) throws Exception {
		return URLEncoder.encode(s, "UTF-8");
	}

	private static int getInt(Properties props, String key, int def) {
		String v = props.getProperty(key);
		if (v == null) return def;
		try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
	}

	private static String[] trimAll(String[] arr) {
		for (int i = 0; i < arr.length; i++) arr[i] = arr[i].trim();
		return arr;
	}

	private static Properties loadProperties(String path) throws IOException {
		Properties props = new Properties();
		FileInputStream fis = new FileInputStream(path);
		props.load(fis);
		fis.close();
		return props;
	}

	/* 组信息：master 地址 + slave 地址列表 */
	private static class TableGroup {
		final String master;
		final String[] slaves;
		TableGroup(String master, String[] slaves) {
			this.master = master;
			this.slaves = slaves;
		}
		/** 读路径：所有节点打乱顺序，负载均衡 + 自动容灾 */
		/** Read path: all nodes shuffled for load balance + failover */
		String[] allReadUrls(String path) {
			String[] urls = new String[1 + slaves.length];
			int i = 0;
			urls[i++] = master + path;
			for (String slave : slaves) urls[i++] = slave.trim() + path;
			shuffle(urls);
			return urls;
		}
		/** 写路径：master 优先，master 失败再试 slave */
		/** Write path: master first, fallback to slaves */
		String[] allUrls(String path) {
			String[] urls = new String[1 + slaves.length];
			int i = 0;
			urls[i++] = master + path;
			for (String slave : slaves) urls[i++] = slave.trim() + path;
			return urls;
		}
		private static void shuffle(String[] arr) {
			for (int i = arr.length - 1; i > 0; i--) {
				int j = (int)(Math.random() * (i + 1));
				String tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
			}
		}
	}
}
