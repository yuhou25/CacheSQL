package com.browise.database.index;

public class CompositeKey implements Comparable {
	private final Object[] values;

	/**
	 * 构造复合键，由多个字段值组成。
	 * Construct a composite key from an array of field values.
	 */
	public CompositeKey(Object[] values) {
		this.values = values;
	}

	public Object[] getValues() {
		return values;
	}

	/**
	 * 按字段依次比较，支持null和混合类型。
	 * Compare field by field, supporting nulls and mixed types.
	 */
	@Override
	public int compareTo(Object o) {
		CompositeKey other = (CompositeKey) o;
		int len = Math.min(this.values.length, other.values.length);
		for (int i = 0; i < len; i++) {
			int cmp = compareValues(this.values[i], other.values[i]);
			if (cmp != 0) return cmp;
		}
		return this.values.length - other.values.length;
	}

	/**
	 * 比较两个字段值，处理null、数字和Comparable。
	 * Compare two field values, handling nulls, numbers and Comparables.
	 */
	@SuppressWarnings("unchecked")
	private static int compareValues(Object a, Object b) {
		if (a == null && b == null) return 0;
		if (a == null) return 1;
		if (b == null) return -1;

		if (a instanceof Number && b instanceof Number) {
			return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
		}

		if (a instanceof Comparable && a.getClass().isInstance(b)) {
			return ((Comparable<Object>) a).compareTo(b);
		}

		if (b instanceof Comparable && b.getClass().isInstance(a)) {
			return -((Comparable<Object>) b).compareTo(a);
		}

		return String.valueOf(a).compareTo(String.valueOf(b));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof CompositeKey)) return false;
		CompositeKey other = (CompositeKey) o;
		if (this.values.length != other.values.length) return false;
		for (int i = 0; i < values.length; i++) {
			Object a = this.values[i];
			Object b = other.values[i];
			if (a == null && b == null) continue;
			if (a == null || b == null) return false;
			if (!a.equals(b)) return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int h = 1;
		for (Object v : values) {
			h = 31 * h + (v == null ? 0 : v.hashCode());
		}
		return h;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("(");
		for (int i = 0; i < values.length; i++) {
			if (i > 0) sb.append(",");
			sb.append(values[i]);
		}
		sb.append(")");
		return sb.toString();
	}

	/**
	 * 便捷工厂方法创建复合键。
	 * Convenience factory method to create a composite key.
	 */
	public static CompositeKey of(Object... values) {
		return new CompositeKey(values);
	}
}
