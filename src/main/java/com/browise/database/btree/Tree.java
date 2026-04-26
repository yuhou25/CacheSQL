package com.browise.database.btree;

import java.util.List;

public interface Tree {
	public Object get(Comparable key);
	public List<List> getLessThen(Comparable key);
	public List<List> getMoreThen(Comparable key);
	public List<List> getMoreAndLessThen(Comparable key1, Comparable key2);
	public boolean remove(Comparable key);

	public void insertOrUpdate(Comparable key, Object obj, boolean obligate);
}
