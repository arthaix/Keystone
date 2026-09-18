package ru.arthaix.afterimage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * Afterimage phase 2: persistent far zone.
 *
 * Write: every section upload whose content fingerprint differs from what is on disk is copied (memcpy) and
 * written by a background thread as minecraft/afterimage/cache/<server>/DIM<n>/r.<rx>.<rz>/<cx>.<sy>.<cz>.L<layer>.aimg
 * (header + deflate). Writes go through a temp file and an atomic rename; a newer upload for the same section
 * supersedes a queued older one. A layer that stays empty for 30 s after vanilla compiled its loaded chunk is
 * deleted.
 * Load: on joining a world the cache of that server and dimension is scanned in the background, nearest sections
 * first, up to the far-zone VRAM budget, inflated off-thread and handed to the main thread, which uploads them
 * to GPU buffers for at most 4 ms per frame. Sections vanilla already shows, or that have a live in-memory copy,
 * are not taken from disk.
 * Texture layout: the vertices carry block atlas coordinates, so the cache is only valid for the atlas it was written
 * with. Its fingerprint (AtlasGuard) is kept in cache/atlas.txt; a cache without the current one is moved to
 * cache-stale-<time> and deleted in the background.
 */
public final class Disk {
    public static volatile boolean ENABLED = !"false".equals(System.getProperty("afterimage.disk"));

    private static final int MAGIC = 0x41494D47;
    /** 2 adds the geometry tick; version 1 files are still read (tick unknown). */
    private static final int VERSION = 2;
    private static final long DELETE_GRACE = 30_000_000_000L;
    private static final long WRITE_BACKLOG_MAX = 1L << 30;
    private static final long READY_MAX = 512L << 20;
    private static final long UPLOAD_BUDGET_NANOS = Long.getLong("afterimage.diskUploadMs", 4L) * 1_000_000L;

    private static File root;
    private static volatile File worldDir;
    private static volatile int generation;
    private static boolean scanPending;

    private static final String ATLAS_FILE = "atlas.txt";
    private static final String STALE_SUFFIX = "-stale-";
    /** Atlas fingerprint that arrived before init */
    private static volatile long pendingAtlas;
    /** While a cache made with another atlas layout is moved away: no scan starts. */
    private static volatile boolean discarding;
    private static final AtomicLong ATLAS_DISCARDS = new AtomicLong();

    private static final class Job {
        final long sk;
        final long key;
        final int layer;
        final ByteBuffer data;
        final int size;
        final long exact;
        final long ms;
        final File dir;
        final int gen;

        final long geomTime;

        Job(long sk, long key, int layer, ByteBuffer data, int size, long exact, long ms, long geomTime, File dir, int gen) {
            this.size = size;
            this.geomTime = geomTime;
            this.sk = sk;
            this.key = key;
            this.layer = layer;
            this.data = data;
            this.exact = exact;
            this.ms = ms;
            this.dir = dir;
            this.gen = gen;
        }
    }

    private static final class Loaded {
        final long key;
        final int layer;
        final int x;
        final int y;
        final int z;
        final ByteBuffer data;
        final int gen;

        final long geomTime;

        Loaded(long key, int layer, int x, int y, int z, ByteBuffer data, long geomTime, int gen) {
            this.geomTime = geomTime;
            this.key = key;
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
            this.gen = gen;
        }
    }

    private static final class Meta {
        final File file;
        final long key;
        final int layer;
        final int x;
        final int y;
        final int z;
        final int raw;
        final int comp;
        double dist2;

        final long geomTime;
        final int headerLen;

        Meta(File file, long key, int layer, int x, int y, int z, int raw, int comp, long geomTime, int headerLen) {
            this.geomTime = geomTime;
            this.headerLen = headerLen;
            this.file = file;
            this.key = key;
            this.layer = layer;
            this.x = x;
            this.y = y;
            this.z = z;
            this.raw = raw;
            this.comp = comp;
        }
    }

    /** section key * 4 + layer -> multiset fingerprint of what is on disk. */
    private static final ConcurrentHashMap<Long, Long> ONDISK = new ConcurrentHashMap<Long, Long>(65536);
    /** section key * 4 + layer -> client world tick of the geometry on disk (0 = unknown, version 1 file). */
    private static final ConcurrentHashMap<Long, Long> ONDISK_TIME = new ConcurrentHashMap<Long, Long>(65536);

    // ---- server sync state (client thread) ----
    private static final long SYNC_SAFETY_TICKS = 1200;
    private static volatile boolean awaitingServer;
    private static long awaitSince;
    private static long lastSync;
    private static long lastSyncWritten;
    private static long lastSyncWriteNanos;
    private static volatile String syncWorldId;
    private static final AtomicLong INVALIDATED_FILES = new AtomicLong();
    private static final AtomicLong OUTDATED_SKIPPED = new AtomicLong();
    private static final ConcurrentHashMap<Long, Job> LATEST = new ConcurrentHashMap<Long, Job>();
    private static final HashMap<Long, Long> PENDING_DELETE = new HashMap<Long, Long>();

    /** An upload waiting for vanilla to confirm that its RenderChunk still shows that position. */
    private static final class Held {
        final long key;
        final ByteBuffer data;
        final int size;
        final long exact;
        final long ms;
        final long geomTime;

        Held(long key, ByteBuffer data, int size, long exact, long ms, long geomTime) {
            this.key = key;
            this.data = data;
            this.size = size;
            this.exact = exact;
            this.ms = ms;
            this.geomTime = geomTime;
        }
    }

    /** RenderChunk -> uploads per layer not yet confirmed (client thread only) */
    private static final IdentityHashMap<RenderChunk, Held[]> HELD = new IdentityHashMap<RenderChunk, Held[]>();
    private static final long HELD_MAX = 256L << 20;
    private static long heldBytes;
    private static long heldCommitted;
    private static long heldDropped;
    private static final ConcurrentLinkedQueue<Loaded> READY = new ConcurrentLinkedQueue<Loaded>();

    private static final AtomicLong BACKLOG = new AtomicLong();
    private static final AtomicLong READY_BYTES = new AtomicLong();
    private static final AtomicLong WRITTEN_FILES = new AtomicLong();
    private static final AtomicLong WRITTEN_RAW = new AtomicLong();
    private static final AtomicLong WRITTEN_COMP = new AtomicLong();
    private static final AtomicLong DELETED = new AtomicLong();
    private static final AtomicLong SCANNED = new AtomicLong();
    private static final AtomicLong SCANNED_COMP = new AtomicLong();
    private static final AtomicLong LOADED_FILES = new AtomicLong();
    private static final AtomicLong LOADED_RAW = new AtomicLong();
    private static final AtomicLong OVER_BUDGET = new AtomicLong();
    private static final AtomicLong ERRORS = new AtomicLong();
    private static volatile String status = "idle";
    private static long uploadedFromDisk;
    private static long rejectedFromDisk;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterimage Disk Writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterimage Disk Loader");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });

    private Disk() {
    }

    public static void init(File cacheRoot) {
        root = cacheRoot;
        root.mkdirs();
        deleteStale();
        long pending = pendingAtlas;
        if (pending != 0L) {
            pendingAtlas = 0L;
            checkAtlas(pending);
        }
    }

    /**
     * Client thread, after every stitch of the block atlas (AtlasGuard). Cached vertices hold texture coordinates of the
     * atlas they were made with and show other textures once the layout differs, so a cache without the current
     * fingerprint is moved aside and deleted in the background, and the fingerprint is recorded for the new cache.
     */
    public static void checkAtlas(long signature) {
        if (root == null) {
            pendingAtlas = signature;
            return;
        }
        final String want = Long.toHexString(signature);
        final File marker = new File(root, ATLAS_FILE);
        if (want.equals(readText(marker))) {
            return;
        }
        discarding = true;
        generation++;
        ONDISK.clear();
        ONDISK_TIME.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        clearHeld();
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
            DirectPool.release(l.data);
        }
        final File cacheRoot = root;
        final File world = worldDir;
        WRITER.submit(() -> discardAll(cacheRoot, world, marker, want));
    }

    /** Writer thread: after the jobs of the old generation, before any of the new one. */
    private static void discardAll(File cacheRoot, File world, File marker, String want) {
        try {
            File[] kids = cacheRoot.listFiles();
            boolean content = false;
            if (kids != null) {
                for (File k : kids) {
                    if (!k.getName().startsWith(ATLAS_FILE)) {
                        content = true;
                        break;
                    }
                }
            }
            if (content) {
                File stale = new File(cacheRoot.getParentFile(), cacheRoot.getName() + STALE_SUFFIX + System.currentTimeMillis());
                boolean moved;
                try {
                    Files.move(cacheRoot.toPath(), stale.toPath());
                    moved = true;
                } catch (IOException e) {
                    // a file inside is still open (the loader of the old generation): delete in place
                    moved = false;
                }
                if (moved) {
                    cacheRoot.mkdirs();
                    deleteInBackground(stale);
                } else {
                    for (File k : kids) {
                        if (!k.getName().startsWith(ATLAS_FILE)) {
                            deleteTree(k);
                        }
                    }
                }
                ATLAS_DISCARDS.incrementAndGet();
                Capture.logInfo("disk: cache discarded, it was made with another block texture layout (now " + want + ")");
            }
            if (world != null) {
                world.mkdirs();
            }
            writeText(marker, want);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.atlas", t);
        } finally {
            discarding = false;
        }
    }

    /** Leftovers of a discard the game did not finish deleting. */
    private static void deleteStale() {
        File parent = root.getParentFile();
        File[] kids = parent == null ? null : parent.listFiles();
        if (kids == null) {
            return;
        }
        for (File k : kids) {
            if (k.isDirectory() && k.getName().startsWith(root.getName() + STALE_SUFFIX)) {
                deleteInBackground(k);
            }
        }
    }

    private static void deleteInBackground(final File f) {
        Thread t = new Thread(() -> deleteTree(f), "Afterimage Cache Cleanup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static String readText(File f) {
        try {
            return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeText(File f, String text) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    // ================= world lifecycle (main thread) =================

    public static void onWorld(WorldClient w) {
        flushSync();
        generation++;
        ONDISK.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        clearHeld();
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
            DirectPool.release(l.data);
        }
        scanPending = false;
        if (w == null || root == null) {
            worldDir = null;
            status = "no world";
            return;
        }
        ONDISK_TIME.clear();
        lastSync = 0L;
        lastSyncWritten = 0L;
        syncWorldId = null;
        if (ClientSync.serverHasAfterimage()) {
            // the server tracks chunk changes: wait for its world id and change list before touching the cache
            worldDir = null;
            awaitingServer = true;
            awaitSince = System.nanoTime();
            status = "waiting for server";
            return;
        }
        awaitingServer = false;
        File dir = new File(root, serverId() + File.separator + "DIM" + w.field_73011_w.func_186058_p().func_186068_a());
        dir.mkdirs();
        worldDir = dir;
        scanPending = true;
        status = "waiting for player";
    }

    /** Client thread, on the server's Hello: use the cache of that world and dimension; returns the sync point to ask for. */
    public static long beginServerWorld(String worldId, int dim) {
        flushSync();
        generation++;
        ONDISK.clear();
        ONDISK_TIME.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        clearHeld();
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
            DirectPool.release(l.data);
        }
        scanPending = false;
        if (root == null) {
            return 0L;
        }
        String id = worldId.replaceAll("[^A-Za-z0-9._-]", "_");
        File server = new File(root, serverId());
        File dir = new File(server, id + File.separator + "DIM" + dim);
        File legacy = new File(server, "DIM" + dim);
        if (!dir.exists() && legacy.isDirectory()) {
            // a cache made before the server had Afterimage belongs to the world the server runs now
            try {
                dir.getParentFile().mkdirs();
                Files.move(legacy.toPath(), dir.toPath());
                Capture.logInfo("disk: adopted the cache in " + legacy + " for world " + id);
            } catch (Throwable t) {
                Capture.logError("disk.adopt", t);
            }
        }
        dir.mkdirs();
        worldDir = dir;
        syncWorldId = id;
        lastSync = readSync(dir);
        lastSyncWritten = lastSync;
        awaitingServer = true;
        awaitSince = System.nanoTime();
        status = "syncing with server";
        return Math.max(0L, lastSync - SYNC_SAFETY_TICKS);
    }

    /** Client thread: the server's answer to Sync has been applied; the cache may be loaded now. */
    public static void serverSyncDone() {
        awaitingServer = false;
        if (worldDir != null) {
            scanPending = true;
            status = "waiting for player";
        }
    }

    /** Client thread: every server change up to serverTime has been applied. */
    public static void noteSync(long serverTime) {
        if (serverTime <= lastSync) {
            return;
        }
        lastSync = serverTime;
        if (System.nanoTime() - lastSyncWriteNanos > 10_000_000_000L) {
            flushSync();
        }
    }

    private static void flushSync() {
        final File dir = worldDir;
        final long value = lastSync;
        if (dir == null || syncWorldId == null || value <= lastSyncWritten) {
            return;
        }
        lastSyncWritten = value;
        lastSyncWriteNanos = System.nanoTime();
        // queued behind the deletions this sync point covers
        WRITER.submit(() -> writeSync(dir, value));
    }

    private static long readSync(File dir) {
        File f = new File(dir, "sync.txt");
        try {
            if (f.isFile()) {
                return Long.parseLong(new String(Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.US_ASCII).trim());
            }
        } catch (Throwable t) {
            Capture.logError("disk.readSync", t);
        }
        return 0L;
    }

    private static void writeSync(File dir, long value) {
        try {
            File tmp = new File(dir, "sync.txt.tmp");
            Files.write(tmp.toPath(), Long.toString(value).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Files.move(tmp.toPath(), new File(dir, "sync.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Capture.logError("disk.writeSync", t);
        }
    }

    private static long chunkKeyOf(long sectionKey) {
        BlockPos p = BlockPos.func_177969_a(sectionKey);
        return ChunkPos.func_77272_a(p.func_177958_n() >> 4, p.func_177952_p() >> 4);
    }

    /** Client thread: delete cached layers of chunk (cx, cz) whose geometry predates server tick t. */
    public static void invalidateChunk(int cx, int cz, long t) {
        final File dir = worldDir;
        if (dir == null) {
            return;
        }
        final int gen = generation;
        for (int sy = 0; sy < 16; sy++) {
            long key = new BlockPos(cx << 4, sy << 4, cz << 4).func_177986_g();
            for (int l = 0; l < 4; l++) {
                final long sk = key * 4 + l;
                Long dt = ONDISK_TIME.get(sk);
                if (dt == null || dt + Far.SYNC_TOLERANCE_TICKS >= t) {
                    continue;
                }
                Job q = LATEST.get(sk);
                if (q != null && q.geomTime + Far.SYNC_TOLERANCE_TICKS >= t) {
                    continue;
                }
                PENDING_DELETE.remove(sk);
                ONDISK_TIME.remove(sk);
                INVALIDATED_FILES.incrementAndGet();
                final long tt = t;
                WRITER.submit(() -> deleteIfOlder(dir, gen, sk, tt));
            }
        }
    }

    private static void deleteIfOlder(File dir, int gen, long sk, long t) {
        try {
            if (dir == null || gen != generation || LATEST.containsKey(sk)) {
                return;
            }
            File f = fileFor(dir, sk >> 2, (int) (sk & 3));
            Meta m = f.isFile() ? readHeader(f) : null;
            if (m != null && m.geomTime + Far.SYNC_TOLERANCE_TICKS < t && f.delete()) {
                DELETED.incrementAndGet();
                ONDISK.remove(sk);
                ONDISK_TIME.remove(sk);
            }
        } catch (Throwable e) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.invalidate", e);
        }
    }

    public static void tick(long now) {
        if (awaitingServer && now - awaitSince > 20_000_000_000L) {
            awaitingServer = false;
            Minecraft mcw = Minecraft.func_71410_x();
            if (worldDir == null && mcw.field_71441_e != null && root != null) {
                File dir = new File(root, serverId() + File.separator + "DIM" + mcw.field_71441_e.field_73011_w.func_186058_p().func_186068_a());
                dir.mkdirs();
                worldDir = dir;
                Capture.logInfo("disk: no answer from the server's Afterimage within 20 s, using the local cache");
            }
            if (worldDir != null) {
                scanPending = true;
                status = "waiting for player";
            }
        }
        if (lastSync > lastSyncWritten && now - lastSyncWriteNanos > 10_000_000_000L) {
            flushSync();
        }
        if (!ENABLED || worldDir == null) {
            return;
        }
        Minecraft mc = Minecraft.func_71410_x();
        if (scanPending && !discarding && mc.field_71439_g != null) {
            scanPending = false;
            EntityPlayer p = mc.field_71439_g;
            final double px = p.field_70165_t;
            final double py = p.field_70163_u;
            final double pz = p.field_70161_v;
            final int gen = generation;
            final File dir = worldDir;
            final long budget = Far.budget();
            final Long2LongOpenHashMap changes = Far.changesSnapshot();
            LOADER.submit(() -> loadAll(dir, gen, px, py, pz, budget, changes));
        }
        if (!PENDING_DELETE.isEmpty()) {
            for (Iterator<Map.Entry<Long, Long>> it = PENDING_DELETE.entrySet().iterator(); it.hasNext();) {
                Map.Entry<Long, Long> e = it.next();
                if (now < e.getValue()) {
                    continue;
                }
                it.remove();
                final long sk = e.getKey();
                final File dir = worldDir;
                final int gen = generation;
                WRITER.submit(() -> deleteFile(dir, gen, sk));
            }
        }
    }

    // ================= write side =================

    /** Main thread, from Capture.onUpload. layer 0..2, 28-byte vertex format. */
    /**
     * Client thread, from Capture.onUpload. An upload is written only when its RenderChunk vouches for it at its current
     * position: it already has a compiled chunk there (a rebuild in place, a translucent resort, a LittleTiles re-upload),
     * or vanilla later sets a compiled chunk with that layer for the same position. Vanilla queues uploads for the client
     * thread; one queued before the RenderChunk moved arrives under the new position with the old position's geometry.
     * Such uploads are held and dropped when the RenderChunk moves again, frees its buffers, or compiles without that layer.
     */
    public static void onSectionUpload(RenderChunk rc, long key, int layer, ByteBuffer buf, int size, long exact, long ms, long geomTime) {
        CompiledChunk cc = rc.func_178571_g();
        // A RenderChunk that has a compiled chunk still shows the position it was built for, so its uploads belong there.
        // The layer flag is not checked: LittleTiles uploads its tiles into a layer first and marks the layer used after.
        if (cc != null && cc != CompiledChunk.field_178502_a) {
            commitUpload(key, layer, buf, null, size, exact, ms, geomTime);
            return;
        }
        if (!ENABLED || worldDir == null || size <= 0 || heldBytes + size > HELD_MAX) {
            return;
        }
        Held[] held = HELD.get(rc);
        if (held == null) {
            held = new Held[4];
            HELD.put(rc, held);
        }
        if (held[layer] != null) {
            heldBytes -= held[layer].size;
            DirectPool.release(held[layer].data);
        }
        held[layer] = new Held(key, DirectPool.copyOf(buf, size), size, exact, ms, geomTime);
        heldBytes += size;
    }

    /** RenderChunk.setCompiledChunk HEAD: vanilla confirmed what this RenderChunk shows at its position. */
    public static void onCompiled(RenderChunk rc, CompiledChunk next) {
        if (!Capture.onMainThread()) {
            return;
        }
        Held[] held = HELD.remove(rc);
        if (held == null) {
            return;
        }
        long key = rc.func_178568_j().func_177986_g();
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        for (int l = 0; l < held.length; l++) {
            Held h = held[l];
            if (h == null) {
                continue;
            }
            heldBytes -= h.size;
            if (next != null && next != CompiledChunk.field_178502_a && h.key == key && !next.func_178491_b(layers[l])) {
                commitUpload(h.key, l, null, h.data, h.size, h.exact, h.ms, h.geomTime);
                heldCommitted++;
            } else {
                DirectPool.release(h.data);
                heldDropped++;
            }
        }
    }

    /** Client thread: forget every held upload and give its copy back. */
    private static void clearHeld() {
        for (Held[] held : HELD.values()) {
            for (Held h : held) {
                if (h != null) {
                    DirectPool.release(h.data);
                }
            }
        }
        HELD.clear();
        heldBytes = 0L;
    }

    /** RenderChunk.setPosition / deleteGlResources HEAD: uploads held for it belong nowhere now. */
    public static void dropHeld(RenderChunk rc) {
        if (!Capture.onMainThread()) {
            return;
        }
        Held[] held = HELD.remove(rc);
        if (held == null) {
            return;
        }
        for (Held h : held) {
            if (h != null) {
                heldBytes -= h.size;
                DirectPool.release(h.data);
                heldDropped++;
            }
        }
    }

    /** heldData, when given, is owned by this call: it is either queued for writing or released. */
    private static void commitUpload(long key, int layer, ByteBuffer buf, ByteBuffer heldData, int size, long exact, long ms, long geomTime) {
        File dir = worldDir;
        if (!ENABLED || dir == null || size <= 0) {
            DirectPool.release(heldData);
            return;
        }
        long sk = key * 4 + layer;
        PENDING_DELETE.remove(sk);
        Job queued = LATEST.get(sk);
        if (queued != null) {
            if (queued.ms == ms) {
                DirectPool.release(heldData);
                return;
            }
        } else {
            Long d = ONDISK.get(sk);
            if (d != null && d == ms) {
                Long dt = ONDISK_TIME.get(sk);
                if (dt == null || !Far.outdated(key, dt)) {
                    DirectPool.release(heldData);
                    return;
                }
                // same bytes, but the file's tick predates a server change: rewrite it with the current tick
            }
        }
        if (BACKLOG.get() + size > WRITE_BACKLOG_MAX) {
            DirectPool.release(heldData);
            return;
        }
        ByteBuffer data = heldData != null ? heldData : DirectPool.copyOf(buf, size);
        final Job job = new Job(sk, key, layer, data, size, exact, ms, geomTime, dir, generation);
        LATEST.put(sk, job);
        BACKLOG.addAndGet(size);
        WRITER.submit(() -> write(job));
    }

    /** Multiset fingerprint of the layer as stored on disk, 0 when not on disk. */
    public static long diskHash(long key, int layer) {
        Long v = ONDISK.get(key * 4 + layer);
        return v == null ? 0L : v;
    }

    /** Main thread, from Far.onCompiledReplace when the chunk is loaded and vanilla compiled the section. */
    public static void onVanillaCompiled(long key, CompiledChunk next) {
        if (!ENABLED || worldDir == null || next == null) {
            return;
        }
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        long due = System.nanoTime() + DELETE_GRACE;
        for (int l = 0; l < 4; l++) {
            long sk = key * 4 + l;
            if (next.func_178491_b(layers[l]) && ONDISK.containsKey(sk)) {
                PENDING_DELETE.put(sk, due);
            }
        }
    }

    private static File fileFor(File dir, long key, int layer) {
        BlockPos p = BlockPos.func_177969_a(key);
        int cx = p.func_177958_n() >> 4;
        int sy = p.func_177956_o() >> 4;
        int cz = p.func_177952_p() >> 4;
        return new File(dir, "r." + (cx >> 5) + "." + (cz >> 5) + File.separator + cx + "." + sy + "." + cz + ".L" + layer + ".aimg");
    }

    // writer thread only: long-lived scratch arrays instead of a heap copy per file
    private static byte[] writeIn = new byte[1 << 20];
    private static byte[] writeOut = new byte[1 << 20];
    private static final byte[] WRITE_HEADER = new byte[64];
    private static final Deflater DEFLATER = new Deflater(1);

    private static void write(Job j) {
        try {
            if (LATEST.get(j.sk) != j || j.gen != generation) {
                return;
            }
            File f = fileFor(j.dir, j.key, j.layer);
            f.getParentFile().mkdirs();
            int n = j.size;
            if (writeIn.length < n) {
                writeIn = new byte[n + (n >> 2)];
            }
            ByteBuffer src = j.data.duplicate();
            src.clear();
            src.limit(n);
            src.get(writeIn, 0, n);
            DEFLATER.reset();
            DEFLATER.setInput(writeIn, 0, n);
            DEFLATER.finish();
            int outLen = 0;
            while (!DEFLATER.finished()) {
                if (writeOut.length - outLen < (1 << 16)) {
                    writeOut = Arrays.copyOf(writeOut, writeOut.length * 2);
                }
                outLen += DEFLATER.deflate(writeOut, outLen, 1 << 16);
            }
            BlockPos p = BlockPos.func_177969_a(j.key);
            ByteBuffer h = ByteBuffer.wrap(WRITE_HEADER);
            h.putInt(MAGIC).putInt(VERSION).putInt(j.layer).putInt(p.func_177958_n()).putInt(p.func_177956_o()).putInt(p.func_177952_p())
                .putInt(VertexLayout.VANILLA).putInt(n).putLong(j.exact).putLong(j.ms).putLong(j.geomTime).putInt(outLen);
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(WRITE_HEADER, 0, h.position());
                out.write(writeOut, 0, outLen);
            }
            try {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicFailed) {
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            ONDISK.put(j.sk, j.ms);
            ONDISK_TIME.put(j.sk, j.geomTime);
            WRITTEN_FILES.incrementAndGet();
            WRITTEN_RAW.addAndGet(n);
            WRITTEN_COMP.addAndGet(outLen);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.write", t);
        } finally {
            LATEST.remove(j.sk, j);
            BACKLOG.addAndGet(-j.size);
            DirectPool.release(j.data);
        }
    }

    private static void deleteFile(File dir, int gen, long sk) {
        try {
            if (dir == null || gen != generation || LATEST.containsKey(sk)) {
                return;
            }
            File f = fileFor(dir, sk >> 2, (int) (sk & 3));
            if (f.delete()) {
                DELETED.incrementAndGet();
            }
            ONDISK.remove(sk);
            ONDISK_TIME.remove(sk);
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            Capture.logError("disk.delete", t);
        }
    }

    /** A shader pack was switched on or off: forget what was found for the old layout and scan the cache again. */
    public static void rescan() {
        generation++;
        ONDISK.clear();
        ONDISK_TIME.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        clearHeld();
        scanPending = worldDir != null;
    }

    public static void clearWorld() {
        final File dir = worldDir;
        if (dir == null) {
            return;
        }
        generation++;
        ONDISK.clear();
        LATEST.clear();
        PENDING_DELETE.clear();
        clearHeld();
        WRITER.submit(() -> {
            deleteTree(dir);
            dir.mkdirs();
        });
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        f.delete();
    }

    // ================= load side =================

    private static void loadAll(File dir, int gen, double px, double py, double pz, long budget, Long2LongOpenHashMap changes) {
        try {
            status = "scanning";
            ArrayList<Meta> metas = new ArrayList<Meta>();
            File[] regions = dir.listFiles();
            if (regions != null) {
                for (File r : regions) {
                    if (gen != generation) {
                        return;
                    }
                    if (!r.isDirectory() || !r.getName().startsWith("r.")) {
                        continue;
                    }
                    File[] files = r.listFiles();
                    if (files == null) {
                        continue;
                    }
                    for (File f : files) {
                        String name = f.getName();
                        if (!name.endsWith(".aimg")) {
                            if (name.endsWith(".tmp")) {
                                f.delete();
                            }
                            continue;
                        }
                        Meta m = readHeader(f);
                        if (m == null) {
                            continue;
                        }
                        long ct = changes.get(chunkKeyOf(m.key));
                        if (ct != 0L && ct > m.geomTime + Far.SYNC_TOLERANCE_TICKS) {
                            // the server changed this chunk after the geometry was made
                            final long sk = m.key * 4 + m.layer;
                            WRITER.submit(() -> deleteIfOlder(dir, gen, sk, ct));
                            INVALIDATED_FILES.incrementAndGet();
                            continue;
                        }
                        metas.add(m);
                        ONDISK.put(m.key * 4 + m.layer, readHash(f));
                        ONDISK_TIME.put(m.key * 4 + m.layer, m.geomTime);
                        SCANNED.incrementAndGet();
                        SCANNED_COMP.addAndGet(m.comp);
                    }
                }
            }
            pruneOverCap(metas);
            for (Meta m : metas) {
                double dx = m.x + 8 - px;
                double dy = m.y + 8 - py;
                double dz = m.z + 8 - pz;
                m.dist2 = dx * dx + dy * dy + dz * dz;
            }
            Collections.sort(metas, (a, b) -> Double.compare(a.dist2, b.dist2));
            status = "loading " + metas.size() + " files";
            long used = 0;
            for (Meta m : metas) {
                if (gen != generation) {
                    return;
                }
                if (used + m.raw > budget) {
                    OVER_BUDGET.incrementAndGet();
                    continue;
                }
                while (READY_BYTES.get() > READY_MAX) {
                    if (gen != generation) {
                        return;
                    }
                    Thread.sleep(20);
                }
                ByteBuffer data = readBody(m);
                if (data == null) {
                    // the writer may be replacing this very file (Windows refuses to open a file mid-move): retry once
                    Thread.sleep(100);
                    data = readBody(m);
                }
                if (data == null) {
                    continue;
                }
                READY.add(new Loaded(m.key, m.layer, m.x, m.y, m.z, data, m.geomTime, gen));
                READY_BYTES.addAndGet(data.capacity());
                used += m.raw;
                LOADED_FILES.incrementAndGet();
                LOADED_RAW.addAndGet(m.raw);
            }
            status = "loaded";
        } catch (Throwable t) {
            ERRORS.incrementAndGet();
            status = "load error";
            Capture.logError("disk.load", t);
        }
    }

    /** The cache has no natural bound: every section ever seen stays. Above -Dafterimage.diskCapMB (8192) the files
     * least recently written go, down to nine tenths of the cap, before loading starts (loader thread, at join). */
    private static final long DISK_CAP = Long.getLong("afterimage.diskCapMB", 8192L) << 20;
    private static final java.util.concurrent.atomic.AtomicLong PRUNED_FILES = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong PRUNED_BYTES = new java.util.concurrent.atomic.AtomicLong();

    private static void pruneOverCap(List<Meta> metas) {
        if (DISK_CAP <= 0) {
            return;
        }
        long total = 0L;
        for (Meta m : metas) {
            total += m.file.length();
        }
        if (total <= DISK_CAP) {
            return;
        }
        List<Meta> byAge = new ArrayList<Meta>(metas);
        Collections.sort(byAge, (a, b) -> Long.compare(a.file.lastModified(), b.file.lastModified()));
        long target = DISK_CAP - DISK_CAP / 10;
        java.util.Set<Meta> gone = new java.util.HashSet<Meta>();
        for (Meta m : byAge) {
            if (total <= target) {
                break;
            }
            long len = m.file.length();
            long sk = m.key * 4 + m.layer;
            ONDISK.remove(sk);
            ONDISK_TIME.remove(sk);
            if (m.file.delete()) {
                total -= len;
                gone.add(m);
                PRUNED_FILES.incrementAndGet();
                PRUNED_BYTES.addAndGet(len);
            }
        }
        metas.removeAll(gone);
    }

    private static final java.util.concurrent.atomic.AtomicInteger READ_ERRORS_LOGGED = new java.util.concurrent.atomic.AtomicInteger();

    private static void readError(String what, File f, Throwable t) {
        ERRORS.incrementAndGet();
        if (READ_ERRORS_LOGGED.incrementAndGet() <= 20) {
            Capture.logError("disk." + what + " " + f, t != null ? t : new IllegalStateException("bad data"));
        }
    }

    private static Meta readHeader(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 64))) {
            if (in.readInt() != MAGIC) {
                return null;
            }
            int version = in.readInt();
            if (version != 1 && version != 2) {
                return null;
            }
            int layer = in.readInt();
            int x = in.readInt();
            int y = in.readInt();
            int z = in.readInt();
            int vs = in.readInt();
            int raw = in.readInt();
            in.readLong();
            in.readLong();
            long geomTime = version >= 2 ? in.readLong() : 0L;
            int comp = in.readInt();
            if (vs != VertexLayout.VANILLA || layer < 0 || layer > 3 || raw <= 0
                    || raw % (VertexLayout.VANILLA * 4) != 0 || comp <= 0) {
                return null;
            }
            return new Meta(f, new BlockPos(x, y, z).func_177986_g(), layer, x, y, z, raw, comp, geomTime, version >= 2 ? 60 : 52);
        } catch (Throwable t) {
            readError("header", f, t);
            return null;
        }
    }

    private static long readHash(File f) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 64))) {
            in.skipBytes(4 * 8 + 8);
            return in.readLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    // loader thread only
    private static byte[] readComp = new byte[1 << 20];
    private static byte[] readRaw = new byte[1 << 20];
    private static final Inflater INFLATER = new Inflater();

    private static final byte[] READ_HEADER = new byte[64];

    /**
     * Loader thread. Sizes come from the header of the file as opened here: the writer may have replaced the file with
     * a newer version since the scan read its header, and the scanned sizes then no longer match the body.
     */
    /** A file whose body cannot be read is deleted and forgotten; the next upload of that section writes it anew. */
    private static void discardCorrupt(Meta m, String why) {
        readError(why, m.file, null);
        try {
            long sk = m.key * 4 + m.layer;
            ONDISK.remove(sk);
            ONDISK_TIME.remove(sk);
            if (m.file.delete()) {
                CORRUPT_DELETED.incrementAndGet();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong CORRUPT_DELETED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SUPERSEDED = new java.util.concurrent.atomic.AtomicLong();

    private static ByteBuffer readBody(Meta m) {
        try (FileInputStream in = new FileInputStream(m.file)) {
            if (!readFully(in, READ_HEADER, m.headerLen)) {
                discardCorrupt(m, "short header");
                return null;
            }
            ByteBuffer h = ByteBuffer.wrap(READ_HEADER, 0, m.headerLen);
            if (h.getInt() != MAGIC || h.getInt(4) != (m.headerLen == 60 ? 2 : 1) || h.getInt(8) != m.layer
                    || h.getInt(24) != VertexLayout.VANILLA) {
                readError("header changed", m.file, null);
                return null;
            }
            int raw = h.getInt(28);
            int comp = h.getInt(m.headerLen - 4);
            if (raw <= 0 || raw % (VertexLayout.VANILLA * 4) != 0 || comp <= 0) {
                discardCorrupt(m, "bad sizes " + raw + "/" + comp);
                return null;
            }
            if (readComp.length < comp) {
                readComp = new byte[comp + (comp >> 2)];
            }
            if (!readFully(in, readComp, comp)) {
                discardCorrupt(m, "short body");
                return null;
            }
            if (readRaw.length < raw) {
                readRaw = new byte[raw + (raw >> 2)];
            }
            INFLATER.reset();
            INFLATER.setInput(readComp, 0, comp);
            int off = 0;
            while (off < raw && !INFLATER.finished()) {
                int k = INFLATER.inflate(readRaw, off, Math.min(1 << 20, raw - off));
                if (k == 0 && (INFLATER.needsInput() || INFLATER.needsDictionary())) {
                    break;
                }
                off += k;
            }
            if (off != raw) {
                discardCorrupt(m, "inflate " + off + "/" + raw);
                return null;
            }
            ByteBuffer bb = DirectPool.acquire(raw);
            bb.put(readRaw, 0, raw);
            bb.flip();
            return bb;
        } catch (java.io.FileNotFoundException e) {
            // deleted or replaced by the writer since the scan (an invalidation, a newer upload): nothing to report
            SUPERSEDED.incrementAndGet();
            return null;
        } catch (java.util.zip.DataFormatException e) {
            discardCorrupt(m, "corrupt body");
            return null;
        } catch (Throwable t) {
            readError("body", m.file, t);
            return null;
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int k = in.read(b, got, len - got);
            if (k < 0) {
                return false;
            }
            got += k;
        }
        return true;
    }

    /** Main thread, from Far.render before drawing: GPU uploads of loaded sections, time-budgeted. */
    public static void pump(ViewFrustum frustum) {
        if (READY.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        Loaded l;
        while ((l = READY.poll()) != null) {
            READY_BYTES.addAndGet(-l.data.capacity());
            try {
                if (l.gen != generation) {
                    continue;
                }
                if (Far.outdated(l.key, l.geomTime)) {
                    OUTDATED_SKIPPED.incrementAndGet();
                    continue;
                }
                if (Far.acceptFromDisk(l.key, l.layer, l.x, l.y, l.z, frustum)) {
                    Far.uploadLayer(l.key, l.x, l.y, l.z, l.layer, l.data, l.geomTime);
                    uploadedFromDisk++;
                } else {
                    rejectedFromDisk++;
                }
            } finally {
                DirectPool.release(l.data);
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    // ================= misc =================

    private static String serverId() {
        Minecraft mc = Minecraft.func_71410_x();
        String id;
        ServerData sd = mc.func_147104_D();
        if (sd != null && sd.field_78845_b != null) {
            id = sd.field_78845_b;
        } else if (mc.func_71401_C() != null) {
            id = "sp_" + mc.func_71401_C().func_71270_I();
        } else {
            id = "unknown";
        }
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public static String summary() {
        return "disk " + (ENABLED ? "ON" : "OFF") + " [" + status + (syncWorldId != null ? ", world " + syncWorldId + " synced to tick " + lastSync : "") + "]: on disk " + SCANNED.get() + " files ("
            + String.format("%.0f MB", SCANNED_COMP.get() / 1048576.0) + " at join), written " + WRITTEN_FILES.get() + " ("
            + String.format("%.0f MB -> %.0f MB", WRITTEN_RAW.get() / 1048576.0, WRITTEN_COMP.get() / 1048576.0)
            + "), deleted " + DELETED.get() + ", loaded " + LOADED_FILES.get() + " ("
            + String.format("%.0f MB", LOADED_RAW.get() / 1048576.0) + "), to GPU " + uploadedFromDisk + ", skipped "
            + rejectedFromDisk + ", over budget " + OVER_BUDGET.get() + ", backlog "
            + String.format("%.0f MB", BACKLOG.get() / 1048576.0) + ", uploads confirmed later " + heldCommitted + ", dropped as not owned " + heldDropped + ", invalidated by server " + INVALIDATED_FILES.get() + ", outdated skipped " + OUTDATED_SKIPPED.get() + ", superseded " + SUPERSEDED.get() + ", corrupt deleted " + CORRUPT_DELETED.get() + ", pruned " + PRUNED_FILES.get() + " (" + (PRUNED_BYTES.get() >> 20) + " MB)" + ", discarded for texture layout " + ATLAS_DISCARDS.get() + ", errors " + ERRORS.get();
    }
}
