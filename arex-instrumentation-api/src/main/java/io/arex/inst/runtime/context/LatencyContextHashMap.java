package io.arex.inst.runtime.context;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Only used for ContextManager
 */
final class LatencyContextHashMap extends ConcurrentHashMap<String, ArexContext> {
    private static final int CLEANUP_THRESHOLD = 10;
    private static final long RECORD_TTL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final ReentrantLock CLEANUP_LOCK = new ReentrantLock();
    private ConcurrentHashMap<String, ArexContext> latencyMap;

    @Override
    public ArexContext get(Object key) {
        ArexContext context = super.get(key);
        return context == null ? initOrGet(key) : context;
    }

    @Override
    public ArexContext remove(Object key) {
        ArexContext context = super.remove(key);
        overdueCleanUp();
        if (latencyMap != null && context != null) {
            latencyMap.put(String.valueOf(key), context);
        }

        return context;
    }

    private ArexContext initOrGet(Object key) {
        if (latencyMap == null) {
            latencyMap = new ConcurrentHashMap<>();
            return null;
        }
        return latencyMap.get(key);
    }

    private void overdueCleanUp() {
        if (latencyMap != null && CLEANUP_LOCK.tryLock() && latencyMap.mappingCount() > CLEANUP_THRESHOLD) {
            try {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, ArexContext> entry: latencyMap.entrySet()) {
                    if (isExpired(entry.getValue().getCreateTime(), now)) {
                        // clear context attachments
                        entry.getValue().clear();
                        latencyMap.remove(entry.getKey());
                    }
                }
            } finally {
                CLEANUP_LOCK.unlock();
            }
        }

        // Compatible where map.remove() not called
        if (CLEANUP_LOCK.tryLock() && this.mappingCount() > CLEANUP_THRESHOLD) {
            try {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, ArexContext> entry: super.entrySet()) {
                    if (isExpired(entry.getValue().getCreateTime(), now)) {
                        super.remove(entry.getKey());
                    }
                }
            } finally {
                CLEANUP_LOCK.unlock();
            }
        }
    }

    private static boolean isExpired(long createTime, long now) {
        return now - createTime >= RECORD_TTL_MILLIS;
    }
}
