package ru.arthaix.keystone.teunloadbatch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

/**
 * Replacement for World.tickableTileEntities in which World.removeTileEntity no longer scans the list.
 *
 * Every replaced block with a tile entity (outside the tile entity tick loop) called tickableTileEntities.remove(te), a
 * scan over every ticking tile entity; a large WorldEdit edit repeated it for each tile entity it replaced (10% of the
 * server time of large edits). That one call site now uses removeLater (MixinWorldTickableRemoval), which only records
 * the element. Recorded removals are applied in a single ordered pass the next time the list is used in any other way
 * (the tick loop's iterator, size, get, removeAll ...), or as soon as they amount to a quarter of the list.
 *
 * Every other method applies them first and then is plain ArrayList, so no reader ever sees a removed element, and
 * the remaining elements keep the order immediate removal gives (tick order unchanged). The pass removes, for each
 * recorded removal, the first occurrence still present, exactly like the sequence of remove(Object) calls it replaces.
 * add(e) appends without applying them unless e itself is waiting for removal, the one case where appending first would
 * change the result. Recorded removals match by identity; elements whose class overrides equals are removed at once.
 */
public final class DeferredRemovalList<E> extends ArrayList<E> {
    private static final long serialVersionUID = 1L;
    private static final int MIN_BATCH = 64;
    private static final ClassValue<Boolean> OWN_EQUALS = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() != Object.class;
            } catch (Throwable t) {
                return Boolean.TRUE;
            }
        }
    };

    /** element -> number of recorded removals not applied yet */
    private transient Reference2IntOpenHashMap<Object> pending;
    private transient int pendingCount;

    /** The thread this list was first used from: Minecraft's world lists belong to one thread. */
    private transient Thread owner;

    /** Set once the recorded removals were lost: from then on every removal is applied at once. */
    private transient boolean degraded;

    private static boolean reportedForeign;
    private static boolean reportedBroken;

    public DeferredRemovalList(Collection<? extends E> initial) {
        super(initial);
    }

    /**
     * Whether the recorded removals may be used now. They are a map on the side, and a mod touching the world's tile
     * entity lists from its own thread can leave it half written (a crash in fastutil while the server ticks, seen
     * 2026-09-19). Calls from another thread than the one the list belongs to take the plain list path instead, and the
     * first such call is logged with its stack so the mod behind it can be found.
     */
    private boolean recording() {
        if (this.degraded) {
            return false;
        }
        Thread t = Thread.currentThread();
        Thread o = this.owner;
        if (o == null) {
            this.owner = t;
            return true;
        }
        if (o == t) {
            return true;
        }
        if (!reportedForeign) {
            reportedForeign = true;
            org.apache.logging.log4j.LogManager.getLogger("keystone").warn(
                    "[teunloadbatch] the tile entity list of " + o.getName() + " is used from " + t.getName()
                            + "; batching is off for it from now on", new IllegalStateException("thread of the caller"));
        }
        this.degraded = true;
        dropPendingSafely();
        return false;
    }

    /** The recorded removals cannot be trusted any more: apply what can still be applied and stop recording. */
    private void broken(Throwable t) {
        this.degraded = true;
        this.pending = null;
        this.pendingCount = 0;
        if (!reportedBroken) {
            reportedBroken = true;
            org.apache.logging.log4j.LogManager.getLogger("keystone").warn(
                    "[teunloadbatch] the recorded removals were left broken, the list keeps working without them", t);
        }
    }

    private void dropPendingSafely() {
        try {
            applyRemovals();
        } catch (RuntimeException t) {
            broken(t);
        }
    }

    /** remove(o) whose effect may be applied later; the return value of remove is not available. */
    public synchronized void removeLater(Object o) {
        if (o == null || OWN_EQUALS.get(o.getClass()) || !recording()) {
            remove(o);
            return;
        }
        try {
            record(o);
        } catch (RuntimeException t) {
            broken(t);
            remove(o);
        }
    }

    private void record(Object o) {
        Reference2IntOpenHashMap<Object> p = this.pending;
        if (p == null) {
            p = new Reference2IntOpenHashMap<Object>();
            this.pending = p;
        }
        p.addTo(o, 1);
        this.pendingCount++;
        EditStats.tickableDeferred++;
        if (this.pendingCount >= MIN_BATCH && this.pendingCount * 4L >= super.size()) {
            applyRemovals();
        }
    }

    /** Number of recorded removals not applied yet (tests). */
    public int pendingRemovals() {
        return this.pendingCount;
    }

    private synchronized void applyRemovals() {
        if (this.pendingCount == 0) {
            return;
        }
        long t0 = System.nanoTime();
        Reference2IntOpenHashMap<Object> p = this.pending;
        int left = this.pendingCount;
        int n = super.size();
        int w = 0;
        int r = 0;
        for (; r < n && left > 0; r++) {
            E e = super.get(r);
            if (e != null) {
                int c = p.getInt(e);
                if (c > 0) {
                    if (c == 1) {
                        p.removeInt(e);
                    } else {
                        p.put(e, c - 1);
                    }
                    left--;
                    continue;
                }
            }
            if (w != r) {
                super.set(w, e);
            }
            w++;
        }
        // [w, r) are free slots; the tail from r on is untouched and moves down in one copy
        if (w < r) {
            super.removeRange(w, r);
        }
        if (this.pendingCount > 4096) {
            this.pending = null;
        } else {
            p.clear();
        }
        this.pendingCount = 0;
        EditStats.tickablePasses++;
        EditStats.tickablePassNanos += System.nanoTime() - t0;
    }

    private synchronized void dropPending() {
        if (this.pendingCount != 0) {
            this.pending = null;
            this.pendingCount = 0;
        }
    }

    // ---------------- ArrayList, with recorded removals applied first ----------------

    @Override
    public synchronized boolean add(E e) {
        try {
            if (this.pendingCount != 0 && e != null && this.pending != null && this.pending.containsKey(e)) {
                applyRemovals();
            }
        } catch (RuntimeException t) {
            broken(t);
        }
        return super.add(e);
    }

    @Override
    public void add(int index, E e) {
        applyRemovals();
        super.add(index, e);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        applyRemovals();
        return super.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        applyRemovals();
        return super.addAll(index, c);
    }

    @Override
    public int size() {
        applyRemovals();
        return super.size();
    }

    @Override
    public boolean isEmpty() {
        applyRemovals();
        return super.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        applyRemovals();
        return super.contains(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        applyRemovals();
        return super.containsAll(c);
    }

    @Override
    public int indexOf(Object o) {
        applyRemovals();
        return super.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        applyRemovals();
        return super.lastIndexOf(o);
    }

    @Override
    public E get(int index) {
        if (this.pendingCount != 0) {
            applyRemovals();
        }
        return super.get(index);
    }

    @Override
    public E set(int index, E e) {
        applyRemovals();
        return super.set(index, e);
    }

    @Override
    public E remove(int index) {
        applyRemovals();
        return super.remove(index);
    }

    @Override
    public boolean remove(Object o) {
        applyRemovals();
        return super.remove(o);
    }

    @Override
    protected void removeRange(int from, int to) {
        applyRemovals();
        super.removeRange(from, to);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        applyRemovals();
        return super.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        applyRemovals();
        return super.retainAll(c);
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        applyRemovals();
        return super.removeIf(filter);
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        applyRemovals();
        super.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super E> c) {
        applyRemovals();
        super.sort(c);
    }

    @Override
    public void clear() {
        dropPending();
        super.clear();
    }

    @Override
    public void trimToSize() {
        applyRemovals();
        super.trimToSize();
    }

    @Override
    public Object[] toArray() {
        applyRemovals();
        return super.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        applyRemovals();
        return super.toArray(a);
    }

    @Override
    public Iterator<E> iterator() {
        applyRemovals();
        return super.iterator();
    }

    @Override
    public ListIterator<E> listIterator() {
        applyRemovals();
        return super.listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        applyRemovals();
        return super.listIterator(index);
    }

    @Override
    public List<E> subList(int from, int to) {
        applyRemovals();
        return super.subList(from, to);
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        applyRemovals();
        super.forEach(action);
    }

    @Override
    public Spliterator<E> spliterator() {
        applyRemovals();
        return super.spliterator();
    }

    @Override
    public boolean equals(Object o) {
        applyRemovals();
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        applyRemovals();
        return super.hashCode();
    }

    @Override
    public String toString() {
        applyRemovals();
        return super.toString();
    }

    /** A plain ArrayList copy: a clone would share the recorded removals. */
    @Override
    public Object clone() {
        applyRemovals();
        return new ArrayList<E>(this);
    }
}
