package graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A min priority queue of distinct elements of type `KeyType` associated with (extrinsic) integer
 * priorities, implemented using a binary heap paired with a hash table.
 */
public class HeapMinQueue<KeyType> implements MinQueue<KeyType> {

    /**
     * Pairs an element `key` with its associated priority `priority`.
     */
    private record Entry<KeyType>(KeyType key, int priority) {
        // Note: This is equivalent to declaring a static nested class with fields `key` and
        //  `priority` and a corresponding constructor and observers, overriding `equals()` and
        //  `hashCode()` to depend on the fields, and overriding `toString()` to print their values.
        // https://docs.oracle.com/en/java/javase/17/language/records.html
    }

    /**
     * Associates each element in the queue with its index in `heap`.  Satisfies
     * `heap.get(index.get(e)).key().equals(e)` if `e` is an element in the queue. Only maps
     * elements that are in the queue (`index.size() == heap.size()`).
     */
    private final Map<KeyType, Integer> index;

    /**
     * Sequence representing a min-heap of element-priority pairs.  Satisfies
     * `heap.get(i).priority() >= heap.get((i-1)/2).priority()` for all `i` in `[1..heap.size()]`.
     */
    private final ArrayList<Entry<KeyType>> heap;

    /**
     * Assert that our class invariant is satisfied.  Returns true if it is (or if assertions are
     * disabled).
     */
    private boolean checkInvariant() {
        for (int i = 1; i < heap.size(); ++i) {
            int p = (i - 1) / 2;
            assert heap.get(i).priority() >= heap.get(p).priority();
            assert index.get(heap.get(i).key()) == i;
        }
        assert index.size() == heap.size();
        return true;
    }

    /**
     * Create an empty queue.
     */
    public HeapMinQueue() {
        index = new HashMap<>();
        heap = new ArrayList<>();
        assert checkInvariant();
    }

    /**
     * Return whether this queue contains no elements.
     */
    @Override
    public boolean isEmpty() {
        return heap.isEmpty();
    }

    /**
     * Return the number of elements contained in this queue.
     */
    @Override
    public int size() {
        return heap.size();
    }

    /**
     * Return an element associated with the smallest priority in this queue.  This is the same
     * element that would be removed by a call to `remove()` (assuming no mutations in between).
     * Throws NoSuchElementException if this queue is empty.
     */
    @Override
    public KeyType get() {
        // Propagate exception from `List::getFirst()` if empty.
        return heap.getFirst().key();
    }

    /**
     * Return the minimum priority associated with an element in this queue.  Throws
     * NoSuchElementException if this queue is empty.
     */
    @Override
    public int minPriority() {
        return heap.getFirst().priority();
    }

    /**
     * If `key` is already contained in this queue, change its associated priority to `priority`.
     * Otherwise, add it to this queue with that priority.
     */
    @Override
    public void addOrUpdate(KeyType key, int priority) {
        if (!index.containsKey(key)) {
            add(key, priority);
        } else {
            update(key, priority);
        }
    }

    /**
     * Remove and return the element associated with the smallest priority in this queue.  If
     * multiple elements are tied for the smallest priority, an arbitrary one will be removed.
     * Throws NoSuchElementException if this queue is empty.
     */
    @Override
    public KeyType remove() {
        if (isEmpty()) {
            throw new NoSuchElementException();
        }
        swap(0, size() - 1);
        Entry<KeyType> minVal = heap.removeLast();
        index.remove(minVal.key());
        bubbleDown(0);
        return minVal.key();
    }

    /**
     * Remove all elements from this queue (making it empty).
     */
    @Override
    public void clear() {
        index.clear();
        heap.clear();
        assert checkInvariant();
    }

    /**
     * Swap the Entries at indices `i` and `j` in `heap`, updating `index` accordingly.  Requires `0
     * <= i,j < heap.size()`.
     */
    private void swap(int i, int j) {
        assert i >= 0 && i < heap.size();
        assert j >= 0 && j < heap.size();

        Entry<KeyType> iVal = heap.get(i);
        Entry<KeyType> jVal = heap.get(j);

        heap.set(j, iVal);
        heap.set(i, jVal);

        index.put(iVal.key(), j);
        index.put(jVal.key(), i);
    }

    /**
     * Add element `key` to this queue, associated with priority `priority`.  Requires `key` is not
     * contained in this queue.
     */
    private void add(KeyType key, int priority) {
        assert !index.containsKey(key);

        index.put(key, size());
        heap.add(new Entry<>(key, priority));

        bubbleUp(size()-1);

        assert checkInvariant();
    }

    /**
     * Change the priority associated with element `key` to `priority`.  Requires that `key` is
     * contained in this queue.
     */
    private void update(KeyType key, int priority) {
        assert index.containsKey(key);

        int heapPos = index.get(key);
        Entry<KeyType> curr = heap.get(heapPos);
        Entry<KeyType> newEntry = new Entry<>(curr.key(), priority);
        heap.set(heapPos, newEntry);

        if (priority < curr.priority) {
            bubbleUp(heapPos);
        }
        else if (priority > curr.priority) {
            bubbleDown(heapPos);
        }

        assert checkInvariant();
    }

    /**
     * Bubbles up the Entry at indices `i` to appropriate level in heap.
     * Requires 'i < heap.size() or heap.isEmpty()'
     */
    private void bubbleUp(int i) {
        assert i < heap.size() || heap.isEmpty();

        if (i == 0) {return;}
        int parentIndex = (i - 1) / 2;
        int parentPriority = heap.get(parentIndex).priority();
        int priority = heap.get(i).priority();

        if (parentPriority < priority) {
            return;
        }
        swap(i, parentIndex);
        bubbleUp(parentIndex);
    }

    /**
     * Bubbles down the Entry at indices `i` to appropriate level in heap.
     * Requires 'i < heap.size() or heap.isEmpty()'
     */
    private void bubbleDown(int i) {
        assert i < heap.size() || heap.isEmpty();

        if (isEmpty()) {return;}
        int priority = heap.get(i).priority();
        int leftIndex = (i * 2) + 1;
        int rightIndex = (i * 2) + 2;
        if (size() <= leftIndex) {}
        else if (size() > rightIndex) {
            int leftPriority = heap.get(leftIndex).priority();
            int rightPriority = heap.get(rightIndex).priority();
            if (leftPriority <= rightPriority && leftPriority < priority) {
                swap(i, leftIndex);
                bubbleDown(leftIndex);
            }
            else if (leftPriority > rightPriority && rightPriority < priority) {
                swap(i, rightIndex);
                bubbleDown(rightIndex);
            }
        }
        else if (size() > leftIndex) {
            int leftPriority = heap.get(leftIndex).priority();
            if (leftPriority < priority) {
                swap(i, leftIndex);
                bubbleDown(leftIndex);
            }
        }
    }
}
