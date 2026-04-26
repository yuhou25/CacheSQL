package com.browise.database.replication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/* 操作日志：固定容量环形缓冲区，记录Master最近的insert/update/delete操作 */
/* Op log: fixed-capacity ring buffer recording Master's recent insert/update/delete operations */
/* 用于增量同步：Slave重连时上报lastSeq，Master从OpLog回放缺失的操作（毫秒级完成） */
/* Used for incremental sync: slave reports lastSeq on reconnect, master replays missed ops (milliseconds) */
public class OpLog {

	private final OpEntry[] buffer;
	private final int capacity;
	private long headSeq = 0;
	private long nextSeq = 1;

	/**
	 * 创建固定容量的操作日志环形缓冲区
	 * Create a fixed-capacity operation log ring buffer
	 * @param capacity 缓冲区容量 / buffer capacity
	 */
	public OpLog(int capacity) {
		this.capacity = capacity;
		this.buffer = new OpEntry[capacity];
	}

	/* 追加操作到环形缓冲区，返回分配的序列号。超过容量时覆盖最旧条目 */
	/* Append operation to ring buffer, returns assigned sequence number. Overwrites oldest when full */
	public synchronized long append(String op, String table, String indexColumn, Object keyValue,
			HashMap<String, Object> data) {
		int pos = (int) ((nextSeq - 1) % capacity);
		buffer[pos] = new OpEntry(nextSeq, op, table, indexColumn,
				keyValue != null ? String.valueOf(keyValue) : null,
				data != null ? new HashMap<String, Object>(data) : null);
		headSeq = Math.max(1, nextSeq - capacity + 1);
		return nextSeq++;
	}

	/* 获取sinceSeq之后的所有操作。如果OpLog不足以覆盖，从headSeq开始返回 */
	/* Get all ops after sinceSeq. If OpLog can't fully cover, starts from headSeq */
	public synchronized List<OpEntry> getSince(long sinceSeq) {
		List<OpEntry> result = new ArrayList<OpEntry>();
		long start = Math.max(sinceSeq + 1, headSeq);
		for (long seq = start; seq < nextSeq; seq++) {
			int pos = (int) ((seq - 1) % capacity);
			if (buffer[pos] != null && buffer[pos].seq == seq) {
				result.add(buffer[pos]);
			}
		}
		return result;
	}

	public synchronized long getLatestSeq() {
		return nextSeq - 1;
	}

	public synchronized long getHeadSeq() {
		return headSeq;
	}

	public synchronized int size() {
		return (int) (nextSeq - headSeq);
	}

	public static class OpEntry {
		public final long seq;
		public final String op;
		public final String table;
		public final String indexColumn;
		public final String keyValue;
		public final HashMap<String, Object> data;

		public OpEntry(long seq, String op, String table, String indexColumn, String keyValue,
				HashMap<String, Object> data) {
			this.seq = seq;
			this.op = op;
			this.table = table;
			this.indexColumn = indexColumn;
			this.keyValue = keyValue;
			this.data = data;
		}
	}
}
