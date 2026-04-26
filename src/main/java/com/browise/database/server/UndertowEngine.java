package com.browise.database.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Deque;

import com.browise.core.util.DBUtil;
import io.undertow.UndertowOptions;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.BlockingHandler;
import io.undertow.server.handlers.PathHandler;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

/* Undertow NIO引擎：基于XNIO的高性能非阻塞HTTP服务器 */
/* Undertow NIO engine: high-performance non-blocking HTTP server based on XNIO */
public class UndertowEngine implements HttpServerEngine {

	private Undertow server;
	private PathHandler pathHandler;

	/**
	 * 启动Undertow NIO引擎，配置IO/Worker线程和缓冲区
	 * Start Undertow NIO engine with IO/worker threads and buffer config
	 */
	public void start(int port, int threads) {
		int cpus = Runtime.getRuntime().availableProcessors();
		int ioThreads = DBUtil.getConfigInt("server.http.ioThreads", 0);
		if (ioThreads <= 0) ioThreads = Math.max(2, cpus);
		int workerThreads = threads > 0 ? threads : DBUtil.getConfigInt("server.http.workerThreads", 0);
		if (workerThreads <= 0) workerThreads = ioThreads * 8;
		int bufferSize = DBUtil.getConfigInt("server.http.bufferSize", 16384);
		int maxEntitySize = DBUtil.getConfigInt("server.http.maxEntitySize", 1048576);
		int idleTimeout = DBUtil.getConfigInt("server.http.idleTimeout", 30000);

		if (pathHandler == null) {
			pathHandler = new PathHandler();
		}

		Undertow.Builder builder = Undertow.builder()
			.addHttpListener(port, "0.0.0.0")
			.setIoThreads(ioThreads)
			.setWorkerThreads(workerThreads)
			.setBufferSize(bufferSize)
			.setServerOption(UndertowOptions.ALWAYS_SET_KEEP_ALIVE, true)
			.setServerOption(UndertowOptions.IDLE_TIMEOUT, idleTimeout);

		if (maxEntitySize > 0) {
			builder.setServerOption(UndertowOptions.MAX_ENTITY_SIZE, (long) maxEntitySize);
		}

		builder.setHandler(pathHandler);
		server = builder.build();
		server.start();
		System.out.println("[http] NIO engine started: port=" + port + " ioThreads=" + ioThreads + " workerThreads=" + workerThreads + " bufferSize=" + bufferSize + " maxEntitySize=" + maxEntitySize);
	}

	/**
	 * 停止Undertow引擎
	 * Stop Undertow engine
	 */
	public void stop() {
		if (server != null) server.stop();
		System.out.println("[http] NIO engine stopped");
	}

	/**
	 * 注册路由到PathHandler
	 * Register route to PathHandler
	 */
	public void registerRoute(String path, RouteHandler handler) {
		if (pathHandler == null) {
			pathHandler = new PathHandler();
		}
		HttpHandler httpHandler = new BlockingHandler((HttpServerExchange exchange) -> {
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
		pathHandler.addExactPath(path, httpHandler);
	}

	/**
	 * 从HttpServerExchange构建内部Request对象
	 * Build internal Request from HttpServerExchange
	 */
	private Request buildRequest(HttpServerExchange exchange) throws IOException {
		String method = exchange.getRequestMethod().toString();
		Map<String, String> queryParams = parseQueryParams(exchange);
		Map<String, String> bodyParams = new HashMap<String, String>();
		if (Methods.POST.equals(exchange.getRequestMethod())
			|| Methods.PUT.equals(exchange.getRequestMethod())) {
			bodyParams = parseBody(exchange);
		}
		return new Request(method, queryParams, bodyParams);
	}

	/**
	 * 解析Undertow查询参数
	 * Parse Undertow query parameters
	 */
	private Map<String, String> parseQueryParams(HttpServerExchange exchange) {
		Map<String, String> params = new HashMap<String, String>();
		Map<String, Deque<String>> qParams = exchange.getQueryParameters();
		for (Map.Entry<String, Deque<String>> entry : qParams.entrySet()) {
			if (!entry.getValue().isEmpty()) {
				params.put(entry.getKey(), entry.getValue().getFirst());
			}
		}
		return params;
	}

	/**
	 * 解析请求体（支持JSON和URL编码表单）
	 * Parse request body (supports JSON and URL-encoded form)
	 */
	private Map<String, String> parseBody(HttpServerExchange exchange) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] buf = new byte[4096];
		int len;
		java.io.InputStream is = exchange.getInputStream();
		while ((len = is.read(buf)) != -1) {
			baos.write(buf, 0, len);
		}
		if (baos.size() > 65536) {
			return new HashMap<String, String>();
		}
		String body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
		Map<String, String> params = new HashMap<String, String>();
		String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
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

	/**
	 * 将Response写入HttpServerExchange
	 * Write Response to HttpServerExchange
	 */
	private void writeResponse(HttpServerExchange exchange, Response resp) throws IOException {
		String body = resp.getBody();
		if (body == null) body = "";
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		HeaderMap headers = exchange.getResponseHeaders();
		headers.put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
		headers.put(new io.undertow.util.HttpString("Access-Control-Allow-Origin"), "*");
		exchange.setStatusCode(resp.getCode());
		exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
	}
}
