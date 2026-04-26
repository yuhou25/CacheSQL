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
import java.util.Random;

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
		for (String key : props.stringPropertyNames()) {
			if (!key.endsWith(".tables")) continue;
			String groupName = key.substring("cachesql.group.".length(), key.length() - ".tables".length());
			String master = props.getProperty("cachesql.group." + groupName + ".master", "").trim();
			String slavesStr = props.getProperty("cachesql.group." + groupName + ".slaves", "").trim();
			if (master.isEmpty()) continue;
			String[] slaves = slavesStr.isEmpty() ? new String[0] : slavesStr.split(",");
			TableGroup group = new TableGroup(master, slaves);
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
		String url = buildGetUrl(table, column, value, "/cache/get");
		return parseRows(httpGet(url));
	}

	/**
	 * GET /cache/less — 小于查询
	 */
	public List<Map<String, Object>> getLessThen(String table, String column, Object value) throws Exception {
		String url = buildGetUrl(table, column, value, "/cache/less");
		return parseRows(httpGet(url));
	}

	/**
	 * GET /cache/more — 大于查询
	 */
	public List<Map<String, Object>> getMoreThen(String table, String column, Object value) throws Exception {
		String url = buildGetUrl(table, column, value, "/cache/more");
		return parseRows(httpGet(url));
	}

	/**
	 * GET /cache/range — 范围查询（闭区间）
	 */
	public List<Map<String, Object>> getRange(String table, String column, Object from, Object to) throws Exception {
		TableGroup group = findGroup(table);
		String url = group.pickReadUrl() + "/cache/range"
			+ "?table=" + URLEncoder.encode(table, "UTF-8")
			+ "&column=" + URLEncoder.encode(column, "UTF-8")
			+ "&from=" + URLEncoder.encode(String.valueOf(from), "UTF-8")
			+ "&to=" + URLEncoder.encode(String.valueOf(to), "UTF-8");
		return parseRows(httpGet(url));
	}

	/**
	 * GET /cache/query — SQL 查询
	 */
	public List<Map<String, Object>> query(String sql) throws Exception {
		TableGroup group = firstGroup();
		String url = group.pickReadUrl() + "/cache/query?sql=" + URLEncoder.encode(sql, "UTF-8");
		return parseRows(httpGet(url));
	}

	/* ========== Write API ========== */

	/**
	 * POST /cache/insert — 插入行（upsert 语义）
	 */
	public boolean insert(String table, String column, Object value, Map<String, Object> data) throws Exception {
		TableGroup group = findGroup(table);
		return httpPost(group.getWriteUrl() + "/cache/insert",
			buildForm(table, column, value, data));
	}

	/**
	 * POST /cache/update — 更新行
	 */
	public boolean update(String table, String column, Object value, Map<String, Object> data) throws Exception {
		TableGroup group = findGroup(table);
		return httpPost(group.getWriteUrl() + "/cache/update",
			buildForm(table, column, value, data));
	}

	/**
	 * POST /cache/delete — 删除行
	 */
	public boolean delete(String table, String column, Object value) throws Exception {
		TableGroup group = findGroup(table);
		return httpPost(group.getWriteUrl() + "/cache/delete",
			buildForm(table, column, value, null));
	}

	/* ========== Internal ========== */

	private String buildGetUrl(String table, String column, Object value, String path) throws Exception {
		TableGroup group = findGroup(table);
		return group.pickReadUrl() + path
			+ "?table=" + URLEncoder.encode(table, "UTF-8")
			+ "&column=" + URLEncoder.encode(column, "UTF-8")
			+ "&value=" + URLEncoder.encode(String.valueOf(value), "UTF-8");
	}

	private String buildForm(String table, String column, Object value, Map<String, Object> data) throws Exception {
		StringBuilder sb = new StringBuilder();
		sb.append("table=").append(URLEncoder.encode(table, "UTF-8"));
		sb.append("&column=").append(URLEncoder.encode(column, "UTF-8"));
		sb.append("&value=").append(URLEncoder.encode(String.valueOf(value), "UTF-8"));
		if (data != null) {
			for (Map.Entry<String, Object> entry : data.entrySet()) {
				if (entry.getValue() != null) {
					sb.append("&").append(URLEncoder.encode(entry.getKey(), "UTF-8"))
					  .append("=").append(URLEncoder.encode(String.valueOf(entry.getValue()), "UTF-8"));
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
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(10000);
		int code = conn.getResponseCode();
		InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
		String body = readStream(is);
		conn.disconnect();
		if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + body);
		return body;
	}

	private boolean httpPost(String url, String body) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(10000);
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

	/* ========== JSON Parser ========== */

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
		String pickReadUrl() {
			if (slaves.length == 0) return master;
			return random() ? master : slaves[(int)(Math.random() * slaves.length)];
		}
		String getWriteUrl() { return master; }
		private boolean random() { return Math.random() < 0.5; }
	}
}
