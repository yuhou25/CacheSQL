package com.browise.database;

import com.browise.core.util.DBUtil;
import com.browise.database.replication.ReplicationManager;
import com.browise.database.server.HttpCacheServer;

public class Main {

	/**
	 * 入口：初始化复制模块、加载所有表、启动 HTTP 服务（all 模式）或仅嵌入模式运行
	 * Entry: init replication, load all tables, start HTTP server (all mode) or run in embedded mode
	 */
	public static void main(String[] args) throws Exception {
		String mode = DBUtil.getConfig("server.mode", "all");

		ReplicationManager.init();
		database.loadAllFromConfig();

		if ("all".equalsIgnoreCase(mode)) {
			int port = args.length > 0 ? Integer.parseInt(args[0]) : DBUtil.getConfigInt("server.port", 8080);
			int threads = DBUtil.getConfigInt("server.threads", 0);
			HttpCacheServer svr = new HttpCacheServer(port, threads);
			svr.start();
			database.startAutoRefresh();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				database.stopAutoRefresh();
				ReplicationManager.shutdown();
				svr.stop();
			}));
		} else {
			database.startAutoRefresh();
			Runtime.getRuntime().addShutdownHook(new Thread(() -> {
				database.stopAutoRefresh();
				ReplicationManager.shutdown();
			}));
			System.out.println("[CacheSQL] embedded mode started, autoRefresh=" + (DBUtil.getConfigInt("cache.refreshInterval", 0) > 0));
		}
	}
}
