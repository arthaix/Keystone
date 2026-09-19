package ru.arthaix.keystone.chunkkeep;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * An iterator over a copy of the chunks queued for unloading.
 *
 * ChunkProviderServer.tick walks that set and removes through the iterator. Anything that queues or drops a chunk while
 * the loop runs - another thread, or a mod reacting to the unload of the chunk in front - makes the iterator throw
 * ConcurrentModificationException, and a dedicated server dies with "Exception ticking world" (seen twice on
 * 2026-09-19). The loop only ever takes a hundred chunks per tick, so it is given those hundred as a copy: removals
 * still go into the real set, and whatever else touches it in the meantime is simply picked up next tick.
 */
public final class DropSnapshot implements Iterator<Long> {
    /** Vanilla unloads at most 100 per tick; a little more costs nothing and leaves room if that ever changes. */
    private static final int LIMIT = 128;

    private final Set<Long> set;
    private final Object[] items;
    private int next;
    private Long last;

    private DropSnapshot(Set<Long> set, Object[] items) {
        this.set = set;
        this.items = items;
    }

    public static Iterator<Long> of(Set<Long> set) {
        return new DropSnapshot(set, copy(set));
    }

    private static Object[] copy(Set<Long> set) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                int size = set.size();
                Object[] out = new Object[size < LIMIT ? size : LIMIT];
                int n = 0;
                for (Long id : set) {
                    if (n == out.length) {
                        break;
                    }
                    out[n++] = id;
                }
                return n == out.length ? out : Arrays.copyOf(out, n);
            } catch (RuntimeException changedWhileCopying) {
                // something added to the set as it was read: read it again
            }
        }
        return new Object[0];
    }

    @Override
    public boolean hasNext() {
        return this.next < this.items.length;
    }

    @Override
    public Long next() {
        if (this.next >= this.items.length) {
            throw new NoSuchElementException();
        }
        this.last = (Long) this.items[this.next++];
        return this.last;
    }

    @Override
    public void remove() {
        Long id = this.last;
        if (id != null) {
            this.last = null;
            this.set.remove(id);
        }
    }
}
