/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.google.gson.internal;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * A map of comparable keys to values. Unlike {@link LinkedHashMap}, this class uses a tree to
 * store entries; the keys must be comparable or an explicit comparator must be provided.
 */
public final class LinkedHashTreeMap<K, V> extends AbstractMap<K, V> implements Serializable {

  private static final Comparator<Object> NATURAL_ORDER =
      new Comparator<Object>() {
        @Override
        public int compare(Object a, Object b) {
          return ((Comparable) a).compareTo(b);
        }
      };

  Comparator<? super K> comparator;
  @Nullable Node<K, V>[] table;
  final Node<K, V> header;
  int size;
  int modCount;
  int threshold;

  public LinkedHashTreeMap() {
    this((Comparator<? super K>) NATURAL_ORDER);
  }

  @SuppressWarnings("unchecked")
  public LinkedHashTreeMap(Comparator<? super K> comparator) {
    this.comparator = comparator != null ? comparator : (Comparator<? super K>) NATURAL_ORDER;
    this.header = new Node<K, V>();
    this.table = (Node<K, V>[]) new Node[16];
    this.threshold = (table.length / 2) + (table.length / 4); // 3/4 capacity
  }

  @Override
  public int size() {
    return size;
  }

  @Override
  @Nullable
  public V get(@Nullable Object key) {
    Node<K, V> node = findByObject(key);
    return node != null ? node.value : null;
  }

  @Override
  @Nullable
  public V put(K key, V value) {
    if (key == null) {
      throw new NullPointerException("key == null");
    }
    Node<K, V> created = find(key, true);
    V result = created.value;
    created.value = value;
    return result;
  }

  @Override
  @Nullable
  public V remove(@Nullable Object key) {
    Node<K, V> node = removeInternalByKey(key);
    return node != null ? node.value : null;
  }

  @Override
  public void clear() {
    table = (Node<K, V>[]) new Node[16];
    size = 0;
    modCount++;
    // Clear the linked list.
    Node<K, V> header = this.header;
    Node<K, V> e = header.next;
    while (e != header) {
      Node<K, V> next = e.next;
      e.next = e.prev = null;
      e = next;
    }
    header.next = header.prev = header;
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    EntrySet result = entrySet;
    return result != null ? result : (entrySet = new EntrySet());
  }

  @Override
  public Set<K> keySet() {
    KeySet result = keySet;
    return result != null ? result : (keySet = new KeySet());
  }

  Node<K, V> find(K key, boolean create) {
    Comparator<? super K> comparator = this.comparator;
    Node<K, V>[] table = this.table;
    int hash = secondaryHash(key.hashCode());
    int index = hash & (table.length - 1);
    Node<K, V> nearest = table[index];
    int comparison = 0;

    if (nearest != null) {
      @SuppressWarnings("unchecked")
      Comparable<Object> comparableKey =
          (comparator == NATURAL_ORDER) ? (Comparable<Object>) key : null;

      while (true) {
        comparison =
            (comparableKey != null)
                ? comparableKey.compareTo(nearest.key)
                : comparator.compare(key, nearest.key);

        if (comparison == 0) {
          return nearest;
        }

        Node<K, V> child = (comparison < 0) ? nearest.left : nearest.right;
        if (child == null) {
          break;
        }
        nearest = child;
      }
    }

    if (!create) {
      return null;
    }

    Node<K, V> header = this.header;
    Node<K, V> created;
    if (nearest == null) {
      // Create the root node.
      created = table[index] = new Node<K, V>(null, key, hash, header, header.prev);
    } else {
      created =
          new Node<K, V>(
              nearest, key, hash, header, header.prev); // also links it into the header list
      if (comparison < 0) {
        nearest.left = created;
      } else {
        nearest.right = created;
      }
      rebalance(nearest, true);
    }
    size++;
    if (size > threshold) {
      doubleCapacity();
    }
    modCount++;
    return created;
  }

  @Nullable
  Node<K, V> findByObject(@Nullable Object key) {
    if (key == null) {
      return null;
    }
    try {
      @SuppressWarnings("unchecked")
      K k = (K) key;
      return find(k, false);
    } catch (ClassCastException e) {
      return null;
    }
  }

  @Nullable
  Node<K, V> findByEntry(Entry<?, ?> entry) {
    Node<K, V> mine = findByObject(entry.getKey());
    boolean valuesEqual =
        mine != null && equal(mine.value, entry.getValue());
    return valuesEqual ? mine : null;
  }

  private void removeInternal(Node<K, V> node, boolean unlink) {
    if (unlink) {
      // unlink from linked list
      node.prev.next = node.next;
      node.next.prev = node.prev;
      node.prev = node.next = null;
    }

    Node<K, V> left = node.left;
    Node<K, V> right = node.right;
    Node<K, V> originalParent = node.parent;

    if (left != null && right != null) {
      Node<K, V> adjacent = (left.height > right.height) ? left.last() : right.first();
      removeInternal(adjacent, false); // removes adjacent from the tree, but not the list
      int leftHeight = 0;
      left = node.left;
      if (left != null) {
        leftHeight = left.height;
        adjacent.left = left;
        left.parent = adjacent;
        node.left = null;
      }
      int rightHeight = 0;
      right = node.right;
      if (right != null) {
        rightHeight = right.height;
        adjacent.right = right;
        right.parent = adjacent;
        node.right = null;
      }
      adjacent.height = Math.max(leftHeight, rightHeight) + 1;
      replaceInParent(node, adjacent);
    } else if (left != null) {
      replaceInParent(node, left);
      node.left = null;
    } else if (right != null) {
      replaceInParent(node, right);
      node.right = null;
    } else {
      replaceInParent(node, null);
    }

    rebalance(originalParent, false);
    size--;
    modCount++;
  }

  @Nullable
  Node<K, V> removeInternalByKey(@Nullable Object key) {
    Node<K, V> node = findByObject(key);
    if (node != null) {
      removeInternal(node, true);
    }
    return node;
  }

  private void replaceInParent(Node<K, V> node, @Nullable Node<K, V> replacement) {
    Node<K, V> parent = node.parent;
    node.parent = null;
    if (replacement != null) {
      replacement.parent = parent;
    }

    if (parent != null) {
      if (parent.left == node) {
        parent.left = replacement;
      } else {
        assert (parent.right == node);
        parent.right = replacement;
      }
    } else {
      int index = node.hash & (table.length - 1);
      table[index] = replacement;
    }
  }

  private void rebalance(@Nullable Node<K, V> unbalanced, boolean insert) {
    while (unbalanced != null) {
      Node<K, V> left = unbalanced.left;
      Node<K, V> right = unbalanced.right;
      int leftHeight = (left != null) ? left.height : 0;
      int rightHeight = (right != null) ? right.height : 0;
      int delta = leftHeight - rightHeight;

      if (delta == -2) {
        Node<K, V> rightLeft = right != null ? right.left : null;
        Node<K, V> rightRight = right != null ? right.right : null;
        int rightLeftHeight = (rightLeft != null) ? rightLeft.height : 0;
        int rightRightHeight = (rightRight != null) ? rightRight.height : 0;

        if (rightLeftHeight > rightRightHeight) {
          rotateRight(right);
          rotateLeft(unbalanced);
        } else {
          rotateLeft(unbalanced);
        }
        if (insert) {
          break;
        }

      } else if (delta == 2) {
        Node<K, V> leftLeft = left != null ? left.left : null;
        Node<K, V> leftRight = left != null ? left.right : null;
        int leftLeftHeight = (leftLeft != null) ? leftLeft.height : 0;
        int leftRightHeight = (leftRight != null) ? leftRight.height : 0;

        if (leftRightHeight > leftLeftHeight) {
          rotateLeft(left);
          rotateRight(unbalanced);
        } else {
          rotateRight(unbalanced);
        }
        if (insert) {
          break;
        }

      } else if (delta == 0) {
        unbalanced.height = leftHeight + 1;
        if (insert) {
          break;
        }

      } else {
        assert (delta == -1 || delta == 1);
        unbalanced.height = Math.max(leftHeight, rightHeight) + 1;
        if (!insert) {
          break;
        }
      }

      unbalanced = unbalanced.parent;
    }
  }

  /** Rotates the subtree so that its root's right child is the new root. */
  private void rotateLeft(Node<K, V> root) {
    Node<K, V> left = root.left;
    Node<K, V> pivot = root.right;
    Node<K, V> pivotLeft = pivot.left;
    Node<K, V> pivotRight = pivot.right;

    // move the pivot's left child to the root's right
    root.right = pivotLeft;
    if (pivotLeft != null) {
      pivotLeft.parent = root;
    }

    replaceInParent(root, pivot);

    // move the root to the pivot's left
    pivot.left = root;
    root.parent = pivot;

    // fix heights
    root.height =
        Math.max(left != null ? left.height : 0, pivotLeft != null ? pivotLeft.height : 0) + 1;
    pivot.height = Math.max(root.height, pivotRight != null ? pivotRight.height : 0) + 1;
  }

  /** Rotates the subtree so that its root's left child is the new root. */
  private void rotateRight(Node<K, V> root) {
    Node<K, V> pivot = root.left;
    if (pivot == null) {
      return;
    }
    Node<K, V> right = root.right;
    Node<K, V> pivotLeft = pivot.left;
    Node<K, V> pivotRight = pivot.right;

    // move the pivot's right child to the root's left
    root.left = pivotRight;
    if (pivotRight != null) {
      pivotRight.parent = root;
    }

    replaceInParent(root, pivot);

    // move the root to the pivot's right
    pivot.right = root;
    root.parent = pivot;

    // fixup heights
    root.height =
        Math.max(right != null ? right.height : 0, pivotRight != null ? pivotRight.height : 0) + 1;
    pivot.height = Math.max(root.height, pivotLeft != null ? pivotLeft.height : 0) + 1;
  }

  @Nullable private EntrySet entrySet;
  @Nullable private KeySet keySet;

  final class EntrySet extends AbstractSet<Entry<K, V>> {
    @Override
    public int size() {
      return size;
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
      return new LinkedTreeMapIterator<Entry<K, V>>() {
        @Override
        public Entry<K, V> next() {
          return nextNode();
        }
      };
    }

    @Override
    public boolean contains(Object o) {
      return o instanceof Entry && findByEntry((Entry<?, ?>) o) != null;
    }

    @Override
    public boolean remove(Object o) {
      if (!(o instanceof Entry)) {
        return false;
      }
      Node<K, V> node = findByEntry((Entry<?, ?>) o);
      if (node == null) {
        return false;
      }
      removeInternal(node, true);
      return true;
    }

    @Override
    public void clear() {
      LinkedHashTreeMap.this.clear();
    }
  }

  final class KeySet extends AbstractSet<K> {
    @Override
    public int size() {
      return size;
    }

    @Override
    public Iterator<K> iterator() {
      return new LinkedTreeMapIterator<K>() {
        @Override
        public K next() {
          return nextNode().key;
        }
      };
    }

    @Override
    public boolean contains(Object o) {
      return containsKey(o);
    }

    @Override
    public boolean remove(Object key) {
      return removeInternalByKey(key) != null;
    }

    @Override
    public void clear() {
      LinkedHashTreeMap.this.clear();
    }
  }

  abstract class LinkedTreeMapIterator<T> implements Iterator<T> {
    Node<K, V> next = header.next;
    Node<K, V> lastReturned = null;
    int expectedModCount = modCount;

    @Override
    public final boolean hasNext() {
      return next != header;
    }

    final Node<K, V> nextNode() {
      Node<K, V> e = next;
      if (e == header) {
        throw new NoSuchElementException();
      }
      if (modCount != expectedModCount) {
        throw new ConcurrentModificationException();
      }
      next = e.next;
      lastReturned = e;
      return e;
    }

    @Override
    public final void remove() {
      if (lastReturned == null) {
        throw new IllegalStateException();
      }
      removeInternal(lastReturned, true);
      lastReturned = null;
      expectedModCount = modCount;
    }
  }

  static final class Node<K, V> implements Entry<K, V> {
    @Nullable Node<K, V> parent;
    @Nullable Node<K, V> left;
    @Nullable Node<K, V> right;
    @Nullable Node<K, V> next;
    @Nullable Node<K, V> prev;
    final K key;
    V value;
    final int hash;
    int height;

    /** Create the header entry */
    Node() {
      key = null;
      hash = -1;
      next = prev = this;
    }

    /** Create a regular entry */
    Node(Node<K, V> parent, K key, int hash, Node<K, V> next, @Nullable Node<K, V> prev) {
      this.parent = parent;
      this.key = key;
      this.hash = hash;
      this.height = 1;
      this.next = next;
      this.prev = prev;
      prev.next = this;
      next.prev = this;
    }

    @Nullable
    @Override
    public K getKey() {
      return key;
    }

    @Nullable
    @Override
    public V getValue() {
      return value;
    }

    @Nullable
    @Override
    public V setValue(V value) {
      V oldValue = this.value;
      this.value = value;
      return oldValue;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object o) {
      if (o instanceof Entry) {
        Entry other = (Entry) o;
        return (key == null ? other.getKey() == null : key.equals(other.getKey()))
            && (value == null ? other.getValue() == null : value.equals(other.getValue()));
      }
      return false;
    }

    @Override
    public int hashCode() {
      return (key == null ? 0 : key.hashCode()) ^ (value == null ? 0 : value.hashCode());
    }

    @Override
    public String toString() {
      return key + "=" + value;
    }

    /** Returns the first node in this subtree. */
    public Node<K, V> first() {
      Node<K, V> node = this;
      Node<K, V> child = node.left;
      while (child != null) {
        node = child;
        child = node.left;
      }
      return node;
    }

    /** Returns the last node in this subtree. */
    public Node<K, V> last() {
      Node<K, V> node = this;
      Node<K, V> child = node.right;
      while (child != null) {
        node = child;
        child = node.right;
  /**
   * Returns a new array containing the same nodes as {@code oldTable}, but with twice as many
   * trees, each of (approximately) half the previous size.
   */
  static <K, V> Node<K, V>[] doubleCapacity(Node<K, V>[] oldTable) {
    int oldCapacity = oldTable.length;
    @SuppressWarnings("unchecked")
    Node<K, V>[] newTable = new Node[oldCapacity * 2];
    AvlIterator<K, V> iterator = new AvlIterator<K, V>();
    AvlBuilder<K, V> builder = new AvlBuilder<K, V>();
    for (int i = 0; i < oldCapacity; i++) {
      Node<K, V> root = oldTable[i];
      if (root == null) {
        continue;
      }
      iterator.reset(root);
      int leftCount = 0;
      int rightCount = 0;
      Node<K, V> node;
      while ((node = iterator.next()) != null) {
        if ((node.hash & oldCapacity) == 0) {
          leftCount++;
        } else {
          rightCount++;
        }
      }
      builder.reset(leftCount);
      iterator.reset(root);
      while ((node = iterator.next()) != null) {
        if ((node.hash & oldCapacity) == 0) {
          builder.add(node);
        }
      }
      Node<K, V> leftRoot = (leftCount > 0) ? builder.root() : null;
      newTable[i] = leftRoot;
      builder.reset(rightCount);
      iterator.reset(root);
      while ((node = iterator.next()) != null) {
        if ((node.hash & oldCapacity) != 0) {
          builder.add(node);
        }
      }
      Node<K, V> rightRoot = (rightCount > 0) ? builder.root() : null;
      newTable[i + oldCapacity] = rightRoot;
    }
    return newTable;
  }

      }
      return node;
    }
  }


  static int secondaryHash(int h) {
    h ^= (h >>> 20) ^ (h >>> 12);
    return h ^ (h >>> 7) ^ (h >>> 4);
  }

  static boolean equal(@Nullable Object a, @Nullable Object b) {
    return a == b || (a != null && a.equals(b));
  }

  public boolean containsKey(@Nullable Object key) {
    return findByObject(key) != null;
  }
}
