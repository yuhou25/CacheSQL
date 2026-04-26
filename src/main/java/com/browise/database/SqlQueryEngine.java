package com.browise.database;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.parser.CCJSqlParserManager;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import com.browise.database.table.Row;
import com.browise.database.table.Table;

/* SQL查询引擎：解析SQL→生成执行计划→缓存计划→通过B+树索引执行。线程安全 */
/* SQL query engine: parse SQL → generate execution plan → cache plan → execute via B+ tree indexes. Thread-safe */
public class SqlQueryEngine {

	/* 执行计划缓存：key=SQL模板(值替换为?)，value=解析后的执行计划。ConcurrentHashMap保证并发安全 */
	/* Plan cache: key=SQL template (values replaced with ?), value=parsed execution plan. ConcurrentHashMap for thread safety */
	private static final ConcurrentHashMap<String, ExecutionPlan> planCache = new ConcurrentHashMap<String, ExecutionPlan>();
	private static final int PLAN_CACHE_MAX = 1024;
	private static final Pattern VALUE_PATTERN = Pattern.compile("('[^']*'|\\b\\d+(\\.\\d+)?\\b)");

	/**
	 * 计算LIKE前缀的字典序上界（用于范围查询）
	 * Calculate the lexicographic upper bound of a LIKE prefix (for range queries)
	 */
	private static String prefixUpperBound(String prefix) {
		if (prefix.isEmpty()) return "\uFFFF";
		char last = prefix.charAt(prefix.length() - 1);
		if (last < Character.MAX_VALUE) {
			return prefix.substring(0, prefix.length() - 1) + (char)(last + 1);
		}
		return prefix + "\uFFFF";
	}

	/* 主查询入口：SQL模板命中缓存→深拷贝条件→填入实际值→选择最优索引→执行 */
	/* Main query entry: SQL template hits cache → deep copy conditions → fill actual values → pick best index → execute */
	public static QueryResult query(String sql) throws Exception {
		String template = toTemplate(sql);
		ExecutionPlan plan = planCache.get(template);

		if (plan == null) {
			plan = parse(sql, template);
			ExecutionPlan existing = planCache.putIfAbsent(template, plan);
			if (existing != null) plan = existing;
			if (planCache.size() > PLAN_CACHE_MAX) {
				planCache.clear();
			}
		}

		Table table = database.getTable(plan.tableName);
		if (table == null) {
			throw new Exception("Table not found: " + plan.tableName);
		}

		if (plan.conditions.length == 0) {
			return scanAll(table);
		}

		PlanCondition[] workingCopy = plan.copyConditions();

		List<Object> values = extractValuesFromSql(sql);
		int vi = 0;
		for (int i = 0; i < workingCopy.length; i++) {
			if (vi < values.size()) {
				workingCopy[i].value = values.get(vi++);
			}
			if (workingCopy[i].op == Op.LIKE_PREFIX) {
				String prefix = String.valueOf(workingCopy[i].value);
				if (prefix.endsWith("%")) {
					prefix = prefix.substring(0, prefix.length() - 1);
				}
				workingCopy[i].value = prefix;
				workingCopy[i].value2 = prefixUpperBound(prefix);
			}
		}

		return execute(table, workingCopy);
	}

	/**
	 * 清空执行计划缓存
	 * Clear the execution plan cache
	 */
	public static void clearPlanCache() {
		planCache.clear();
	}

	public static int planCacheSize() {
		return planCache.size();
	}

	private static String toTemplate(String sql) {
		return VALUE_PATTERN.matcher(sql).replaceAll("?");
	}

	/**
	 * 使用jsqlparser解析SQL为执行计划
	 * Parse SQL into execution plan using jsqlparser
	 */
	private static ExecutionPlan parse(String sql, String template) throws Exception {
		CCJSqlParserManager parserManager = new CCJSqlParserManager();
		Statement statement;
		try {
			statement = parserManager.parse(new StringReader(sql));
		} catch (JSQLParserException e) {
			throw new Exception("SQL parse error: " + e.getMessage());
		}

		if (!(statement instanceof Select)) {
			throw new Exception("Only SELECT supported");
		}

		Select select = (Select) statement;
		PlainSelect plain = select.getPlainSelect();
		if (plain == null) {
			throw new Exception("Only simple SELECT supported");
		}

		String tableName = plain.getFromItem().toString();
		Expression where = plain.getWhere();

		List<PlanCondition> conditions = new ArrayList<PlanCondition>();
		if (where != null) {
			flattenAnd(where, conditions);
		}

		return new ExecutionPlan(template, tableName, conditions.toArray(new PlanCondition[0]));
	}

	/**
	 * 从原始SQL中提取参数字面值（字符串/数字）
	 * Extract parameter literal values (strings/numbers) from raw SQL
	 */
	private static List<Object> extractValuesFromSql(String sql) {
		List<Object> values = new ArrayList<Object>();
		Matcher m = VALUE_PATTERN.matcher(sql);
		while (m.find()) {
			String raw = m.group();
			if (raw.startsWith("'") && raw.endsWith("'")) {
				values.add(raw.substring(1, raw.length() - 1));
			} else {
				try {
					values.add(raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw));
				} catch (NumberFormatException e) {
					values.add(raw);
				}
			}
		}
		return values;
	}

	/**
	 * 将AND表达式树展平为条件列表
	 * Flatten AND expression tree into a list of conditions
	 */
	private static void flattenAnd(Expression expr, List<PlanCondition> conditions) {
		if (expr instanceof AndExpression) {
			AndExpression and = (AndExpression) expr;
			flattenAnd(and.getLeftExpression(), conditions);
			flattenAnd(and.getRightExpression(), conditions);
		} else {
			PlanCondition c = parseCondition(expr);
			if (c != null) conditions.add(c);
		}
	}

	/**
	 * 将单个表达式节点解析为PlanCondition
	 * Parse a single expression node into a PlanCondition
	 */
	private static PlanCondition parseCondition(Expression expr) {
		if (expr instanceof EqualsTo) {
			EqualsTo eq = (EqualsTo) expr;
			return new PlanCondition(Op.EQ, columnName(eq.getLeftExpression()), valueOf(eq.getRightExpression()));
		}
		if (expr instanceof MinorThan) {
			MinorThan lt = (MinorThan) expr;
			return new PlanCondition(Op.LT, columnName(lt.getLeftExpression()), valueOf(lt.getRightExpression()));
		}
		if (expr instanceof GreaterThan) {
			GreaterThan gt = (GreaterThan) expr;
			return new PlanCondition(Op.GT, columnName(gt.getLeftExpression()), valueOf(gt.getRightExpression()));
		}
		if (expr instanceof MinorThanEquals) {
			MinorThanEquals le = (MinorThanEquals) expr;
			return new PlanCondition(Op.LE, columnName(le.getLeftExpression()), valueOf(le.getRightExpression()));
		}
		if (expr instanceof GreaterThanEquals) {
			GreaterThanEquals ge = (GreaterThanEquals) expr;
			return new PlanCondition(Op.GE, columnName(ge.getLeftExpression()), valueOf(ge.getRightExpression()));
		}
		if (expr instanceof LikeExpression) {
			LikeExpression like = (LikeExpression) expr;
			String col = columnName(like.getLeftExpression());
			Object val = valueOf(like.getRightExpression());
			String pattern = String.valueOf(val);
			if (pattern.endsWith("%") && !pattern.startsWith("%")) {
				String prefix = pattern.substring(0, pattern.length() - 1);
				String upper = prefix + "\uFFFF";
				return new PlanCondition(Op.LIKE_PREFIX, col, prefix, upper);
			}
		}
		return null;
	}

	/**
	 * 从表达式提取列名
	 * Extract column name from expression
	 */
	private static String columnName(Expression expr) {
		if (expr instanceof Column) {
			return ((Column) expr).getColumnName();
		}
		return expr.toString();
	}

	/**
	 * 从表达式提取字面值并转为Java类型
	 * Extract literal value from expression and convert to Java type
	 */
	private static Object valueOf(Expression expr) {
		String s = expr.toString();
		if (s.startsWith("'") && s.endsWith("'")) {
			return s.substring(1, s.length() - 1);
		}
		try {
			if (s.contains(".")) return Double.parseDouble(s);
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			return s;
		}
	}

	/**
	 * 全表扫描，返回未删除的所有行
	 * Full table scan, returns all non-deleted rows
	 */
	private static QueryResult scanAll(Table table) throws Exception {
		int total = table.getData().size();
		List<Row> rows = new ArrayList<Row>();
		for (int i = 0; i < total; i++) {
			Row row;
			try { row = table.getData().get(i); } catch (Exception e) { continue; }
			if (!row.isDelete()) {
				row.touch();
				rows.add(row);
			}
		}
		return new QueryResult(rows, "full_scan");
	}

	/**
	 * 根据执行计划通过B+树索引执行查询，返回结果行
	 * Execute query via B+ tree index according to execution plan, return result rows
	 */
	private static QueryResult execute(Table table, PlanCondition[] conditions) throws Exception {
		PlanCondition indexed = findBestIndex(table, conditions);
		if (indexed == null) {
			return new QueryResult(null, "no_index");
		}

		PlanCondition rangePartner = findRangePartner(conditions, indexed);
		Object result = null;
		if (rangePartner != null) {
			PlanCondition lo = (indexed.op == Op.GE || indexed.op == Op.GT) ? indexed : rangePartner;
			PlanCondition hi = (indexed.op == Op.LE || indexed.op == Op.LT) ? indexed : rangePartner;
			result = table.getMoreAndLessThen(indexed.column, lo.value, hi.value);
			if (result == null) {
				return new QueryResult(null, "index:" + indexed.column + "_range");
			}
			@SuppressWarnings("unchecked")
			List<Row> rows = (List<Row>) result;
			return new QueryResult(rows, "index:" + indexed.column + "_range");
		}

		switch (indexed.op) {
		case EQ:
			result = table.get(indexed.column, indexed.value);
			break;
		case LT:
			result = table.getLessThen(indexed.column, indexed.value);
			break;
		case GT:
			result = table.getMoreThen(indexed.column, indexed.value);
			break;
		case LE:
			result = table.getLessThenEquals(indexed.column, indexed.value);
			break;
		case GE:
			result = table.getMoreThenEquals(indexed.column, indexed.value);
			break;
		case LIKE_PREFIX:
			result = table.getRange(indexed.column, indexed.value, indexed.value2);
			break;
		default:
			return new QueryResult(null, "unsupported_op");
		}

		if (result == null) {
			return new QueryResult(null, indexed.method);
		}

		@SuppressWarnings("unchecked")
		List<Row> rows = (List<Row>) result;

		if (conditions.length > 1) {
			rows = filterRemaining(rows, conditions, indexed);
		}

		return new QueryResult(rows, indexed.method);
	}

	/* 索引优先级：等值查询(EQ)优先级10，范围查询优先级5。优先用等值索引 */
	/* Index priority: equality (EQ) = 10, range = 5. Prefer equality index */
	private static PlanCondition findBestIndex(Table table, PlanCondition[] conditions) {
		PlanCondition best = null;
		int bestPriority = -1;
		for (PlanCondition c : conditions) {
			if (table.getIndex().containsKey(c.column)) {
				int pri = c.op == Op.EQ ? 10 : 5;
				if (pri > bestPriority) {
					bestPriority = pri;
					best = c;
					best.method = "index:" + c.column;
				}
			}
		}
		return best;
	}

	/* 检测同一列上的范围组合(如 GE+LE)，合并为单次getMoreAndLessThen调用，避免两次索引查找 */
	/* Detect range pair on same column (e.g. GE+LE), merge into single getMoreAndLessThen call to avoid two index lookups */
	private static PlanCondition findRangePartner(PlanCondition[] conditions, PlanCondition indexed) {
		if (indexed.op != Op.GE && indexed.op != Op.GT && indexed.op != Op.LE && indexed.op != Op.LT) {
			return null;
		}
		for (PlanCondition c : conditions) {
			if (c == indexed) continue;
			if (c.column.equals(indexed.column)) {
				if ((indexed.op == Op.GE || indexed.op == Op.GT) && (c.op == Op.LE || c.op == Op.LT)) return c;
				if ((indexed.op == Op.LE || indexed.op == Op.LT) && (c.op == Op.GE || c.op == Op.GT)) return c;
			}
		}
		return null;
	}

	/**
	 * 对索引扫描结果进行剩余条件的内存过滤
	 * Filter index scan results by remaining conditions in memory
	 */
	private static List<Row> filterRemaining(List<Row> rows, PlanCondition[] all, PlanCondition indexed) {
		List<Row> filtered = new ArrayList<Row>();
		for (Row row : rows) {
			boolean match = true;
			for (PlanCondition c : all) {
				if (c == indexed) continue;
				Object val = row.get(c.column);
				if (!evaluateCondition(val, c)) {
					match = false;
					break;
				}
			}
			if (match) filtered.add(row);
		}
		return filtered;
	}

	/**
	 * 评估单行数据是否满足单个PlanCondition
	 * Evaluate whether a single row satisfies one PlanCondition
	 */
	private static boolean evaluateCondition(Object rowVal, PlanCondition c) {
		if (rowVal == null) return false;
		String s = String.valueOf(rowVal);
		String cv = String.valueOf(c.value);
		switch (c.op) {
		case EQ:
			return s.equals(cv) || compareNumber(rowVal, c.value) == 0;
		case LT:
			return compareNumber(rowVal, c.value) < 0;
		case GT:
			return compareNumber(rowVal, c.value) > 0;
		case LE:
			return compareNumber(rowVal, c.value) <= 0;
		case GE:
			return compareNumber(rowVal, c.value) >= 0;
		default:
			return false;
		}
	}

	/**
	 * 比较两个数值（字符串转double后比较，失败则按字符串比较）
	 * Compare two numeric values (parse to double, fallback to string comparison)
	 */
	private static int compareNumber(Object a, Object b) {
		try {
			double da = Double.parseDouble(String.valueOf(a));
			double db = Double.parseDouble(String.valueOf(b));
			return Double.compare(da, db);
		} catch (NumberFormatException e) {
			return String.valueOf(a).compareTo(String.valueOf(b));
		}
	}

	public static class QueryResult {
		public final List<Row> rows;
		public final String method;

		/**
		 * 查询结果：行列表 + 执行方法描述
		 * Query result: row list + execution method description
		 */
		public QueryResult(List<Row> rows, String method) {
			this.rows = rows;
			this.method = method;
		}
	}

	private static class ExecutionPlan {
		final String template;
		final String tableName;
		final PlanCondition[] conditions;

		/**
		 * 执行计划：SQL模板 + 表名 + 条件数组
		 * Execution plan: SQL template + table name + condition array
		 */
		ExecutionPlan(String template, String tableName, PlanCondition[] conditions) {
			this.template = template;
			this.tableName = tableName;
			this.conditions = conditions;
		}

	/* 每次query深拷贝PlanCondition，防止多线程并发修改value导致数据串读(C-1修复) */
	/* Deep copy PlanCondition on each query to prevent concurrent value modification causing data cross-read (C-1 fix) */
	PlanCondition[] copyConditions() {
			PlanCondition[] copy = new PlanCondition[conditions.length];
			for (int i = 0; i < conditions.length; i++) {
				copy[i] = new PlanCondition(conditions[i].op, conditions[i].column, conditions[i].value);
				copy[i].value2 = conditions[i].value2;
			}
			return copy;
		}
	}

	private static class PlanCondition {
		Op op;
		String column;
		Object value;
		Object value2;
		String method;

		/**
		 * 创建单值条件（EQ/LT/GT/LE/GE）
		 * Create a single-value condition (EQ/LT/GT/LE/GE)
		 */
		PlanCondition(Op op, String column, Object value) {
			this.op = op;
			this.column = column;
			this.value = value;
		}

		/**
		 * 创建区间条件（LIKE_PREFIX/范围查询）
		 * Create a range condition (LIKE_PREFIX/range queries)
		 */
		PlanCondition(Op op, String column, Object value, Object value2) {
			this.op = op;
			this.column = column;
			this.value = value;
			this.value2 = value2;
		}
	}

	private enum Op {
		EQ, LT, GT, LE, GE, BETWEEN, LIKE_PREFIX
	}
}
