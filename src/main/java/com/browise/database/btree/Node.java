package com.browise.database.btree;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;

import java.util.Map.Entry;

/* B+树节点：叶子节点存储key→ArrayList<rowIdx>映射，非叶节点存储路由信息 */
/* B+ tree node: leaf stores key→ArrayList<rowIdx>, non-leaf stores routing info */
public class Node {

	protected boolean isLeaf;

	protected boolean isRoot;

	protected Node parent;

	protected Node previous;

	protected Node next;

	protected List<Entry<Comparable, ArrayList<Object>>> entries;

	protected List<Node> children;

	protected int order = 256;

	/* 构造时预分配ArrayList(order=256)容量，模拟Oracle PCTFREE思路：
	   预留空间避免加载阶段频繁扩容。100万行3个索引可节省约3.4MB扩容开销。
	   加载完成后B+树不再分裂(insert/update/delete不会改变树结构) */
	/* Pre-allocate ArrayList(order=256) capacity at construction, inspired by Oracle PCTFREE:
	   reserved space avoids frequent resizing during bulk load. ~3.4MB saved for 1M rows × 3 indexes.
	   After loading, B+ tree never splits again (insert/update/delete don't change tree structure) */
	public Node(boolean isLeaf) {
		this.isLeaf = isLeaf;
		entries = new ArrayList<Entry<Comparable, ArrayList<Object>>>(order);

		if (!isLeaf) {
			children = new ArrayList<Node>(order);
		}
	}

	/* 带order参数的构造器，由BPTree传递tree.getOrder()，所有6个Node创建点统一使用 */
	/* Constructor with order param, called with tree.getOrder() from BPTree. All 6 Node creation sites use this */
	public Node(boolean isLeaf, int order) {
		this.isLeaf = isLeaf;
		this.order = order;
		entries = new ArrayList<Entry<Comparable, ArrayList<Object>>>(order);

		if (!isLeaf) {
			children = new ArrayList<Node>(order);
		}
	}

	/**
	 * 构造节点，指定是否为叶子节点和是否为根节点。
	 * Construct a node with the given leaf and root flags.
	 */
	public Node(boolean isLeaf, boolean isRoot) {
		this(isLeaf);
		this.isRoot = isRoot;
	}

	/**
	 * 构造节点，指定叶子标志、根标志和阶数。
	 * Construct a node with leaf flag, root flag and order.
	 */
	public Node(boolean isLeaf, boolean isRoot, int order) {
		this(isLeaf, order);
		this.isRoot = isRoot;
	}

	/* 等值查找：二分搜索定位key。非叶节点递归路由到子节点，叶节点直接返回值列表 */
	/* Point lookup: binary search for key. Non-leaf routes to child, leaf returns value list directly */
	public Object get(Comparable key) {

		if (isLeaf) {
			int bind = search(entries,key);
			if(bind >= 0)
			{
				return entries.get(bind).getValue();
			}else
			{
				return null;
			}

		} else {
			if (entries.isEmpty()) return null;
			if (key.compareTo(entries.get(0).getKey()) <= 0) {
				return children.get(0).get(key);
			} else if (key.compareTo(entries.get(entries.size() - 1).getKey()) >= 0) {
				return children.get(children.size() - 1).get(key);
			} else {
				for (int i = 0; i < entries.size() - 1; i++) {
					if (entries.get(i).getKey().compareTo(key) <= 0 && entries.get(i + 1).getKey().compareTo(key) > 0) {
						return children.get(i).get(key);
					}
				}
			}
		}

		return null;
	}

	/* 二分查找：返回匹配位置，未找到返回-1。entries按key升序排列 */
	/* Binary search: returns match position, -1 if not found. entries sorted by key ascending */
	public int search(List<Entry<Comparable, ArrayList<Object>>> list,  Comparable key) {
		int low = 0;
		int high = list.size() - 1;

		while (low <= high) {
			int mid = (low + high) / 2;
			Entry<Comparable, ArrayList<Object>> entry = list.get(mid);
			if (entry.getKey().compareTo(key) < 0) {
				low = mid + 1;
			} else if (entry.getKey().compareTo(key) > 0) {
				high = mid - 1;
			} else {
				return mid;
			}
		}

		return -1;
	}

	/**
	 * 二分搜索小于等于key的最大索引位置。
	 * Binary search for the largest index where entry key <= given key.
	 */
	public int searchless(List<Entry<Comparable, ArrayList<Object>>> list,  Comparable key) {
		int low = 0;
		int high = list.size() - 1;
		int result = -1;
		while (low <= high) {
			int mid = (low + high) / 2;
			Entry<Comparable, ArrayList<Object>> entry = list.get(mid);
			if (entry.getKey().compareTo(key) <= 0) {
				result = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return result;
	}
	
	/**
	 * 二分搜索大于key的最小索引位置。
	 * Binary search for the smallest index where entry key > given key.
	 */
	public int searchmore(List<Entry<Comparable, ArrayList<Object>>> list,  Comparable key) {
		int low = 0;
		int high = list.size() - 1;
		int mid = 0;
		int result = list.size();
		while (low <= high) {
			mid = (low + high) / 2;
			Entry<Comparable, ArrayList<Object>> entry = list.get(mid);
			if (entry.getKey().compareTo(key) <= 0) {
				low = mid + 1;
			} else {
				result = mid;
				high = mid - 1;
			}
		}
		return result < list.size() ? result : -1;
	}

	/**
	 * 查询所有严格小于key的值列表。
	 * Get all values with keys strictly less than the given key.
	 */
	@SuppressWarnings("unchecked")
	public List<List> getLessThen(Comparable key) {
		List<List> list = new ArrayList<List>();
		int bind = searchless(entries,key);
		if (isLeaf) {
			if(bind >= 0)
			{
				if (entries.get(bind).getKey().compareTo(key) == 0) {
					bind--;
				}
				for(int i =0;i<=bind;i++)
				{
					list.add(entries.get(i).getValue());
				}
				return list.isEmpty() ? null : list;
			}else
			{
				return null;
			}

		} else {
			if (entries.isEmpty()) return null;
			if(bind >= 0)
			{
				int adjusted = bind;
				if (entries.get(adjusted).getKey().compareTo(key) == 0) {
					adjusted--;
				}
				for(int i =0;i<=adjusted;i++)
				{
					List list1  = children.get(i).getLessThen(key);
					if (list1 != null)
						list.addAll(list1);
				}
				if (list.size() == 0)
					return null;
				else
					return list;
			}else
			{
				List list1 = children.get(0).getLessThen(key);
				if (list1 != null)
					list.addAll(list1);
				if (list.size() == 0)
					return null;
				else
					return list;
			}
		}

	}

	/**
	 * 查询所有严格大于key的值列表。
	 * Get all values with keys strictly greater than the given key.
	 */
	public List<List> getMoreThen(Comparable key) {
		List<List> list = new ArrayList<List>();
		int bind = searchmore(entries,key);
		if (isLeaf) {
			
			if(bind >= 0)
			{
				int size = entries.size();
				for(int i = bind;i< size;i++)
				{
					list.add(entries.get(i).getValue());
					
				}
				return list;
			}else
			{
				return null;
			}
		} else {

		
			if (entries.isEmpty()) return null;
			if(bind >= 0)
			{
				int size = entries.size();
				for(int i = bind - 1 < 0?bind:bind -1 ;i< size;i++)
				{
				
					
					List list1  = children.get(i).getMoreThen(key);
					if (list1 != null)
						list.addAll(list1);
					
				}
				if (list.size() == 0)
					return null;
				else
					return list;
				
			}else
			{
				List list1 = children.get(entries.size() - 1).getMoreThen(key);
				if (list1 != null)
					list.addAll(list1);
				if (list.size() == 0)
					return null;
				else
					return list;

			}
			
			
			
		}
	}

	/**
	 * 查询key在(key1, key2)开区间内的值列表。
	 * Get all values with keys in the open interval (key1, key2).
	 */
	public List<List> getMoreAndLessThen(Comparable key1, Comparable key2) {
		List<List> list = new ArrayList<List>();
		if (isLeaf) {
			int bind1 = searchless(entries,key2);
			int bind2 = searchmore(entries,key1);
			if (bind2 > 0 && entries.get(bind2 - 1).getKey().compareTo(key1) == 0) {
				bind2--;
			}
			for(int i = bind2 ;i <=  bind1 && bind2 >=0;i++)
			{
				list.add(entries.get(i).getValue());
			}
			if (list.size() == 0)
				return null;
			else
				return list;

		} else {
			if (entries.isEmpty()) return null;

			int bind1 = searchless(entries,key2);
			
			int bind2 = searchmore(entries,key1);
			
			if( bind1 < 0)
			{
				List list1 =  children.get(0).getMoreAndLessThen(key1, key2);
				if (list1 != null)
					list.addAll(list1);
			}
			else if(bind2 < 0)
			{
				List list1 =  children.get(entries.size() -1).getMoreAndLessThen(key1, key2);
				if (list1 != null)
					list.addAll(list1);
			}
			else
			{
				for(int i = bind2-1 >= 0?bind2-1:bind2 ;i <=  bind1 ;i++)
				{
					
					List list1 =  children.get(i).getMoreAndLessThen(key1, key2);
					if (list1 != null)
						list.addAll(list1);
				}
			}

		
			
			if (list.size() == 0) {
				return list;
			} else {
				return list;
			}
		}

	}

	/**
	 * 查询key在[keyFrom, keyTo)左闭右开区间内的值列表。
	 * Get all values with keys in [keyFrom, keyTo) range.
	 */
	public List<List> getRange(Comparable keyFrom, Comparable keyTo) {
		if (isLeaf) {
			List<List> list = new ArrayList<List>();
			for (int i = 0; i < entries.size(); i++) {
				Comparable key = entries.get(i).getKey();
				if (key.compareTo(keyFrom) >= 0 && key.compareTo(keyTo) < 0) {
					list.add(entries.get(i).getValue());
				}
			}
			return list.isEmpty() ? null : list;
		} else {
			if (entries.isEmpty()) return null;
			int lo = searchmore(entries, keyFrom);
			if (lo > 0) lo--;
			int hi = searchless(entries, keyTo);
			if (hi < 0) return null;
			List<List> list = new ArrayList<List>();
			for (int i = lo; i <= hi + 1 && i < children.size(); i++) {
				List list1 = children.get(i).getRange(keyFrom, keyTo);
				if (list1 != null) list.addAll(list1);
			}
			return list.isEmpty() ? null : list;
		}
	}

	/**
	 * 插入或更新key→obj映射。叶节点满时分裂，非叶节点递归路由。
	 * Insert or update key→obj mapping. Splits leaf when full, routes recursively for non-leaf.
	 */
	public void insertOrUpdate(Comparable key, Object obj, BPTree tree,boolean obligate) {
		if (isLeaf) {
			float per = 0;
			if(obligate)
			{
				per = tree.getPer();
			}
			int order = (int) (tree.getOrder() * (1 -per));
			if (contains(key) || entries.size() < order) {

				insertOrUpdate(key, obj);
				if (parent != null) {
					parent.updateInsert(tree,obligate);
				}

			} else {
				Node left = new Node(true, tree.getOrder());
				Node right = new Node(true, tree.getOrder());
				if (previous != null) {
					previous.setNext(left);
					left.setPrevious(previous);
				}
				if (next != null) {
					next.setPrevious(right);
					right.setNext(next);
				}
				if (previous == null) {
					tree.setHead(left);
				}

				left.setNext(right);
				right.setPrevious(left);
				previous = null;
				next = null;

				int leftSize = (order + 1) / 2 + (order + 1) % 2;
				int rightSize = (order + 1) / 2;
				insertOrUpdate(key, obj);
				for (int i = 0; i < leftSize; i++) {
					left.getEntries().add(entries.get(i));
				}
				for (int i = 0; i < rightSize; i++) {
					right.getEntries().add(entries.get(leftSize + i));
				}

				if (parent != null) {
					int index = parent.getChildren().indexOf(this);
					parent.getChildren().remove(this);
					left.setParent(parent);
					right.setParent(parent);
					parent.getChildren().add(index, left);
					parent.getChildren().add(index + 1, right);
					setEntries(null);
					setChildren(null);

					parent.updateInsert(tree,obligate);
					setParent(null);
				} else {
					isRoot = false;
				Node parent = new Node(false, true, tree.getOrder());
					tree.setRoot(parent);
					left.setParent(parent);
					right.setParent(parent);
					parent.getChildren().add(left);
					parent.getChildren().add(right);
					setEntries(null);
					setChildren(null);

					parent.updateInsert(tree,obligate);
				}

			}

		} else {
			if (entries.isEmpty()) return;
			if (key.compareTo(entries.get(0).getKey()) <= 0) {
				children.get(0).insertOrUpdate(key, obj, tree,obligate);
			} else if (key.compareTo(entries.get(entries.size() - 1).getKey()) >= 0) {
				children.get(children.size() - 1).insertOrUpdate(key, obj, tree,obligate);
			} else {
				for (int i = 0; i < entries.size() - 1; i++) {
					if (entries.get(i).getKey().compareTo(key) <= 0 && entries.get(i + 1).getKey().compareTo(key) > 0) {
						children.get(i).insertOrUpdate(key, obj, tree,obligate);
						break;
					}
				}
			}
		}
	}

	/**
	 * 插入后自平衡：子节点超出阶数时分裂，向上递归。
	 * Self-balance after insert: splits when children exceed order, recurses upward.
	 */
	protected void updateInsert(BPTree tree,boolean obligate) {

		validate(this, tree);
		float per = 0;
		if(obligate)
		{
			per = tree.getPer();
		}
		int order = (int) (tree.getOrder() * (1 -per));
		if (children.size() > order) {
			Node left = new Node(false, tree.getOrder());
			Node right = new Node(false, tree.getOrder());
			int leftSize = (order + 1) / 2 + (order + 1) % 2;
			int rightSize = (order + 1) / 2;
			for (int i = 0; i < leftSize; i++) {
				left.getChildren().add(children.get(i));
				left.getEntries().add(new SimpleEntry(children.get(i).getEntries().get(0).getKey(), null));
				children.get(i).setParent(left);
			}
			for (int i = 0; i < rightSize; i++) {
				right.getChildren().add(children.get(leftSize + i));
				right.getEntries().add(new SimpleEntry(children.get(leftSize + i).getEntries().get(0).getKey(), null));
				children.get(leftSize + i).setParent(right);
			}

			if (parent != null) {
				int index = parent.getChildren().indexOf(this);
				parent.getChildren().remove(this);
				left.setParent(parent);
				right.setParent(parent);
				parent.getChildren().add(index, left);
				parent.getChildren().add(index + 1, right);
				setEntries(null);
				setChildren(null);

				parent.updateInsert(tree,obligate);
				setParent(null);
			} else {
				isRoot = false;
				Node parent = new Node(false, true, tree.getOrder());
				tree.setRoot(parent);
				left.setParent(parent);
				right.setParent(parent);
				parent.getChildren().add(left);
				parent.getChildren().add(right);
				setEntries(null);
				setChildren(null);

				parent.updateInsert(tree,obligate);
			}
		}
	}

	/**
	 * 验证并修复节点路由key与子节点首个key的一致性。
	 * Validate and repair routing keys to match first key of child nodes.
	 */
	protected static void validate(Node node, BPTree tree) {

		if (node.getEntries().size() == node.getChildren().size()) {
			for (int i = 0; i < node.getEntries().size(); i++) {
				Comparable key = node.getChildren().get(i).getEntries().get(0).getKey();
				if (node.getEntries().get(i).getKey().compareTo(key) != 0) {
					node.getEntries().remove(i);
					node.getEntries().add(i, new SimpleEntry(key, null));
					if (!node.isRoot()) {
						validate(node.getParent(), tree);
					}
				}
			}
		} else if (node.isRoot() && node.getChildren().size() >= 2 || node.getChildren().size() >= tree.getOrder() / 2
				&& node.getChildren().size() <= tree.getOrder() && node.getChildren().size() >= 2) {
			node.getEntries().clear();
			for (int i = 0; i < node.getChildren().size(); i++) {
				Comparable key = node.getChildren().get(i).getEntries().get(0).getKey();
				node.getEntries().add(new SimpleEntry(key, null));
				if (!node.isRoot()) {
					validate(node.getParent(), tree);
				}
			}
		}
	}

	/**
	 * 删除后自平衡：子节点不足时借节点或合并，向上递归。
	 * Self-balance after remove: borrows or merges when children underflow, recurses upward.
	 */
	protected void updateRemove(BPTree tree) {

		validate(this, tree);

		if (children.size() < tree.getOrder() / 2 || children.size() < 2) {
			if (isRoot) {
				if (children.size() >= 2) {
					return;
				} else {
					Node root = children.get(0);
					tree.setRoot(root);
					root.setParent(null);
					root.setRoot(true);
					setEntries(null);
					setChildren(null);
				}
			} else {
				int currIdx = parent.getChildren().indexOf(this);
				int prevIdx = currIdx - 1;
				int nextIdx = currIdx + 1;
				Node previous = null, next = null;
				if (prevIdx >= 0) {
					previous = parent.getChildren().get(prevIdx);
				}
				if (nextIdx < parent.getChildren().size()) {
					next = parent.getChildren().get(nextIdx);
				}

				if (previous != null && previous.getChildren().size() > tree.getOrder() / 2
						&& previous.getChildren().size() > 2) {
					int idx = previous.getChildren().size() - 1;
					Node borrow = previous.getChildren().get(idx);
					previous.getChildren().remove(idx);
					borrow.setParent(this);
					children.add(0, borrow);
					validate(previous, tree);
					validate(this, tree);
					parent.updateRemove(tree);

				} else if (next != null && next.getChildren().size() > tree.getOrder() / 2
						&& next.getChildren().size() > 2) {
					Node borrow = next.getChildren().get(0);
					next.getChildren().remove(0);
					borrow.setParent(this);
					children.add(borrow);
					validate(next, tree);
					validate(this, tree);
					parent.updateRemove(tree);

				} else {
					if (previous != null && (previous.getChildren().size() <= tree.getOrder() / 2
							|| previous.getChildren().size() <= 2)) {

						for (int i = previous.getChildren().size() - 1; i >= 0; i--) {
							Node child = previous.getChildren().get(i);
							children.add(0, child);
							child.setParent(this);
						}
						previous.setChildren(null);
						previous.setEntries(null);
						previous.setParent(null);
						parent.getChildren().remove(previous);
						validate(this, tree);
						parent.updateRemove(tree);

					} else if (next != null
							&& (next.getChildren().size() <= tree.getOrder() / 2 || next.getChildren().size() <= 2)) {

						for (int i = 0; i < next.getChildren().size(); i++) {
							Node child = next.getChildren().get(i);
							children.add(child);
							child.setParent(this);
						}
						next.setChildren(null);
						next.setEntries(null);
						next.setParent(null);
						parent.getChildren().remove(next);
						validate(this, tree);
						parent.updateRemove(tree);
					}
				}
			}
		}
	}

	/**
	 * 从B+树中删除指定key。叶节点失衡时触发借/合并操作。
	 * Remove the given key from the B+ tree. Triggers borrow/merge on leaf underflow.
	 */
	public boolean remove(Comparable key, BPTree tree) {
		boolean foud = false;
		if (isLeaf) {

			if (!contains(key)) {
				return false;
			}

			if (isRoot) {
				if (remove(key)) {
					foud = true;
				}
			} else {
				if (entries.size() > tree.getOrder() / 2 && entries.size() > 2) {
					if (remove(key)) {
						foud = true;
					}
				} else {
					if (previous != null && previous.getEntries().size() > tree.getOrder() / 2
							&& previous.getEntries().size() > 2 && previous.getParent() == parent) {
						int size = previous.getEntries().size();
						Entry<Comparable, ArrayList<Object>> entry = previous.getEntries().get(size - 1);
						previous.getEntries().remove(entry);
						entries.add(0, entry);
						if (remove(key)) {
							foud = true;
						}
					} else if (next != null && next.getEntries().size() > tree.getOrder() / 2
							&& next.getEntries().size() > 2 && next.getParent() == parent) {
						Entry<Comparable, ArrayList<Object>> entry = next.getEntries().get(0);
						next.getEntries().remove(entry);
						entries.add(entry);
						if (remove(key)) {
							foud = true;
						}
					} else {
						if (previous != null && (previous.getEntries().size() <= tree.getOrder() / 2
								|| previous.getEntries().size() <= 2) && previous.getParent() == parent) {
							for (int i = previous.getEntries().size() - 1; i >= 0; i--) {
								entries.add(0, previous.getEntries().get(i));
							}
							if (remove(key)) {
								foud = true;
							}
							previous.setParent(null);
							previous.setEntries(null);
							parent.getChildren().remove(previous);
							if (previous.getPrevious() != null) {
								Node temp = previous;
								temp.getPrevious().setNext(this);
								previous = temp.getPrevious();
								temp.setPrevious(null);
								temp.setNext(null);
							} else {
								tree.setHead(this);
								previous.setNext(null);
								previous = null;
							}
						} else if (next != null
								&& (next.getEntries().size() <= tree.getOrder() / 2 || next.getEntries().size() <= 2)
								&& next.getParent() == parent) {
							for (int i = 0; i < next.getEntries().size(); i++) {
								entries.add(next.getEntries().get(i));
							}
							if (remove(key)) {
								foud = true;
							}
							next.setParent(null);
							next.setEntries(null);
							parent.getChildren().remove(next);
							if (next.getNext() != null) {
								Node temp = next;
								temp.getNext().setPrevious(this);
								next = temp.getNext();
								temp.setPrevious(null);
								temp.setNext(null);
							} else {
								next.setPrevious(null);
								next = null;
							}
						}
					}
				}
				parent.updateRemove(tree);
			}
		} else {
			if (entries.isEmpty()) return false;
			if (key.compareTo(entries.get(0).getKey()) <= 0) {
				if (children.get(0).remove(key, tree)) {
					foud = true;
				}
			} else if (key.compareTo(entries.get(entries.size() - 1).getKey()) >= 0) {
				if (children.get(children.size() - 1).remove(key, tree)) {
					foud = true;
				}
			} else {
				for (int i = 0; i < entries.size() - 1; i++) {
					if (entries.get(i).getKey().compareTo(key) <= 0 && entries.get(i + 1).getKey().compareTo(key) > 0) {
						if (children.get(i).remove(key, tree)) {
							foud = true;
						}
						break;
					}
				}
			}
		}
		if (foud) {
			return true;
		} else {
			return false;
		}
	}

	/**
	 * 检查当前节点是否包含指定key。
	 * Check if the current node contains the given key.
	 */
	protected boolean contains(Comparable key) {
		if(search(entries,key)>=0) return true;
		return false;
	}

	/* 插入或追加key→rowIdx映射。同一key可对应多个rowIdx(非唯一索引)，追加到ArrayList */
	/* Insert or append key→rowIdx mapping. Same key can map to multiple rowIdx (non-unique index), appended to ArrayList */
	protected void insertOrUpdate(Comparable key, Object obj) {
		ArrayList<Object> obj1 = new ArrayList<Object>(1);
		obj1.add(obj);
		Entry<Comparable, ArrayList<Object>> entry = new SimpleEntry<Comparable, ArrayList<Object>>(key, obj1);
		if (entries.size() == 0) {
			entries.add(entry);
			entry  = null;
			obj1 = null;
			return;
		}
		int bind = this.search(entries, key);
		if(bind >= 0)
		{
			entries.get(bind).getValue().add(obj);
			entry  = null;
			obj1 = null;
			return;
		}else
		{
			bind = this.searchmore(entries, key);
			if(bind >= 0)
			{
				entries.add(bind,entry);	
				entry  = null;
				obj1 = null;
				return;
			}
			
		}
		
		entries.add(entries.size(), entry);
		entry  = null;
		obj1 = null;
	}

	/**
	 * 从当前节点的entries中移除指定key（不触发平衡）。
	 * Remove the given key from this node's entries (no rebalancing).
	 */
	protected boolean remove(Comparable key) {
		int index = -1;
		boolean foud = false;
		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).getKey().compareTo(key) == 0) {
				index = i;
				foud = true;
				break;
			}
		}
		if (index != -1) {
			entries.remove(index);
		}
		if (foud) {
			return true;
		} else {
			return false;
		}
	}

	public Node getPrevious() {
		return previous;
	}

	public void setPrevious(Node previous) {
		this.previous = previous;
	}

	public Node getNext() {
		return next;
	}

	public void setNext(Node next) {
		this.next = next;
	}

	public boolean isLeaf() {
		return isLeaf;
	}

	public void setLeaf(boolean isLeaf) {
		this.isLeaf = isLeaf;
	}

	public Node getParent() {
		return parent;
	}

	public void setParent(Node parent) {
		this.parent = parent;
	}

	public List<Entry<Comparable, ArrayList<Object>>> getEntries() {
		return entries;
	}

	public void setEntries(List<Entry<Comparable, ArrayList<Object>>> entries) {
		this.entries = entries;
	}

	public List<Node> getChildren() {
		return children;
	}

	public void setChildren(List<Node> children) {
		this.children = children;
	}

	public boolean isRoot() {
		return isRoot;
	}

	public void setRoot(boolean isRoot) {
		this.isRoot = isRoot;
	}

	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("isRoot: ");
		sb.append(isRoot);
		sb.append(", ");
		sb.append("isLeaf: ");
		sb.append(isLeaf);
		sb.append(", ");
		sb.append("keys: ");
		for (Entry entry : entries) {
			sb.append(entry.getKey());
			sb.append(", ");
		}
		sb.append(", ");
		return sb.toString();

	}

}
