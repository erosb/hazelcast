/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.cache.impl;

import com.hazelcast.cache.CacheEntryView;
import com.hazelcast.cache.CacheMergePolicy;
import com.hazelcast.cache.impl.merge.entry.DefaultCacheEntryView;
import com.hazelcast.cache.impl.merge.policy.CacheMergePolicyProvider;
import com.hazelcast.cache.impl.operation.CacheMergeOperation;
import com.hazelcast.cache.impl.record.CacheRecord;
import com.hazelcast.config.CacheConfig;
import com.hazelcast.core.ExecutionCallback;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.Address;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.SplitBrainHandlerService;
import com.hazelcast.spi.partition.IPartitionService;
import com.hazelcast.spi.serialization.SerializationService;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static com.hazelcast.util.ExceptionUtil.rethrow;
import static com.hazelcast.util.MapUtil.createHashMap;

/**
 * Handles split-brain functionality for cache.
 */
class CacheSplitBrainHandlerService implements SplitBrainHandlerService {

    private final NodeEngine nodeEngine;
    private final Map<String, CacheConfig> configs;
    private final CachePartitionSegment[] segments;
    private final CacheMergePolicyProvider mergePolicyProvider;

    CacheSplitBrainHandlerService(NodeEngine nodeEngine, Map<String, CacheConfig> configs, CachePartitionSegment[] segments) {
        this.nodeEngine = nodeEngine;
        this.configs = configs;
        this.segments = segments;
        this.mergePolicyProvider = new CacheMergePolicyProvider(nodeEngine);
    }

    @Override
    public Runnable prepareMergeRunnable() {
        final Map<String, Map<Data, CacheRecord>> recordMap = createHashMap(configs.size());
        final IPartitionService partitionService = nodeEngine.getPartitionService();
        final int partitionCount = partitionService.getPartitionCount();
        final Address thisAddress = nodeEngine.getClusterService().getThisAddress();

        for (int i = 0; i < partitionCount; i++) {
            // add your owned entries so they will be merged
            if (thisAddress.equals(partitionService.getPartitionOwner(i))) {
                CachePartitionSegment segment = segments[i];
                Iterator<ICacheRecordStore> iterator = segment.recordStoreIterator();
                while (iterator.hasNext()) {
                    ICacheRecordStore cacheRecordStore = iterator.next();
                    if (!(cacheRecordStore instanceof SplitBrainAwareCacheRecordStore)) {
                        continue;
                    }
                    String cacheName = cacheRecordStore.getName();
                    Map<Data, CacheRecord> records = recordMap.get(cacheName);
                    if (records == null) {
                        records = createHashMap(cacheRecordStore.size());
                        recordMap.put(cacheName, records);
                    }
                    for (Map.Entry<Data, CacheRecord> cacheRecordEntry : cacheRecordStore.getReadOnlyRecords().entrySet()) {
                        Data key = cacheRecordEntry.getKey();
                        CacheRecord cacheRecord = cacheRecordEntry.getValue();
                        records.put(key, cacheRecord);
                    }
                    // clear all records either owned or backup
                    cacheRecordStore.clear();

                    // send the cache invalidation event regardless if any actually cleared or not
                    // (no need to know how many actually cleared)
                    final CacheService cacheService = nodeEngine.getService(CacheService.SERVICE_NAME);
                    cacheService.sendInvalidationEvent(cacheName, null, AbstractCacheRecordStore.SOURCE_NOT_AVAILABLE);
                }
            }
        }
        return new CacheMerger(nodeEngine, configs, recordMap, mergePolicyProvider);
    }

    private static class CacheMerger implements Runnable {

        private static final int TIMEOUT_FACTOR = 500;

        private final NodeEngine nodeEngine;
        private final Map<String, CacheConfig> configs;
        private final Map<String, Map<Data, CacheRecord>> recordMap;
        private final CacheMergePolicyProvider mergePolicyProvider;
        private final ILogger logger;

        CacheMerger(NodeEngine nodeEngine, Map<String, CacheConfig> configs, Map<String, Map<Data, CacheRecord>> recordMap,
                    CacheMergePolicyProvider mergePolicyProvider) {
            this.nodeEngine = nodeEngine;
            this.configs = configs;
            this.recordMap = recordMap;
            this.mergePolicyProvider = mergePolicyProvider;
            this.logger = nodeEngine.getLogger(CacheService.class);
        }

        @Override
        public void run() {
            final Semaphore semaphore = new Semaphore(0);
            int recordCount = 0;

            ExecutionCallback<Object> mergeCallback = new ExecutionCallback<Object>() {
                @Override
                public void onResponse(Object response) {
                    semaphore.release(1);
                }

                @Override
                public void onFailure(Throwable t) {
                    logger.warning("Error while running merge operation: " + t.getMessage());
                    semaphore.release(1);
                }
            };

            SerializationService serializationService = nodeEngine.getSerializationService();

            for (Map.Entry<String, Map<Data, CacheRecord>> recordMapEntry : recordMap.entrySet()) {
                String cacheName = recordMapEntry.getKey();
                CacheConfig cacheConfig = configs.get(cacheName);
                Map<Data, CacheRecord> records = recordMapEntry.getValue();
                String mergePolicyName = cacheConfig.getMergePolicy();
                final CacheMergePolicy cacheMergePolicy = mergePolicyProvider.getMergePolicy(mergePolicyName);
                for (Map.Entry<Data, CacheRecord> recordEntry : records.entrySet()) {
                    Data key = recordEntry.getKey();
                    CacheRecord record = recordEntry.getValue();
                    recordCount++;
                    CacheEntryView<Data, Data> entryView = new DefaultCacheEntryView(
                            key,
                            serializationService.toData(record.getValue()),
                            record.getCreationTime(),
                            record.getExpirationTime(),
                            record.getLastAccessTime(),
                            record.getAccessHit());
                    CacheMergeOperation operation = new CacheMergeOperation(
                            cacheName,
                            key,
                            entryView,
                            cacheMergePolicy);
                    try {
                        int partitionId = nodeEngine.getPartitionService().getPartitionId(key);
                        nodeEngine.getOperationService()
                                .invokeOnPartition(ICacheService.SERVICE_NAME, operation, partitionId)
                                .andThen(mergeCallback);
                    } catch (Throwable t) {
                        throw rethrow(t);
                    }
                }
            }
            try {
                semaphore.tryAcquire(recordCount, recordCount * TIMEOUT_FACTOR, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                logger.finest("Interrupted while waiting merge operation...");
            }
        }
    }
}
