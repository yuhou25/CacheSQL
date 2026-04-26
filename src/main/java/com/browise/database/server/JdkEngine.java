package com.browise.database.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.browise.core.util.DBUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/* JDK HttpServer引擎：零外部依赖，BIO模型 */
/* JDK HttpServer engine: zero external dependency, BIO model */
public class JdkEngine implements HttpServerEngine {

	private HttpServer server;
	private List<RouteEntry> routes = new ArrayList<RouteEntry>();

	private static class RouteEntry {
		String path;
		RouteHandler handler;
		RouteEntry(String path, RouteHandler handler) { this.path = path; this.handler = handler; }
	}

	/**
	 * 启动JDK HttpServer，绑定端口和线程池
	 * Start JDK HttpServer, bind port and thread pool
	 */
	public void start(int port, int threads) {
		try {
			int backlog = DBUtil.getConfigInt("server.http.backlog", 0);
			server = HttpServer.create(new InetSocketAddress(port), backlog);
			int n = threads > 0 ? threads : DBUtil.getConfigInt("server.http.threads", Runtime.getRuntime().availableProcessors() * 2);
			server.setExecutor(Executors.newFixedThreadPool(n));
			for (RouteEntry entry : routes) {
				registerContext(entry.path, entry.handler);
			}
			routes.clear();
			server.start();
			System.out.println("[http] BIO engine started: port=" + port + " threads=" + n + " backlog=" + backlog);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 停止JDK HttpServer
	 * Stop JDK HttpServer
	 */
	public void stop() {
		if (server != null) server.stop(0);
		System.out.println("[http] BIO engine stopped");
	}

	/**
	 * 注册路由（引擎未启动时暂存，启动后直接绑定）
	 * Register route (defer if engine not started, bind directly if running)
	 */
	public void registerRoute(String path, RouteHandler handler) {
		if (server != null) {
			registerContext(path, handler);
		} else {
			routes.add(new RouteEntry(path, handler));
		}
	}

	/**
	 * 创建HttpContext并绑定处理器
	 * Create HttpContext and bind handler
	 */
	private void registerContext(String path, RouteHandler handler) {
		server.createContext(path, (HttpExchange exchange) -> {
			Request req = buildRequest(exchange);
			Response resp = new Response();
			try {
				handler.handle(req, resp);
			} catch (Exception e) {
				System.err.println("[http] handler error: " + e.getMessage());
				resp.sendError(500, "Internal Server Error");
			}
			writeResponse(exchange, resp);
		});
	}

	/**
	 * 从HttpExchange构建内部Request对象
	 * Build internal Request from HttpExchange
	 */
	private Request buildRequest(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getQuery());
		Map<String, String> bodyParams = parseBody(exchange);
		return new Request(method, queryParams, bodyParams);
	}

	/**
	 * 将Response写入HttpExchange输出流
	 * Write Response to HttpExchange output stream
	 */
	private void writeResponse(HttpExchange exchange, Response resp) throws IOException {
		String body = resp.getBody();
		if (body == null) body = "";
		byte[] bytes = body.getBytes("UTF-8");
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
		exchange.sendResponseHeaders(resp.getCode(), bytes.length);
		OutputStream os = exchange.getResponseBody();
		os.write(bytes);
		os.close();
	}

	/**
	 * 解析URL查询字符串为参数Map
	 * Parse URL query string into params map
	 */
	private Map<String, String> parseQuery(String query) {
		Map<String, String> params = new HashMap<String, String>();
		if (query != null) {
			for (String param : query.split("&")) {
				String[] pair = param.split("=", 2);
				if (pair.length == 2) {
					try {
						params.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
					} catch (Exception e) {
						params.put(pair[0], pair[1]);
					}
				}
			}
		}
		return params;
	}

	/**
	 * 解析请求体（支持JSON和URL编码表单）
	 * Parse request body (supports JSON and URL-encoded form)
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
		String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
		if (contentType != null && contentType.contains("application/json")) {
			return JsonParser.parseSimpleJson(body);
		} else {
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
		}
		return params;
	}
}
