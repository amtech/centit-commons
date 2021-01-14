package com.centit.support.common;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 用 ConcurrentHashMap 缓存对象，每个对象 自己维护缓存策略
 *
 * @param <K> id 键值类型
 * @param <T> target 缓存对象的类型
 */
public class DerivativeCachedMap<K, D, T> extends AbstractCachedObject<Map<K, T>> {
    private static Log logger = LogFactory.getLog(DerivativeCachedMap.class);

    private ConcurrentMap<K, CachedIdentifiedObject> targetMap;
    private long freshPeriod;
    private Function<D, T> refresher;
    private CachedMap<K, D> parentCachedMap;

    /**
     * 构造函数
     *
     * @param refresher       重新获取代码的接口
     * @param parentCache     保鲜时间，单位为秒；也是重新刷新时间
     *                        它的意思不是每隔一段时间就刷新，而是在获取数据是检查是否超时，如果超时则刷新
     * @param initialCapacity The implementation performs internal
     *                        sizing to accommodate this many elements.
     */
    public DerivativeCachedMap(Function<D, T> refresher, CachedMap<K, D> parentCache, int initialCapacity) {
        this.targetMap = new ConcurrentHashMap<>(initialCapacity);
        this.refresher = refresher;
        this.parentCachedMap = parentCache;
        parentCache.addDeriveCache(this);
        this.freshPeriod = ICachedObject.DEFALUT_FRESH_PERIOD_SECONDS;
    }

    public DerivativeCachedMap(Function<D, T> refresher, CachedMap<K, D> parentCache) {
        this(refresher, parentCache, 16);
    }

    public void setRefresher(Function<D, T> refresher) {
        this.refresher = refresher;
    }

    public void setFreshPeriod(long freshPeriod) {
        this.freshPeriod = freshPeriod;
    }

    public void evictIdentifiedCache(K key) {
        CachedIdentifiedObject identifiedObject = targetMap.get(key);
        if (identifiedObject != null) {
            identifiedObject.evictCahce();
        }
    }

    @Override
    public void evictCahce() {
        targetMap.clear();
        super.evictCahce();
    }

    public T getCachedValue(K key) {
        CachedIdentifiedObject identifiedObject = targetMap.get(key);
        if (identifiedObject != null) {
            return identifiedObject.getCachedTarget(key);
        }

        identifiedObject = new CachedIdentifiedObject();
        T target = identifiedObject.getFreshTarget(key);
        if (target != null) {
            targetMap.put(key, identifiedObject);
        }
        return target;
    }

    public Map<K, T> getRawTarget() {
        if (targetMap == null) {
            return null;
        }
        Map<K, T> rawTargetMap = new HashMap<>(targetMap.size() + 1);
        for (Map.Entry<K, CachedIdentifiedObject> ent : targetMap.entrySet()) {
            rawTargetMap.put(ent.getKey(), ent.getValue().getRawTarget());
        }
        return rawTargetMap;
    }

    class CachedIdentifiedObject extends AbstractCachedObject<T> {
        private AbstractCachedObject<D> parentCache;

        CachedIdentifiedObject() {
            this.target = null;
            this.evicted = true;
            this.parentCache = null;
        }

        synchronized void refreshData(K key) {
            if (this.parentCache == null) {
                this.parentCache = parentCachedMap.getCachedObject(key);
                if (this.parentCache != null) {
                    this.parentCache.addDeriveCache(this);
                } else {
                    return;
                }
            }

            T tempTarget = null;
            try {
                tempTarget = refresher.apply(parentCachedMap.getCachedValue(key));
            } catch (RuntimeException re) {
                logger.error(re.getLocalizedMessage());
            }
            setRefreshDataAndState(tempTarget, freshPeriod, false);
        }

        T getCachedTarget(K key) {
            if (this.target == null || isTargetOutOfDate(freshPeriod)) {
                refreshData(key);
            }
            return target;
        }

        T getFreshTarget(K key) {
            refreshData(key);
            return target;
        }

        @Override
        public T getRawTarget() {
            return target;
        }
    }
}
