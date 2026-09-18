package ru.arthaix.afterimage;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Afterimage phase 0: capture, verify, measure. Changes nothing on screen.
 *
 * Capture: every section VBO upload is fingerprinted (exact + quad multiset) and recorded
 * per section and layer. Verify: every few seconds one quiet section is read back from the
 * GPU, forced to rebuild through the normal path, read back again after it settles, and the
 * two are compared. Discarded when the world marks the section dirty in between. Measure:
 * live VRAM held by section geometry, unique meshes, per-section sizes to afterimage/*.csv.
 *
 * All entry points are main-thread except onUpload's thread check; any throwable disables
 * the whole thing and is written to afterimage/errors.log.
 */
public final class Capture {
    public static volatile boolean ENABLED = !"false".equals(System.getProperty("afterimage.enabled"));
    public static boolean selfMark;
    /** Phase 0 verifier and raw samples: forced rebuilds and GPU readbacks, for development only. */
    private static final boolean VERIFY = Boolean.getBoolean("afterimage.verify");

    private static final int LAYERS = 4;
    private static final long SEC = 1_000_000_000L;
    private static final long QUIET = 5 * SEC;
    private static final long VERIFY_EVERY = 2 * SEC;
    private static final long VERIFY_TIMEOUT = 45 * SEC;
    private static final long REVERIFY_AFTER = 600 * SEC;
    private static final long SUMMARY_EVERY = 60 * SEC;
    private static final long CSV_EVERY = 900 * SEC;
    private static final int MAX_READBACK = 16 * 1024 * 1024;
    private static final int MAX_SECTIONS = 400_000;
    private static final int MAX_DUMPS = 25;

    private static File dir;
    private static Thread mainThread;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Afterimage IO");
        t.setDaemon(true);
        return t;
    });

    // ---- capture state ----
    private static final class Rec {
        final long key;
        WeakReference<RenderChunk> owner;
        final int[] size = new int[LAYERS];
        final long[] exact = new long[LAYERS];
        final long[] ms = new long[LAYERS];
        long combined;
        long lastNonTranslUpload;
        long lastContentTranslUpload;
        int nonTranslUploads;
        long lastVerified;

        Rec(long key) {
            this.key = key;
        }

        int bytes() {
            return size[0] + size[1] + size[2] + size[3];
        }

        int nonTranslBytes() {
            return size[0] + size[1] + size[2];
        }
    }

    private static final HashMap<Long, Rec> SECTIONS = new HashMap<Long, Rec>(65536);
    private static final ArrayList<Long> ORDER = new ArrayList<Long>(65536);
    private static final HashMap<Long, Integer> UNIQUE = new HashMap<Long, Integer>(65536);

    private static long vboCreated, vboDeleted, uploads, uploadBytes, unownedUploads, offThreadUploads, hashNanos;
    private static final long[] LIVE = new long[LAYERS];
    /** bytes in every VertexBuffer that has been uploaded and not deleted, tracked sections or not (VRAM attribution) */
    private static long allLive;

    // ---- fingerprints computed on chunk workers ----
    /** Section uploads are fingerprinted by the chunk worker that finished the buffer (-Dafterimage.workerHash=false: client thread). */
    private static volatile boolean WORKER_HASH = !"false".equals(System.getProperty("afterimage.workerHash"));
    /** The first 16 and then every n-th worker fingerprint are recomputed on the client thread; one difference turns it off. */
    private static final int WORKER_CHECK_EVERY = Integer.getInteger("afterimage.workerHashCheck", 64);
    private static final AtomicLong WORKER_HASHED = new AtomicLong();
    private static final AtomicLong WORKER_NANOS = new AtomicLong();
    private static BufferBuilder uploadingBuilder;
    private static long mainHashed, workerUsed, workerStale, workerChecked, workerMismatch;

    // ---- verifier state ----
    private static final class Snap {
        int size;
        long exact;
        long ms;
        ByteBuffer bytes;
    }

    private static int vState; // 0 idle, 1 waiting for rebuild
    private static int scanIndex;
    private static boolean pickLargest;
    private static long lastVerifyStart;
    private static long candKey;
    private static WeakReference<RenderChunk> candRef;
    private static Snap[] s1;
    private static int gen1;
    private static int uploads1;
    private static long t0;

    private static long vDone, vExact, vReordered, vDifferent, vSize, vDiscardWorld, vDiscardMoved, vTimeout, vEmpty;
    private static long capAgree, capDisagree, dumps;
    private static final long[] LAYER_BAD = new long[LAYERS];

    private static long lastSummary, lastCsv, errors;
    private static Object lastWorld;

    private Capture() {
    }

    // ================= hooks =================

    public static void init(File gameDir) {
        dir = new File(gameDir, "afterimage");
        dir.mkdirs();
        appendLine("summary.log", "=== session start " + stamp() + " enabled=" + ENABLED + " ===");
        Disk.init(new File(dir, "cache"));
    }

    public static void onVboCreated() {
        vboCreated++;
    }

    public static void bindOwner(RenderChunk rc, int layer, VertexBuffer vb) {
        if (vb == null || layer < 0 || layer >= LAYERS) {
            return;
        }
        IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
        if (v.afterimage$owner() != rc) {
            v.afterimage$setOwner(rc, layer);
        }
    }

    public static void onDelete(VertexBuffer vb) {
        if (!ENABLED) {
            return;
        }
        try {
            vboDeleted++;
            IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
            int layer = v.afterimage$layer();
            if (v.afterimage$owner() != null && layer >= 0 && layer < LAYERS) {
                LIVE[layer] -= v.afterimage$lastSize();
            }
            allLive -= v.afterimage$lastSize();
            v.afterimage$setLastSize(0);
        } catch (Throwable t) {
            fail("onDelete", t);
        }
    }

    /** Chunk worker, from ChunkRenderDispatcher.uploadChunk just before the upload is queued for the client thread. */
    public static void workerHash(BlockRenderLayer layer, BufferBuilder buffer) {
        if (!ENABLED || !WORKER_HASH || buffer == null || layer == null || layer.ordinal() >= LAYERS) {
            return;
        }
        try {
            VertexFormat format = buffer.func_178973_g();
            ByteBuffer bytes = buffer.func_178966_f();
            if (format == null || bytes == null || format.func_177338_f() != 28 || !bytes.hasRemaining()) {
                return;
            }
            long t = System.nanoTime();
            long[] h = Hash.of(bytes, 28);
            WORKER_NANOS.addAndGet(System.nanoTime() - t);
            WORKER_HASHED.incrementAndGet();
            ((IAfterimageBufferBuilder) buffer).afterimage$setPreHash(new long[] {bytes.remaining(), h[0], h[1]});
        } catch (Throwable t) {
            WORKER_HASH = false;
        }
    }

    /** Client thread: VertexBufferUploader.draw HEAD (the builder being uploaded) and RETURN (null). */
    public static void uploading(BufferBuilder buffer) {
        uploadingBuilder = buffer;
    }

    /** The chunk worker's fingerprint of exactly these bytes, or null when there is none or it may not match them. */
    private static long[] takeWorkerHash(ByteBuffer buf, int size, int vs) {
        BufferBuilder b = uploadingBuilder;
        if (b == null) {
            return null;
        }
        long[] pre = ((IAfterimageBufferBuilder) b).afterimage$takePreHash();
        if (pre == null) {
            return null;
        }
        if (!WORKER_HASH || vs != 28 || pre[0] != size || b.func_178966_f() != buf) {
            workerStale++;
            return null;
        }
        long[] h = new long[] {pre[1], pre[2]};
        workerUsed++;
        if (WORKER_CHECK_EVERY > 0 && (workerUsed <= 16 || workerUsed % WORKER_CHECK_EVERY == 0)) {
            workerChecked++;
            long[] m = Hash.of(buf, vs);
            if (m[0] != h[0] || m[1] != h[1]) {
                workerMismatch++;
                WORKER_HASH = false;
                logInfo("a chunk worker's fingerprint differed from the uploaded bytes; fingerprinting on the client thread from now on");
                return m;
            }
        }
        return h;
    }

    /** Far kept this VertexBuffer's GL buffer as a copy; the VertexBuffer holds a new, empty one now. */
    public static void onStolen(VertexBuffer vb) {
        IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
        int layer = v.afterimage$layer();
        if (v.afterimage$owner() != null && layer >= 0 && layer < LAYERS) {
            LIVE[layer] -= v.afterimage$lastSize();
        }
        v.afterimage$setLastSize(0);
    }

    public static long allLiveMb() {
        return allLive >> 20;
    }

    public static long liveMb() {
        long t = 0L;
        for (long l : LIVE) {
            t += l;
        }
        return t >> 20;
    }

    public static void onUpload(VertexBuffer vb, ByteBuffer buf) {
        if (!ENABLED || buf == null) {
            return;
        }
        try {
            if (mainThread != null && Thread.currentThread() != mainThread) {
                offThreadUploads++;
                return;
            }
            IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
            int size = buf.remaining();
            uploads++;
            uploadBytes += size;
            ru.arthaix.keystone.ltfix.GpuTrace.upload(size);
            int prev = v.afterimage$lastSize();
            v.afterimage$setLastSize(size);
            allLive += size - prev;
            RenderChunk rc = v.afterimage$owner();
            int layer = v.afterimage$layer();
            if (rc == null || layer < 0 || layer >= LAYERS) {
                unownedUploads++;
                return;
            }
            LIVE[layer] += size - prev;

            long t = System.nanoTime();
            int vs = v.afterimage$vertexSize();
            // With a shader pack loaded the game builds wider vertices (OptiFine's layout). The fingerprint and the disk
            // cache use the vanilla part of them, so both stay the same whether a pack is loaded or not.
            ByteBuffer plain = null;
            int plainSize = 0;
            if (vs == VertexLayout.VANILLA) {
                plain = buf;
                plainSize = size;
            } else if (vs > VertexLayout.VANILLA && size % vs == 0) {
                plain = vanillaPart(buf, size, vs);
                plainSize = plain == null ? 0 : plain.remaining();
            }
            long[] h = takeWorkerHash(buf, size, vs);
            long now = System.nanoTime();
            if (h == null) {
                h = plain == null ? new long[] {0L, 0L} : Hash.of(plain, VertexLayout.VANILLA);
                now = System.nanoTime();
                hashNanos += now - t;
                mainHashed++;
            }

            long key = rc.func_178568_j().func_177986_g();
            if (VERIFY) {
                sample(vb, buf, key, layer, size, vs, now);
            }
            if (layer < 4 && plain != null && plainSize > 0) {
                net.minecraft.client.multiplayer.WorldClient cw = Minecraft.func_71410_x().field_71441_e;
                long wt = cw == null ? 0L : cw.func_82737_E();
                Far.onUpload(key, layer, h[1], wt);
                Disk.onSectionUpload(rc, key, layer, plain, plainSize, h[0], h[1], wt);
            }
            Rec r = SECTIONS.get(key);
            if (r == null) {
                if (SECTIONS.size() >= MAX_SECTIONS) {
                    return;
                }
                r = new Rec(key);
                SECTIONS.put(key, r);
                ORDER.add(key);
            }
            if (r.owner == null || r.owner.get() != rc) {
                r.owner = new WeakReference<RenderChunk>(rc);
            }
            boolean contentChanged = r.size[layer] != size || r.ms[layer] != h[1];
            uniqueRemove(r);
            r.size[layer] = size;
            r.exact[layer] = h[0];
            r.ms[layer] = h[1];
            r.combined = combine(r);
            uniqueAdd(r);
            if (layer != 3) {
                r.lastNonTranslUpload = now;
                r.nonTranslUploads++;
            } else if (contentChanged) {
                r.lastContentTranslUpload = now;
            }
        } catch (Throwable t) {
            fail("onUpload", t);
        }
    }

    /** Client tick END, main thread. */
    public static void tick() {
        try {
            if (mainThread == null) {
                mainThread = Thread.currentThread();
            }
            Minecraft mcw = Minecraft.func_71410_x();
            if (mcw.field_71441_e != lastWorld) {
                lastWorld = mcw.field_71441_e;
                ClientSync.onWorld();
                Far.clearAll();
                Disk.onWorld(mcw.field_71441_e);
            }
            ClientSync.drain();
            Far.deleteTrash();
            Far.checkMemory(System.nanoTime());
            Disk.tick(System.nanoTime());
        } catch (Throwable t) {
            logError("tick.world", t);
        }
        if (!ENABLED) {
            return;
        }
        try {
            long now = System.nanoTime();
            if (lastSummary == 0) {
                lastSummary = now;
                lastCsv = now;
            }
            if (now - lastSummary > SUMMARY_EVERY) {
                lastSummary = now;
                appendLine("summary.log", stamp() + " " + summaryOneLine());
            }
            if (now - lastCsv > CSV_EVERY) {
                lastCsv = now;
                writeCsv();
            }
            if (VERIFY) {
                verifierTick(now);
            }
        } catch (Throwable t) {
            fail("tick", t);
        }
    }

    /** Returns true when the chat line was a afterimage command and must not be sent. */
    public static boolean onChat(String msg) {
        if (msg == null) {
            return false;
        }
        String m = msg.trim();
        if (!m.equals("/afterimage") && !m.startsWith("/afterimage ")) {
            return false;
        }
        try {
            String arg = m.length() > 11 ? m.substring(11).trim() : "";
            if (arg.equals("off")) {
                ENABLED = false;
                say("disabled for this session");
            } else if (arg.equals("on")) {
                ENABLED = true;
                say("enabled");
            } else if (arg.equals("far off")) {
                Far.ENABLED = false;
                Far.clearAll();
                say("far zone disabled, copies freed");
            } else if (arg.equals("far on")) {
                Far.ENABLED = true;
                say("far zone enabled");
            } else if (arg.equals("far")) {
                say(Far.summary());
            } else if (arg.equals("disk")) {
                say(Disk.summary());
            } else if (arg.equals("disk off")) {
                Disk.ENABLED = false;
                say("disk cache disabled for this session");
            } else if (arg.equals("disk on")) {
                Disk.ENABLED = true;
                say("disk cache enabled");
            } else if (arg.equals("disk clear")) {
                Disk.clearWorld();
                say("disk cache for this server and dimension is being deleted");
            } else if (arg.equals("sync")) {
                say(ClientSync.summary());
            } else if (arg.equals("fog") || arg.startsWith("fog ")) {
                String v = arg.substring(3).trim();
                if (!v.isEmpty()) {
                    Far.fogEnd = Math.max(0, Integer.parseInt(v));
                }
                say("far zone fog ends at " + Far.fogEnd + " blocks (0 = vanilla fog)");
            } else if (arg.equals("csv")) {
                writeCsv();
                say("csv written to afterimage/sections.csv");
            } else {
                for (String line : summaryLines()) {
                    say(line);
                }
                appendLine("summary.log", stamp() + " [cmd] " + summaryOneLine());
            }
        } catch (Throwable t) {
            fail("onChat", t);
        }
        return true;
    }

    // main thread only: the vanilla part of a pack's vertices, for the fingerprint and the disk cache
    private static byte[] plainBytes = new byte[1 << 20];
    private static ByteBuffer plainBuf;

    private static ByteBuffer vanillaPart(ByteBuffer buf, int size, int vs) {
        int vertices = size / vs;
        int need = vertices * VertexLayout.VANILLA;
        if (need <= 0) {
            return null;
        }
        if (plainBytes.length < need) {
            plainBytes = new byte[need + (need >> 2)];
            plainBuf = null;
        }
        int wrote = VertexLayout.strip(buf, buf.position(), size, vs, plainBytes);
        if (plainBuf == null || plainBuf.array() != plainBytes) {
            plainBuf = ByteBuffer.wrap(plainBytes).order(java.nio.ByteOrder.nativeOrder());
        }
        plainBuf.clear();
        plainBuf.limit(wrote);
        return plainBuf;
    }

    /** Fingerprint of the last upload of that section layer if its size matches, else 0 (unknown). */
    public static long layerHash(long key, int layer, int size) {
        Rec r = SECTIONS.get(key);
        return r != null && r.size[layer] == size ? r.ms[layer] : 0L;
    }

    // ================= geometry samples =================
    // Raw bytes of a few real sections for offline compressibility analysis. The first upload of a
    // sampled VBO is saved as _first; the next up to three uploads of the same VBO within 60 s as
    // _next1.._next3 (LittleTiles re-uploads the merged buffer, so the multiset difference is its share).
    private static final int[] BAND_MIN = {8 << 20, 1 << 20, 100 << 10};
    private static final int[] BAND_QUOTA = {8, 8, 8};
    private static final int[] BAND_TAKEN = new int[3];
    private static final java.util.HashSet<Long> SAMPLED = new java.util.HashSet<Long>();
    private static final java.util.IdentityHashMap<VertexBuffer, long[]> FOLLOW = new java.util.IdentityHashMap<VertexBuffer, long[]>();
    private static int sampleSeq;
    private static long sampleFiles;

    private static void sample(VertexBuffer vb, ByteBuffer buf, long key, int layer, int size, int vertexSize, long now) {
        long[] follow = FOLLOW.get(vb);
        if (follow != null) {
            if (now > follow[1]) {
                FOLLOW.remove(vb);
            } else {
                follow[2]++;
                save(buf, (int) follow[0], "_next" + follow[2], key, layer, vertexSize);
                if (follow[2] >= 3) {
                    FOLLOW.remove(vb);
                }
                return;
            }
        }
        if (layer == 3 || size <= 0) {
            return;
        }
        long sk = key * 4 + layer;
        if (SAMPLED.contains(sk)) {
            return;
        }
        for (int b = 0; b < BAND_MIN.length; b++) {
            if (size >= BAND_MIN[b]) {
                if (BAND_TAKEN[b] >= BAND_QUOTA[b]) {
                    return;
                }
                BAND_TAKEN[b]++;
                SAMPLED.add(sk);
                int id = ++sampleSeq;
                save(buf, id, "_first", key, layer, vertexSize);
                FOLLOW.put(vb, new long[] {id, now + 60 * SEC, 0});
                return;
            }
        }
    }

    private static void save(ByteBuffer buf, int id, String suffix, long key, int layer, int vertexSize) {
        if (dir == null) {
            return;
        }
        final ByteBuffer copy = ByteBuffer.allocateDirect(buf.remaining()).order(buf.order());
        copy.put(buf.duplicate());
        copy.flip();
        sampleFiles++;
        final String base = String.format("sample_%02d_%s_L%d_vs%d%s", id, posString(key).replace(',', '_'), layer, vertexSize, suffix);
        final File d = new File(dir, "samples");
        IO.submit(() -> {
            try {
                d.mkdirs();
                writeBytes(new File(d, base + ".bin"), copy);
            } catch (Throwable t) {
                logError("sample", t);
            }
        });
    }

    // ================= verifier =================

    private static void verifierTick(long now) {
        if (vState == 0) {
            if (now - lastVerifyStart < VERIFY_EVERY || Minecraft.func_71410_x().field_71441_e == null) {
                return;
            }
            lastVerifyStart = now;
            Rec r = pickCandidate(now);
            if (r == null) {
                return;
            }
            RenderChunk rc = r.owner.get();
            Snap[] snap = snapshot(rc);
            int total = 0;
            for (int l = 0; l < LAYERS; l++) {
                total += snap[l].size;
                if (snap[l].size > 0 && r.size[l] > 0) {
                    boolean agree = r.size[l] == snap[l].size && r.ms[l] == snap[l].ms;
                    if (agree) {
                        capAgree++;
                    } else {
                        capDisagree++;
                    }
                }
            }
            r.lastVerified = now;
            if (total == 0) {
                vEmpty++;
                return;
            }
            s1 = snap;
            candKey = r.key;
            candRef = new WeakReference<RenderChunk>(rc);
            gen1 = ((IAfterimageRenderChunk) (Object) rc).afterimage$dirtyGen();
            uploads1 = r.nonTranslUploads;
            t0 = now;
            selfMark = true;
            try {
                rc.func_178575_a(false);
            } finally {
                selfMark = false;
            }
            vState = 1;
            return;
        }

        RenderChunk rc = candRef.get();
        Rec r = SECTIONS.get(candKey);
        if (rc == null || r == null || rc.func_178568_j().func_177986_g() != candKey) {
            vDiscardMoved++;
            reset();
            return;
        }
        if (((IAfterimageRenderChunk) (Object) rc).afterimage$dirtyGen() != gen1) {
            vDiscardWorld++;
            reset();
            return;
        }
        if (r.nonTranslUploads == uploads1) {
            if (now - t0 > VERIFY_TIMEOUT) {
                vTimeout++;
                reset();
            }
            return;
        }
        long last = Math.max(r.lastNonTranslUpload, r.lastContentTranslUpload);
        if (now - last < QUIET) {
            return;
        }
        Snap[] s2 = snapshot(rc);
        int worst = 0;
        for (int l = 0; l < LAYERS; l++) {
            int res = compare(l, s1[l], s2[l]);
            if (res >= 2) {
                LAYER_BAD[l]++;
                if (dumps < MAX_DUMPS) {
                    dump(candKey, l, res, s1[l], s2[l]);
                }
            }
            worst = Math.max(worst, res);
        }
        vDone++;
        switch (worst) {
            case 0: vExact++; break;
            case 1: vReordered++; break;
            case 2: vDifferent++; break;
            default: vSize++; break;
        }
        if (worst >= 2) {
            appendLine("verify.log", stamp() + " MISMATCH " + posString(candKey) + " result=" + worst + layersString(s1, s2));
        }
        reset();
    }

    private static void reset() {
        vState = 0;
        s1 = null;
        candRef = null;
    }

    /** 0 exact, 1 same quads other order, 2 different content same size, 3 different size. */
    private static int compare(int layer, Snap a, Snap b) {
        if (a.size != b.size) {
            return 3;
        }
        if (a.size == 0) {
            return 0;
        }
        if (a.ms != b.ms) {
            return 2;
        }
        if (layer == 3) {
            return 0;
        }
        return a.exact == b.exact ? 0 : 1;
    }

    private static Rec pickCandidate(long now) {
        int n = ORDER.size();
        if (n == 0) {
            return null;
        }
        pickLargest = !pickLargest;
        Rec best = null;
        int eligible = 0;
        for (int scanned = 0; scanned < Math.min(n, 3000); scanned++) {
            if (scanIndex >= n) {
                scanIndex = 0;
            }
            Rec r = SECTIONS.get(ORDER.get(scanIndex++));
            if (r == null || r.owner == null) {
                continue;
            }
            RenderChunk rc = r.owner.get();
            if (rc == null || r.nonTranslBytes() == 0) {
                continue;
            }
            if (now - r.lastNonTranslUpload < QUIET || now - r.lastContentTranslUpload < QUIET) {
                continue;
            }
            if (r.lastVerified != 0 && now - r.lastVerified < REVERIFY_AFTER) {
                continue;
            }
            if (rc.func_178568_j().func_177986_g() != r.key) {
                continue;
            }
            if (!pickLargest) {
                return r;
            }
            if (best == null || r.bytes() > best.bytes()) {
                best = r;
            }
            if (++eligible >= 300) {
                break;
            }
        }
        return best;
    }

    private static Snap[] snapshot(RenderChunk rc) {
        Snap[] out = new Snap[LAYERS];
        CompiledChunk cc = rc.func_178571_g();
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        for (int l = 0; l < LAYERS; l++) {
            Snap s = new Snap();
            out[l] = s;
            if (cc == null || cc.func_178491_b(layers[l])) {
                continue;
            }
            VertexBuffer vb = rc.func_178565_b(l);
            if (vb == null) {
                continue;
            }
            IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
            ByteBuffer bytes = readback(v.afterimage$glId());
            if (bytes == null) {
                continue;
            }
            long[] h = Hash.of(bytes, v.afterimage$vertexSize());
            s.size = bytes.remaining();
            s.exact = h[0];
            s.ms = h[1];
            s.bytes = bytes;
        }
        return out;
    }

    private static ByteBuffer readback(int id) {
        if (id <= 0) {
            return null;
        }
        int prev = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id);
            int size = GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER, GL15.GL_BUFFER_SIZE);
            if (size <= 0 || size > MAX_READBACK) {
                return null;
            }
            ByteBuffer bb = BufferUtils.createByteBuffer(size);
            GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER, 0, bb);
            bb.position(0);
            bb.limit(size);
            return bb;
        } finally {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prev);
        }
    }

    // ================= stats & output =================

    private static long combine(Rec r) {
        long c = 0x2545F4914F6CDD1DL;
        for (int l = 0; l < LAYERS; l++) {
            c = Long.rotateLeft(c ^ r.ms[l], 23) * 0x9E3779B97F4A7C15L + r.size[l];
        }
        return c;
    }

    private static void uniqueAdd(Rec r) {
        if (r.bytes() == 0) {
            return;
        }
        Integer c = UNIQUE.get(r.combined);
        UNIQUE.put(r.combined, c == null ? 1 : c + 1);
    }

    private static void uniqueRemove(Rec r) {
        if (r.bytes() == 0) {
            return;
        }
        Integer c = UNIQUE.get(r.combined);
        if (c == null) {
            return;
        }
        if (c <= 1) {
            UNIQUE.remove(r.combined);
        } else {
            UNIQUE.put(r.combined, c - 1);
        }
    }

    private static String mb(long bytes) {
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String[] summaryLines() {
        long live = LIVE[0] + LIVE[1] + LIVE[2] + LIVE[3];
        long known = 0;
        int nonEmpty = 0;
        for (Rec r : SECTIONS.values()) {
            int b = r.bytes();
            known += b;
            if (b > 0) {
                nonEmpty++;
            }
        }
        return new String[] {
            "GPU section geometry live: " + mb(live) + " (solid " + mb(LIVE[0]) + ", mipped " + mb(LIVE[1])
                + ", cutout " + mb(LIVE[2]) + ", transl " + mb(LIVE[3]) + ")",
            "sections seen: " + SECTIONS.size() + ", non-empty " + nonEmpty + ", unique meshes " + UNIQUE.size()
                + ", geometry of all seen " + mb(known),
            "uploads " + uploads + " (" + mb(uploadBytes) + "), hashing " + (hashNanos / 1_000_000L) + " ms on client thread (" + mainHashed
                + "), on workers " + (WORKER_NANOS.get() / 1_000_000L) + " ms (" + WORKER_HASHED.get() + "), handed over " + workerUsed
                + ", stale " + workerStale + ", checked " + workerChecked + ", MISMATCH " + workerMismatch + ", unowned "
                + unownedUploads + ", offthread " + offThreadUploads + ", VBO created " + vboCreated + " deleted " + vboDeleted,
            "verify: " + vDone + " done, exact " + vExact + ", reordered " + vReordered + ", DIFFERENT " + vDifferent
                + ", SIZE " + vSize + " | discarded world " + vDiscardWorld + ", moved " + vDiscardMoved
                + ", timeout " + vTimeout + ", empty " + vEmpty,
            Far.summary(),
            Disk.summary(),
            "capture==GPU: agree " + capAgree + ", disagree " + capDisagree + " | dumps " + dumps + ", samples " + sampleSeq + " (" + sampleFiles + " files), errors " + errors
                + (ENABLED ? "" : " | DISABLED")
        };
    }

    private static String summaryOneLine() {
        StringBuilder sb = new StringBuilder();
        for (String s : summaryLines()) {
            if (sb.length() > 0) {
                sb.append(" || ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static void say(String line) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71439_g != null) {
            mc.field_71439_g.func_145747_a(new TextComponentString("§b[afterimage]§r " + line));
        }
    }

    private static void writeCsv() {
        final int n = SECTIONS.size();
        final long[] keys = new long[n];
        final int[] sizes = new int[n * LAYERS];
        final long[] combined = new long[n];
        final byte[] live = new byte[n];
        int i = 0;
        for (Rec r : SECTIONS.values()) {
            if (i >= n) {
                break;
            }
            keys[i] = r.key;
            System.arraycopy(r.size, 0, sizes, i * LAYERS, LAYERS);
            combined[i] = r.combined;
            RenderChunk rc = r.owner == null ? null : r.owner.get();
            live[i] = (byte) (rc != null && rc.func_178568_j().func_177986_g() == r.key ? 1 : 0);
            i++;
        }
        final int count = i;
        final File out = new File(dir, "sections.csv");
        IO.submit(() -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
                w.println("sx,sy,sz,solid,mipped,cutout,transl,combined,live");
                for (int k = 0; k < count; k++) {
                    BlockPos p = BlockPos.func_177969_a(keys[k]);
                    w.print(p.func_177958_n() >> 4);
                    w.print(',');
                    w.print(p.func_177956_o() >> 4);
                    w.print(',');
                    w.print(p.func_177952_p() >> 4);
                    for (int l = 0; l < LAYERS; l++) {
                        w.print(',');
                        w.print(sizes[k * LAYERS + l]);
                    }
                    w.print(',');
                    w.print(Long.toHexString(combined[k]));
                    w.print(',');
                    w.println(live[k]);
                }
            } catch (Throwable t) {
                logError("csv", t);
            }
        });
    }

    private static void dump(long key, int layer, int result, Snap a, Snap b) {
        dumps++;
        final String base = String.format("mismatch_%02d_%s_L%d_r%d", dumps, posString(key).replace(',', '_'), layer, result);
        final ByteBuffer ba = a.bytes;
        final ByteBuffer bb = b.bytes;
        final String meta = "pos=" + posString(key) + " layer=" + layer + " result=" + result
            + " A.size=" + a.size + " A.exact=" + Long.toHexString(a.exact) + " A.ms=" + Long.toHexString(a.ms)
            + " B.size=" + b.size + " B.exact=" + Long.toHexString(b.exact) + " B.ms=" + Long.toHexString(b.ms);
        final File d = new File(dir, "mismatch");
        IO.submit(() -> {
            try {
                d.mkdirs();
                writeBytes(new File(d, base + "_A.bin"), ba);
                writeBytes(new File(d, base + "_B.bin"), bb);
                try (PrintWriter w = new PrintWriter(new FileWriter(new File(d, base + ".txt")))) {
                    w.println(meta);
                }
            } catch (Throwable t) {
                logError("dump", t);
            }
        });
    }

    private static void writeBytes(File f, ByteBuffer b) throws Exception {
        if (b == null) {
            return;
        }
        ByteBuffer dup = b.duplicate();
        dup.position(0);
        try (FileOutputStream fos = new FileOutputStream(f); FileChannel ch = fos.getChannel()) {
            while (dup.hasRemaining()) {
                ch.write(dup);
            }
        }
    }

    private static String posString(long key) {
        BlockPos p = BlockPos.func_177969_a(key);
        return p.func_177958_n() + "," + p.func_177956_o() + "," + p.func_177952_p();
    }

    private static String layersString(Snap[] a, Snap[] b) {
        StringBuilder sb = new StringBuilder();
        for (int l = 0; l < LAYERS; l++) {
            sb.append(" L").append(l).append(':').append(a[l].size).append('/').append(b[l].size)
                .append(a[l].ms == b[l].ms ? "=" : "!").append(a[l].exact == b[l].exact ? "=" : "~");
        }
        return sb.toString();
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss").format(new Date());
    }

    private static void appendLine(final String file, final String line) {
        if (dir == null) {
            return;
        }
        final File f = new File(dir, file);
        IO.submit(() -> {
            try (PrintWriter w = new PrintWriter(new FileWriter(f, true))) {
                w.println(line);
            } catch (Throwable t) {
                // nowhere left to report
            }
        });
    }

    private static void fail(String where, Throwable t) {
        errors++;
        ENABLED = false;
        logError(where, t);
    }

    static boolean onMainThread() {
        return mainThread == null || Thread.currentThread() == mainThread;
    }

    static void logInfo(String message) {
        appendLine("summary.log", stamp() + " " + message);
    }

    static void logError(String where, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        appendLine("errors.log", stamp() + " " + where + ": " + sw);
    }

    /** Exposed for tests / debugging. */
    public static Map<Long, Integer> uniqueView() {
        return UNIQUE;
    }
}
