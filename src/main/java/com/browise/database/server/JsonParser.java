package com.browise.database.server;

import java.util.HashMap;
import java.util.Map;

/* 简易JSON解析：有限状态机，支持嵌套值中的逗号和冒号 */
/* Minimal JSON parser: finite state machine, handles commas/colons inside nested values */
public class JsonParser {

	/**
	 * 解析简易JSON对象为Map（有限状态机）
	 * Parse simple JSON object to Map (finite state machine)
	 */
	public static Map<String, String> parseSimpleJson(String json) {
		Map<String, String> result = new HashMap<String, String>();
		if (json == null || json.isEmpty()) return result;
		json = json.trim();
		if (json.startsWith("{")) json = json.substring(1);
		if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
		if (json.isEmpty()) return result;

		int i = 0;
		int len = json.length();
		while (i < len) {
			while (i < len && json.charAt(i) == ' ') i++;
			if (i >= len) break;

			String key = readString(json, i);
			if (key == null) break;
			i += key.length() + (json.charAt(i) == '"' ? 2 : 0);
			while (i < len && json.charAt(i) != ':') i++;
			if (i >= len) break;
			i++;
			while (i < len && json.charAt(i) == ' ') i++;
			if (i >= len) break;

			String value = readString(json, i);
			if (value == null) {
				int comma = findTopLevelComma(json, i);
				if (comma < 0) break;
				value = json.substring(i, comma).trim();
				i = comma;
			} else {
				i += value.length() + (json.charAt(i) == '"' ? 2 : 0);
			}
			result.put(key, value);

			while (i < len && json.charAt(i) != ',') i++;
			if (i < len) i++;
		}
		return result;
	}

	/**
	 * 读取JSON字符串值（处理转义）
	 * Read JSON string value (handles escaping)
	 */
	private static String readString(String json, int pos) {
		if (pos >= json.length() || json.charAt(pos) != '"') return null;
		int end = pos + 1;
		while (end < json.length()) {
			char c = json.charAt(end);
			if (c == '\\') { end += 2; continue; }
			if (c == '"') return json.substring(pos + 1, end);
			end++;
		}
		return null;
	}

	/**
	 * 查找顶层逗号（跳过嵌套结构）
	 * Find top-level comma (skip nested structures)
	 */
	private static int findTopLevelComma(String json, int start) {
		int depth = 0;
		for (int i = start; i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '{' || c == '[') depth++;
			else if (c == '}' || c == ']') { if (depth > 0) depth--; }
			else if (c == ',' && depth == 0) return i;
		}
		return -1;
	}
}
