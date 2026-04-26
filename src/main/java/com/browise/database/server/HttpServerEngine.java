package com.browise.database.server;

import java.util.Map;

/* HTTP服务器引擎接口：抽象JDK/Undertow等底层实现 */
/* HTTP server engine interface: abstracts JDK/Undertow implementations */
public interface HttpServerEngine {

	/**
	 * 启动引擎，绑定端口并设置线程数
	 * Start engine, bind port and set thread count
	 */
	void start(int port, int threads);

	/**
	 * 停止引擎
	 * Stop engine
	 */
	void stop();

	/**
	 * 注册路由路径及其处理器
	 * Register a route path with its handler
	 */
	void registerRoute(String path, RouteHandler handler);

	/**
	 * 路由处理器接口
	 * Route handler interface
	 */
	interface RouteHandler {
		/**
		 * 处理HTTP请求并写入响应
		 * Handle HTTP request and write response
		 */
		void handle(Request req, Response resp) throws Exception;
	}

	class Request {
		private final String method;
		private final Map<String, String> queryParams;
		private final Map<String, String> bodyParams;

		/**
		 * 构造请求对象
		 * Construct request object
		 */
		public Request(String method, Map<String, String> queryParams, Map<String, String> bodyParams) {
			this.method = method;
			this.queryParams = queryParams;
			this.bodyParams = bodyParams;
		}

		public String getMethod() { return method; }
		public Map<String, String> getQueryParams() { return queryParams; }
		public Map<String, String> getBodyParams() { return bodyParams; }
		/**
		 * 获取有效参数（POST用body，GET用query）
		 * Get effective params (POST uses body, GET uses query)
		 */
		public Map<String, String> params() {
			return "POST".equals(method) && bodyParams != null && !bodyParams.isEmpty() ? bodyParams : queryParams;
		}
	}

	class Response {
		private int code = 200;
		private String body;

		public void setCode(int code) { this.code = code; }
		/**
		 * 发送JSON响应
		 * Send JSON response
		 */
		public void sendJson(int code, String json) { this.code = code; this.body = json; }
		/**
		 * 发送错误响应
		 * Send error response
		 */
		public void sendError(int code, String message) {
			this.code = code;
			this.body = "{\"code\":" + code + ",\"message\":\"" + message + "\",\"data\":null}";
		}

		public int getCode() { return code; }
		public String getBody() { return body; }
	}
}
