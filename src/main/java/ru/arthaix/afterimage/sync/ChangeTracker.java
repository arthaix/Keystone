package ru.arthaix.afterimage.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

/**
 * Server side of Afterimage: remembers, per dimension, the last world tick at which each chunk changed in a way that
 * clients have to render again. A change is a block state change or a block update notification (tile entities such as
 * LittleTiles and Chisels & Bits announce their own changes that way). Loading a chunk is not a change: notifications
 * during Chunk.onLoad and for GRACE_TICKS afterwards are ignored.
 *
 * Kept in memory and in <world>/afterimage/changes-DIM<n>.bin. The world also gets a random id in
 * <world>/afterimage/world-id.txt, so clients keep the caches of different worlds on one server apart.
 * Server thread only in practice; every method is synchronized for the odd mod that edits blocks elsewhere.
 */
public final class ChangeTracker {
    public static final long GRACE_TICKS = 40;
    private static final int MAGIC = 0x41494348;
    private static final int VERSION = 1;

    private static final Map<Integer, Long2LongOpenHashMap> CHANGES = new HashMap<Integer, Long2LongOpenHashMap>();
    private static final Map<Integer, Long2LongOpenHashMap> PENDING = new HashMap<Integer, Long2LongOpenHashMap>();
    private static final Map<Integer, Long2LongOpenHashMap> LOADED_AT = new HashMap<Integer, Long2LongOpenHashMap>();
    private static final Set<Integer> DIRTY = new HashSet<Integer>();
    private static int loadDepth;
    private static File dir;
    private static File saveRoot;
    private static long lastSnapshot;
    private static long offlineChunks;
    private static String worldId;
    private static long recorded;
    private static long ignoredLoading;
    private static long ignoredGrace;

    private ChangeTracker() {
    }

    /** Dimension id as the client cache names it (DimensionType id; equals the Forge dimension id for registered dimensions). */
    public static int dimension(World w) {
        return w.field_73011_w.func_186058_p().func_186068_a();
    }

    private static Long2LongOpenHashMap map(Map<Integer, Long2LongOpenHashMap> m, int dim) {
        Long2LongOpenHashMap x = m.get(dim);
        if (x == null) {
            x = new Long2LongOpenHashMap();
            x.defaultReturnValue(Long.MIN_VALUE);
            m.put(dim, x);
        }
        return x;
    }

    // ---------------- lifecycle ----------------

    public static synchronized void start(File saveRoot) {
        if (saveRoot == null) {
            return;
        }
        File d = new File(saveRoot, "afterimage");
        if (d.equals(dir)) {
            return;
        }
        if (dir != null) {
            stop();
        }
        d.mkdirs();
        dir = d;
        worldId = readOrCreateId(new File(d, "world-id.txt"));
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.startsWith("changes-DIM") && n.endsWith(".bin")) {
                    try {
                        int dim = Integer.parseInt(n.substring("changes-DIM".length(), n.length() - ".bin".length()));
                        load(f, map(CHANGES, dim));
                    } catch (Throwable t) {
                        System.out.println("[afterimage] could not read " + f + ": " + t);
                    }
                }
            }
        }
        ChangeTracker.saveRoot = saveRoot;
        offlineEdits(saveRoot, d);
        System.out.println("[afterimage] tracking chunk changes in " + d + ", world id " + worldId + ", " + total()
            + " changed chunks on record");
    }

    /**
     * Chunks written while the server was down (a script that edits the world, a world copied in) are recorded as
     * changed now, so clients drop the far copies they still hold of them. See RegionWatch.
     */
    private static void offlineEdits(File root, File d) {
        try {
            File snapshot = new File(d, "regions.bin");
            Map<Integer, java.util.List<Long>> edits = RegionWatch.offlineEdits(root, snapshot);
            long now = worldTime();
            long count = 0;
            for (Map.Entry<Integer, java.util.List<Long>> e : edits.entrySet()) {
                int dim = e.getKey();
                Long2LongOpenHashMap ch = map(CHANGES, dim);
                for (Long key : e.getValue()) {
                    ch.put(key.longValue(), now);
                    count++;
                }
                if (!e.getValue().isEmpty()) {
                    DIRTY.add(dim);
                }
            }
            offlineChunks = count;
            if (count > 0) {
                System.out.println("[afterimage] " + count + " chunks were written while the server was down; "
                        + "clients drop their far copies of them");
                save();
            }
            RegionWatch.write(root, snapshot);
            lastSnapshot = now;
        } catch (Throwable t) {
            System.out.println("[afterimage] could not check the world files for edits made while down: " + t);
        }
    }

    /** Overworld time, 0 before the world is there. */
    private static long worldTime() {
        try {
            World w = net.minecraftforge.common.DimensionManager.getWorld(0);
            return w == null ? 0L : w.func_82737_E();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** Server thread, every few minutes: what the files look like now, so the next start only sees outside edits. */
    private static void refreshSnapshot(long now) {
        final File root = saveRoot;
        final File d = dir;
        if (root == null || d == null || now - lastSnapshot < 6000L) {
            return;
        }
        lastSnapshot = now;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                RegionWatch.write(root, new File(d, "regions.bin"));
            }
        }, "afterimage-regions");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    public static synchronized void stop() {
        if (dir == null) {
            return;
        }
        save();
        if (saveRoot != null) {
            RegionWatch.write(saveRoot, new File(dir, "regions.bin"));
        }
        System.out.println("[afterimage] " + summary());
        CHANGES.clear();
        PENDING.clear();
        LOADED_AT.clear();
        saveRoot = null;
        DIRTY.clear();
        loadDepth = 0;
        dir = null;
        worldId = null;
    }

    public static synchronized String worldId() {
        return worldId;
    }

    private static String readOrCreateId(File f) {
        try {
            if (f.isFile()) {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
            String id = UUID.randomUUID().toString();
            Files.write(f.toPath(), id.getBytes(StandardCharsets.US_ASCII));
            return id;
        } catch (Throwable t) {
            System.out.println("[afterimage] world id file " + f + ": " + t);
            return "unsaved-" + UUID.randomUUID();
        }
    }

    // ---------------- recording ----------------

    public static void loadStart(World w) {
        if (w == null || w.field_72995_K) {
            return;
        }
        synchronized (ChangeTracker.class) {
            loadDepth++;
        }
    }

    public static void loadEnd(World w, int cx, int cz) {
        if (w == null || w.field_72995_K) {
            return;
        }
        synchronized (ChangeTracker.class) {
            if (loadDepth > 0) {
                loadDepth--;
            }
            if (dir != null) {
                map(LOADED_AT, dimension(w)).put(ChunkPos.func_77272_a(cx, cz), w.func_82737_E());
            }
        }
    }

    /** A block in chunk (cx, cz) of w changed or asked clients to re-render it. */
    public static void onChange(World w, int cx, int cz) {
        if (w == null || w.field_72995_K) {
            return;
        }
        synchronized (ChangeTracker.class) {
            if (dir == null) {
                return;
            }
            if (loadDepth > 0) {
                ignoredLoading++;
                return;
            }
            int dim = dimension(w);
            long key = ChunkPos.func_77272_a(cx, cz);
            long now = w.func_82737_E();
            Long2LongOpenHashMap loaded = map(LOADED_AT, dim);
            long at = loaded.get(key);
            if (at != Long.MIN_VALUE) {
                if (now >= at && now - at < GRACE_TICKS) {
                    ignoredGrace++;
                    return;
                }
                loaded.remove(key);
            }
            Long2LongOpenHashMap ch = map(CHANGES, dim);
            if (ch.get(key) == now) {
                return;
            }
            ch.put(key, now);
            map(PENDING, dim).put(key, now);
            DIRTY.add(dim);
            recorded++;
        }
    }

    /** Server tick start: no chunk load spans ticks, so a load interrupted by an exception cannot block tracking. */
    public static synchronized void tickStart() {
        loadDepth = 0;
    }

    /** Forget load times older than the grace period. */
    public static synchronized void prune(long now) {
        refreshSnapshot(now);
        for (Long2LongOpenHashMap m : LOADED_AT.values()) {
            for (LongIterator it = m.keySet().iterator(); it.hasNext();) {
                long k = it.nextLong();
                if (now - m.get(k) >= GRACE_TICKS) {
                    it.remove();
                }
            }
        }
    }

    // ---------------- queries ----------------

    /** (chunk key, tick) pairs of every change after the given tick. */
    public static synchronized long[] since(int dim, long since) {
        Long2LongOpenHashMap ch = CHANGES.get(dim);
        if (ch == null || ch.isEmpty()) {
            return new long[0];
        }
        long[] out = new long[ch.size() * 2];
        int n = 0;
        for (LongIterator it = ch.keySet().iterator(); it.hasNext();) {
            long k = it.nextLong();
            long t = ch.get(k);
            if (t > since) {
                out[n++] = k;
                out[n++] = t;
            }
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Changes since the last call, per dimension, as (chunk key, tick) pairs. */
    public static synchronized Map<Integer, long[]> drainPending() {
        Map<Integer, long[]> out = new HashMap<Integer, long[]>();
        for (Map.Entry<Integer, Long2LongOpenHashMap> e : PENDING.entrySet()) {
            Long2LongOpenHashMap m = e.getValue();
            if (m.isEmpty()) {
                continue;
            }
            long[] pairs = new long[m.size() * 2];
            int n = 0;
            for (LongIterator it = m.keySet().iterator(); it.hasNext();) {
                long k = it.nextLong();
                pairs[n++] = k;
                pairs[n++] = m.get(k);
            }
            m.clear();
            out.put(e.getKey(), pairs);
        }
        return out;
    }

    private static long total() {
        long n = 0;
        for (Long2LongOpenHashMap m : CHANGES.values()) {
            n += m.size();
        }
        return n;
    }

    public static synchronized String summary() {
        return "change tracking " + (dir == null ? "off" : "on, world " + worldId) + ": " + total() + " changed chunks on record, "
            + recorded + " changes this run, ignored while loading " + ignoredLoading + ", in load grace " + ignoredGrace;
    }

    // ---------------- persistence ----------------

    public static synchronized void save() {
        if (dir == null || DIRTY.isEmpty()) {
            return;
        }
        for (Integer dim : DIRTY) {
            Long2LongOpenHashMap m = CHANGES.get(dim);
            if (m == null) {
                continue;
            }
            File f = new File(dir, "changes-DIM" + dim + ".bin");
            File tmp = new File(dir, f.getName() + ".tmp");
            try {
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
                    out.writeInt(MAGIC);
                    out.writeInt(VERSION);
                    out.writeInt(m.size());
                    for (LongIterator it = m.keySet().iterator(); it.hasNext();) {
                        long k = it.nextLong();
                        out.writeLong(k);
                        out.writeLong(m.get(k));
                    }
                }
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable t) {
                System.out.println("[afterimage] could not save " + f + ": " + t);
            }
        }
        DIRTY.clear();
    }

    private static void load(File f, Long2LongOpenHashMap into) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IllegalStateException("unknown format");
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long k = in.readLong();
                long t = in.readLong();
                into.put(k, t);
            }
        }
    }
}
