/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc.segment;

import java.util.concurrent.locks.StampedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.caffinitas.ohc.api.BytesSource;
import org.caffinitas.ohc.api.OHCacheBuilder;
import org.caffinitas.ohc.internal.Util;
import org.caffinitas.ohc.segment.replacement.ReplacementCallback;
import org.caffinitas.ohc.segment.replacement.ReplacementStrategy;

final class OffHeapMap implements Constants
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OffHeapMap.class);

    private final double entriesPerSegmentTrigger;

    volatile long size;

    private final DataMemory dataMemory;
    private final ReplacementStrategy replacementStrategy;
    private final StampedLock lock;

    private volatile Table table;
    private volatile boolean rehashTrigger;
    private ReplacementCallback replacementCallback = new ReplacementCallback()
    {
        public long evict(long hashEntryAdr)
        {
            long bytes = DataMemory.getEntryBytes(hashEntryAdr);
            long hash = HashEntries.getHash(hashEntryAdr);
            table.removeLink(hash, hashEntryAdr);

            if (HashEntries.dereference(hashEntryAdr))
                dataMemory.free(hashEntryAdr);

            size--;

            return bytes;
        }
    };

    OffHeapMap(OHCacheBuilder builder, DataMemory dataMemory, ReplacementStrategy replacementStrategy)
    {
        this.dataMemory = dataMemory;
        this.replacementStrategy = replacementStrategy;
        lock = new StampedLock();

        int hts = builder.getHashTableSize();
        if (hts < 8192)
            hts = 8192;
        table = new Table(Util.roundUpToPowerOf2(hts));

        this.entriesPerSegmentTrigger = builder.getEntriesPerSegmentTrigger();
    }

    void release()
    {
        table.release();
    }

    long size()
    {
        return size;
    }

    long lock()
    {
        return lock.writeLock();
    }

    void unlock(long stamp)
    {
        lock.unlockWrite(stamp);
    }

    long getEntry(long hash, BytesSource keySource)
    {
        long firstHashEntryAdr = table.first(hash);
        boolean first = true;
        int loops = 0;
        for (long hashEntryAdr = firstHashEntryAdr;
             hashEntryAdr != 0L;
             hashEntryAdr = HashEntries.getNext(hashEntryAdr), loops++, first = false)
        {
            assertNotEndlessLoop(hash, firstHashEntryAdr, first, hashEntryAdr);

            if (notSameKey(hash, keySource, loops, hashEntryAdr))
                continue;

            // return existing entry

            table.removeLink(hash, hashEntryAdr);
            table.addLinkAsHead(hash, hashEntryAdr);

            replacementStrategy.entryUsed(hashEntryAdr);

            return hashEntryAdr;
        }

        // not found

        return 0L;
    }

    long replaceEntry(long hash, BytesSource keySource, long newHashEntryAdr)
    {
        long firstHashEntryAdr = table.first(hash);
        boolean first = true;
        int loops = 0;
        long hashEntryAdr;
        for (hashEntryAdr = firstHashEntryAdr;
             hashEntryAdr != 0L;
             hashEntryAdr = HashEntries.getNext(hashEntryAdr), loops++, first = false)
        {
            assertNotEndlessLoop(hash, firstHashEntryAdr, first, hashEntryAdr);

            if (notSameKey(hash, keySource, loops, hashEntryAdr))
                continue;

            // replace existing entry

            table.removeLink(hash, hashEntryAdr);

            break;
        }

        // add new entry

        if (hashEntryAdr == 0L)
            size++;

        table.addLinkAsHead(hash, newHashEntryAdr);
        replacementStrategy.entryReplaced(hashEntryAdr, newHashEntryAdr);

        return hashEntryAdr;
    }

    long removeEntry(long hash, BytesSource keySource)
    {
        long firstHashEntryAdr = table.first(hash);
        boolean first = true;
        int loops = 0;
        for (long hashEntryAdr = firstHashEntryAdr;
             hashEntryAdr != 0L;
             hashEntryAdr = HashEntries.getNext(hashEntryAdr), loops++, first = false)
        {
            assertNotEndlessLoop(hash, firstHashEntryAdr, first, hashEntryAdr);

            if (notSameKey(hash, keySource, loops, hashEntryAdr))
                continue;

            // remove existing entry

            table.removeLink(hash, hashEntryAdr);

            replacementStrategy.entryRemoved(hashEntryAdr);

            size--;

            return hashEntryAdr;
        }

        // no entry to remove

        return 0L;
    }

    private void assertNotEndlessLoop(long hash, long firstHashEntryAdr, boolean first, long hashEntryAdr)
    {
        if (!first && firstHashEntryAdr == hashEntryAdr)
            throw new InternalError("endless loop for hash " + hash);
    }

    private boolean notSameKey(long hash, BytesSource keySource, int loops, long hashEntryAdr)
    {
        maybeTriggerRehash(loops);

        long hashEntryHash = HashEntries.getHash(hashEntryAdr);
        if (hashEntryHash != hash)
            return true;

        long serKeyLen = HashEntries.getHashKeyLen(hashEntryAdr);
        return serKeyLen != keySource.size()
               || !HashEntries.compareKey(hashEntryAdr, keySource, serKeyLen);
    }

    private void maybeTriggerRehash(int loops)
    {
        if (loops >= entriesPerSegmentTrigger && !rehashTrigger)
        {
            rehashTrigger = true;
            LOGGER.warn("Degraded OHC performance! Segment linked list very long - rehash triggered");
        }
    }

    boolean rehashTriggered()
    {
        return rehashTrigger;
    }

    long cleanUp(long recycleGoal)
    {
        return replacementStrategy.cleanUp(recycleGoal, replacementCallback);
    }

    void rehash()
    {
        Table tab = table;
        int tableSize = tab.size();
        if (tableSize > 1 << 24)
        {
            // already at max hash table size - keep rehashTrigger field true
            return;
        }

        long t0 = System.currentTimeMillis();

        Table newTable = new Table(tableSize * 2);
        long next, hash;

        for (int part = 0; part < tableSize; part++)
            for (long hashEntryAdr = tab.first(part);
                 hashEntryAdr != 0L;
                 hashEntryAdr = next)
            {
                next = HashEntries.getNext(hashEntryAdr);

                HashEntries.setNext(hashEntryAdr, 0L);
                hash = HashEntries.getHash(hashEntryAdr);
                newTable.addLinkAsHead(hash, hashEntryAdr);
            }

        long t = System.currentTimeMillis() - t0;
        LOGGER.debug("Rehashed table - increased table size from {} to {} in {}ms", tableSize, tableSize * 2, t);

        table = newTable;
        rehashTrigger = false;
    }

    static final class Table
    {
        final int segmentMask;
        final long address;

        public Table(int hashTableSize)
        {
            int msz = (int) BUCKET_ENTRY_LEN * hashTableSize;
            this.address = Uns.allocate(msz);
            segmentMask = hashTableSize - 1;
            // It's important to initialize the hash segment table memory.
            // (uninitialized memory will cause problems - endless loops, JVM crashes, damaged data, etc)
            Uns.setMemory(address, 0L, msz, (byte) 0);
        }

        void release()
        {
            Uns.free(address);
        }

        long first(long hash)
        {
            return Uns.getLongVolatile(address, bucketOffset(hash));
        }

        void first(long hash, long hashEntryAdr)
        {
            Uns.putLongVolatile(address, bucketOffset(hash), hashEntryAdr);
        }

        private long bucketOffset(long hash)
        {
            return bucketIndexForHash(hash) * BUCKET_ENTRY_LEN;
        }

        private int bucketIndexForHash(long hash)
        {
            return (int) (hash & segmentMask);
        }

        void removeLink(long hash, long hashEntryAdr)
        {
            long prev = HashEntries.getPrevious(hashEntryAdr);
            long next = HashEntries.getNext(hashEntryAdr);

            long head = first(hash);
            if (head == hashEntryAdr)
            {
                if (prev != 0L)
                    throw new IllegalStateException("head must not have a previous entry");
                first(hash, next);
            }

            if (prev != 0L)
                HashEntries.setNext(prev, next);
            if (next != 0L)
                HashEntries.setPrevious(next, prev);

            // just for safety
            HashEntries.setPrevious(hashEntryAdr, 0L);
            HashEntries.setNext(hashEntryAdr, 0L);
        }

        void addLinkAsHead(long hash, long hashEntryAdr)
        {
            long head = first(hash);
            HashEntries.setNext(hashEntryAdr, head);
            HashEntries.setPrevious(hashEntryAdr, 0L); // just for safety
            first(hash, hashEntryAdr);
            if (head != 0L)
                HashEntries.setPrevious(head, hashEntryAdr);
        }

        int size()
        {
            return segmentMask + 1;
        }
    }
}
