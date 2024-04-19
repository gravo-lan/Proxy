package me.gravolan.proxy;

import java.util.HashMap;
import java.util.LinkedList;

public class LimitedCache<K, V> extends HashMap<K, V> {
    private final int maxSize;
    private final LinkedList<K> accessQueue; // Tracks access order

    public LimitedCache(int maxSize) {
        this.maxSize = maxSize;
        this.accessQueue = new LinkedList<>();
    }

    @Override
    public V put(K key, V value) {
        if (size() >= maxSize) {
            evictLRUEntry(); // Remove least recently used entry
        }
        accessQueue.addLast(key); // Add key to the end of the access queue
        return super.put(key, value);
    }

    @Override
    public V get(Object key) {
        V value = super.get(key);
        if (value != null) {
            // Key accessed, move it to the end of the queue (most recently used)
            accessQueue.remove(key);
            accessQueue.addLast((K) key);
        }
        return value;
    }

    private void evictLRUEntry() {
        K leastRecentlyUsedKey = accessQueue.removeFirst(); // Remove first element (LRU)
        super.remove(leastRecentlyUsedKey); // Remove entry from the map
    }
}
