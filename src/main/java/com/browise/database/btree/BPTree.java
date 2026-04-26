package com.browise.database.btree;

import java.util.ArrayList;
import java.util.List;

/* B+树：阶(order)默认256，叶子节点链表支持范围查询。key→ArrayList<rowIdx>支持非唯一索引 */
/* B+ tree: order defaults to 256, leaf linked list for range queries. key→ArrayList<rowIdx> supports non-unique indexes */
public class BPTree implements Tree {

	private float per = 0.1f;
	public float getPer() {
		return per;
	}

	public void setPer(float per) {
		this.per = per;
	}

	protected Node root;

	protected int order;
	
	protected Node head;
	
	public Node getHead() {
		return head;
	}

	public void setHead(Node head) {
		this.head = head;
	}

	public Node getRoot() {
		return root;
	}

	public void setRoot(Node root) {
		this.root = root;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public Object get(Comparable key) {
		return root.get(key);
	}

	@Override
	public boolean remove(Comparable key) {
		return root.remove(key, this);

	}

	@Override
	public void insertOrUpdate(Comparable key, Object obj,boolean obligate) {
		root.insertOrUpdate(key, obj, this,obligate);

	}
	
	/**
	 * 构造B+树，指定阶数。阶数必须大于2。
	 * Construct a B+ tree with the given order. Order must be greater than 2.
	 */
	public BPTree(int order){
		if (order < 3) {
			throw new IllegalArgumentException("order must be greater than 2");
		}
		this.order = order;
		root = new Node(true, true, order);
		head = root;
	}

	/**
	 * 清空B+树，重置为单个空叶子节点。
	 * Clear the B+ tree, resetting to a single empty leaf node.
	 */
	public void clear() {
		root = new Node(true, true);
		head = root;
	}

	@Override
	public List<List> getLessThen(Comparable key) {
		return root.getLessThen(key);
	}

	@Override
	public List<List> getMoreThen(Comparable key) {
		return root.getMoreThen(key);
	}

	@Override
	public List<List> getMoreAndLessThen(Comparable key1, Comparable key2) {
		return root.getMoreAndLessThen(key1,key2);
	}

	public List<List> getRange(Comparable keyFrom, Comparable keyTo) {
		return root != null ? root.getRange(keyFrom, keyTo) : null;
	}

}
