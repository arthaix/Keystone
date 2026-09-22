package ru.arthaix.afterimage;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.ViewFrustum;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.MobEffects;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GLContext;
import ru.arthaix.afterimage.mixin.ViewFrustumAccessor;

/**
 * Afterimage phase 1: far zone.
 *
 * Section geometry is copied GPU-side (glCopyBufferSubData, no CPU readback) at the moment vanilla would
 * lose it: a RenderChunk moving to another position, renderers being deleted, or a section being recompiled
 * while its chunk is no longer loaded on the client. The copies are drawn before the vanilla SOLID layer with
 * the clipping planes pushed out and fog pushed out, then the depth buffer is cleared, so vanilla terrain (always
 * nearer) draws on top. A copy stays while vanilla shows the section and is only replaced when the section's
 * geometry changed in the meantime (upload fingerprints), so flying back and forth costs no copies.
 * Bytes are identical to what vanilla uploaded (verified in phase 0). All four layers: SOLID, CUTOUT_MIPPED and
 * CUTOUT first, then TRANSLUCENT back to front with blending and no depth writes, as vanilla draws it.
 */
public final class Far {
    public static volatile boolean ENABLED = !"false".equals(System.getProperty("afterimage.far"));
    private static final long BUDGET = Long.getLong("afterimage.farBudgetMB", 8192L) << 20;
    private static final double FAR_PLANE = Integer.getInteger("afterimage.farPlane", 8192);
    /** Near plane of the far pass. Much larger than vanilla's so a 24-bit depth buffer resolves microblocks kilometres away. */
    private static final double FAR_NEAR = Double.parseDouble(System.getProperty("afterimage.farNear", "6"));
    /** Normal air fog is pushed out to this distance while the far zone has content; 0 keeps vanilla fog. */
    public static volatile int fogEnd = Integer.getInteger("afterimage.fogEnd", 2048);
    /**
     * A copy keeps being drawn under a freshly compiled vanilla section until the two match or vanilla has not changed the
     * section for this long. Vanilla compiles a section as soon as the chunk arrives, before its tile entities (LittleTiles,
     * Chisels & Bits) are applied, so an immediate handover made buildings vanish and come back while chunks loaded.
     */
    private static final long SETTLE_NANOS = Long.getLong("afterimage.settleMs", 20000L) * 1_000_000L;
    /** Sections nearer than this (blocks, camera to section centre) are never hidden from vanilla: you see your own edits at once. */
    private static final double HIDE_NEAR = Double.parseDouble(System.getProperty("afterimage.hideNear", "32"));
    /** Server change ticks are compared with client geometry ticks with this slack (the client clock follows the server). */
    public static final long SYNC_TOLERANCE_TICKS = 100;
    private static final int LAYERS = 4;
    /** SOLID, CUTOUT_MIPPED, CUTOUT. Layer 3 (TRANSLUCENT) is drawn in its own sorted, blended pass. */
    private static final int OPAQUE_LAYERS = 3;
    private static final int TRANSLUCENT = 3;
    private static final int VS = 28;
    /** Keep a leaving section's own GL buffer as its copy instead of copying it on the GPU (-Dafterimage.steal=false copies). */
    private static final boolean STEAL = !"false".equals(System.getProperty("afterimage.steal"));
    /** Copies loaded from disk never change: immutable GL storage without CPU access where the driver has it. */
    private static final boolean IMMUTABLE = !"false".equals(System.getProperty("afterimage.immutable"));
    private static Boolean storage;
    private static final long MIN_BUDGET = Long.getLong("afterimage.farMinBudgetMB", 1024L) << 20;
    /** Below this much available RAM the far zone gives copies back; above HIGH_FREE it grows back toward BUDGET. */
    private static final long LOW_FREE = Long.getLong("afterimage.lowFreeMB", 4096L) << 20;
    private static final long HIGH_FREE = Long.getLong("afterimage.highFreeMB", 6144L) << 20;
    private static long budgetNow = BUDGET;
    private static long lastMemoryCheck;
    private static long budgetLowered;
    private static java.lang.management.OperatingSystemMXBean os;

    private static boolean storage() {
        if (storage == null) {
            org.lwjgl.opengl.ContextCapabilities c = GLContext.getCapabilities();
            storage = IMMUTABLE && (c.OpenGL44 || c.GL_ARB_buffer_storage);
        }
        return storage;
    }

    /**
     * Client tick, every 2 s. Immutable copies (glBufferStorage) live in VRAM only, so the budget follows the card's
     * free VRAM where the driver reports it (GL_NVX_gpu_memory_info): below LOW_VRAM the budget shrinks by the shortfall
     * (never below MIN_BUDGET) and the farthest copies go, above HIGH_VRAM it grows back 256 MB per check. Without that
     * report the budget stays as configured. Only where copies are mutable (no ARB_buffer_storage) the driver keeps a
     * shadow of each in RAM; then the same rule runs on available RAM (LOW_FREE / HIGH_FREE). Windows' "free" RAM was
     * used for both before, and it is near zero whenever the file cache is full, so the far zone was emptied on every
     * flight for no reason.
     */
    private static final long LOW_VRAM = Long.getLong("afterimage.lowVramMB", 2560L) << 20;
    private static final long HIGH_VRAM = Long.getLong("afterimage.highVramMB", 3584L) << 20;
    private static final int GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    private static Boolean vramInfo;

    /** last reading, for callers off the client thread (hitch reports); -1 until the first one */
    private static volatile long lastVramFree = -1L;

    private static long availableVram() {
        // GL only on the client thread: a query from the stall sampler's thread threw, and the caught error switched
        // the VRAM budget off for the rest of the session
        try {
            if (!Minecraft.func_71410_x().func_152345_ab()) {
                return lastVramFree;
            }
        } catch (Throwable t) {
            return lastVramFree;
        }
        if (vramInfo == null) {
            try {
                vramInfo = GLContext.getCapabilities().GL_NVX_gpu_memory_info;
            } catch (Throwable t) {
                vramInfo = false;
            }
        }
        if (!vramInfo) {
            return -1L;
        }
        try {
            long free = (long) GL11.glGetInteger(GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) << 10;
            lastVramFree = free;
            return free;
        } catch (Throwable t) {
            vramInfo = false;
            return -1L;
        }
    }

    public static void checkMemory(long now) {
        if (now - lastMemoryCheck < 2_000_000_000L) {
            return;
        }
        lastMemoryCheck = now;
        long available;
        long low;
        long high;
        if (storage()) {
            available = availableVram();
            if (available < 0L) {
                return;
            }
            low = LOW_VRAM;
            high = HIGH_VRAM;
        } else {
            try {
                if (os == null) {
                    os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                }
                available = ((com.sun.management.OperatingSystemMXBean) os).getFreePhysicalMemorySize();
            } catch (Throwable t) {
                return;
            }
            low = LOW_FREE;
            high = HIGH_FREE;
        }
        if (available < low) {
            long shortfall = low - available;
            if (shortfall < (128L << 20)) {
                return;
            }
            long target = Math.max(MIN_BUDGET, budgetNow - shortfall);
            if (target < budgetNow) {
                budgetNow = target;
                budgetLowered++;
                if (bytes > budgetNow) {
                    evict();
                }
            }
        } else if (available > high && budgetNow < BUDGET) {
            budgetNow = Math.min(BUDGET, budgetNow + (256L << 20));
        }
    }

    private static final class Entry {
        final long key;
        final int x;
        final int y;
        final int z;
        final int[] ids = new int[LAYERS];
        final int[] counts = new int[LAYERS];
        /** Multiset fingerprint per layer of the copied bytes, 0 when unknown. */
        final long[] hash = new long[LAYERS];
        /** Set when the section got different geometry after the copy was made. */
        boolean stale;
        /** Fingerprint per layer of what vanilla currently has for this section, 0 = empty or not seen. */
        final long[] vanillaHash = new long[LAYERS];
        /** System.nanoTime() of vanilla's last compile of this section, 0 = none seen while the copy existed. */
        long vanillaChanged;
        /** Vanilla has taken over this section; stays set until the section leaves vanilla again. */
        boolean handedOver;
        /** Vanilla shows this section in the current frame (compiled, chunk loaded). */
        boolean vanillaNow;
        /** This frame vanilla's half-assembled section is hidden and the copy is drawn in full. */
        boolean hidingVanilla;
        /** Client world tick at which this geometry was last confirmed by vanilla or written to disk; 0 = unknown. */
        long geomTime;
        long bytes;
        double dist2;
        /** Bytes per vertex of these buffers: vanilla's layout, or OptiFine's while a shader pack is loaded. */
        int stride = VS;

        Entry(long key, BlockPos p) {
            this(key, p.func_177958_n(), p.func_177956_o(), p.func_177952_p());
        }

        Entry(long key, int x, int y, int z) {
            this.key = key;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A shader pack is drawing the world: copies are taken and drawn in OptiFine's vertex layout. */
    private static boolean shaders;

    /** Bytes per vertex of the copies taken now: VS without a shader pack, OptiFine's layout with one. */
    private static int stride = VS;

    private static final HashMap<Long, Entry> ENTRIES = new HashMap<Long, Entry>(8192);
    /** Stale copies of sections vanilla shows, recaptured after the render loop (at most REFRESH_PER_FRAME per frame). */
    private static final ArrayList<RenderChunk> REFRESH = new ArrayList<RenderChunk>(16);
    private static final int REFRESH_PER_FRAME = 8;
    private static final long REFRESH_QUIET_NANOS = Long.getLong("afterimage.refreshQuietMs", 1500L) * 1_000_000L;
    private static long refreshed;
    private static final ArrayList<Entry> VISIBLE = new ArrayList<Entry>(8192);
    private static final ArrayList<Entry> VISIBLE_T = new ArrayList<Entry>(2048);
    private static final FloatBuffer MAT = BufferUtils.createFloatBuffer(16);
    private static final float[] PROJ = new float[16];
    private static final float[] MV = new float[16];
    private static final float[] CLIP = new float[16];
    private static final float[][] PLANES = new float[6][4];

    private static Boolean supported;
    private static long bytes;
    private static long captures;
    private static long stolen;
    private static long reused;
    private static long invalidated;
    /** section key -> client world tick of vanilla's last upload for that section */
    private static final Long2LongOpenHashMap GEOM = new Long2LongOpenHashMap();
    /** chunk key -> latest server tick at which the server reported a change of that chunk */
    private static final Long2LongOpenHashMap CHANGES = new Long2LongOpenHashMap();
    private static long settling;
    private static long drops;
    private static long evictions;
    private static long errors;
    private static int lastDrawn;
    private static int lastHidden;
    /** Incremented once per frame at the SOLID pass; RenderChunks store the frame in which vanilla must skip them. */
    private static int frame;
    private static int lastDrawnTranslucent;
    private static int lastSkippedVanilla;
    private static int lastCulled;
    private static double camX;
    private static double camY;
    private static double camZ;

    private Far() {
    }

    // ================= capture =================

    /** RenderChunk is about to move to another position, or its GL resources are about to be deleted. */
    public static void onLeave(RenderChunk rc) {
        if (!ENABLED || !Capture.onMainThread()) {
            return;
        }
        try {
            capture(rc, true);
        } catch (Throwable t) {
            fail("onLeave", t);
        }
    }

    /** RenderChunk is about to get a new CompiledChunk. */
    public static void onCompiledReplace(RenderChunk rc, CompiledChunk next) {
        if (!ENABLED || !Capture.onMainThread()) {
            return;
        }
        try {
            BlockPos p = rc.func_178568_j();
            if (chunkLoaded(p)) {
                if (next != CompiledChunk.field_178502_a) {
                    long key = p.func_177986_g();
                    Entry e = ENTRIES.get(key);
                    if (e != null) {
                        BlockRenderLayer[] layers = BlockRenderLayer.values();
                        for (int l = 0; l < LAYERS; l++) {
                            if (next.func_178491_b(layers[l])) {
                                e.vanillaHash[l] = 0L;
                                if (e.ids[l] > 0) {
                                    e.stale = true;
                                }
                            }
                        }
                        e.vanillaChanged = System.nanoTime();
                    }
                    Disk.onVanillaCompiled(key, next);
                }
            } else {
                capture(rc);
            }
        } catch (Throwable t) {
            fail("onCompiledReplace", t);
        }
    }

    /** Main thread, from Capture.onUpload: a section layer (0..2) got geometry with this fingerprint. */
    public static void onUpload(long key, int layer, long ms, long worldTime) {
        GEOM.put(key, worldTime);
        Entry e = ENTRIES.get(key);
        if (e != null) {
            e.vanillaHash[layer] = ms;
            e.vanillaChanged = System.nanoTime();
            if (e.hash[layer] != ms) {
                e.stale = true;
            } else if (!e.stale) {
                e.geomTime = Math.max(e.geomTime, worldTime);
            }
        }
    }

    private static boolean regionsChecked;
    private static boolean regionsAbsent;
    private static boolean regions;
    private static int regionsCheckFrame;

    /** OptiFine's render regions draw sections from shared region buffers: a VertexBuffer's own buffer must stay put then. */
    private static boolean renderRegions() {
        if (regionsAbsent) {
            return false;
        }
        if (!regionsChecked || frame - regionsCheckFrame > 600 || frame < regionsCheckFrame) {
            regionsChecked = true;
            regionsCheckFrame = frame;
            try {
                regions = Boolean.TRUE.equals(Class.forName("Config").getMethod("isRenderRegions").invoke(null));
            } catch (ClassNotFoundException e) {
                regionsAbsent = true;
                regions = false;
            } catch (Throwable t) {
                regions = true;
            }
        }
        return regions;
    }

    private static boolean supported() {
        if (supported == null) {
            supported = GLContext.getCapabilities().OpenGL31;
        }
        return supported;
    }

    private static boolean chunkLoaded(BlockPos p) {
        WorldClient w = Minecraft.func_71410_x().field_71441_e;
        if (w == null) {
            return false;
        }
        Chunk c = w.func_72863_F().func_186026_b(p.func_177958_n() >> 4, p.func_177952_p() >> 4);
        return c != null && !c.func_76621_g();
    }

    /** chunk key -> (frame + 1) * 2 + loaded: the far pass asks for the same chunks for every visible section. */
    private static final Long2LongOpenHashMap LOADED_IN_FRAME = new Long2LongOpenHashMap();

    private static boolean chunkLoadedThisFrame(BlockPos p) {
        long ck = ChunkPos.func_77272_a(p.func_177958_n() >> 4, p.func_177952_p() >> 4);
        long stamp = ((long) frame + 1L) << 1;
        long v = LOADED_IN_FRAME.get(ck);
        if ((v & ~1L) == stamp) {
            return (v & 1L) != 0L;
        }
        boolean loaded = chunkLoaded(p);
        if (LOADED_IN_FRAME.size() > 262144) {
            LOADED_IN_FRAME.clear();
        }
        LOADED_IN_FRAME.put(ck, stamp | (loaded ? 1L : 0L));
        return loaded;
    }

    private static void capture(RenderChunk rc) {
        capture(rc, false);
    }

    /** leaving: the RenderChunk moves to another position or deletes its buffers, so vanilla never draws their bytes again. */
    private static void capture(RenderChunk rc, boolean leaving) {
        if (!supported()) {
            return;
        }
        CompiledChunk cc = rc.func_178571_g();
        if (cc == null || cc == CompiledChunk.field_178502_a) {
            return;
        }
        BlockPos p = rc.func_178568_j();
        long key = p.func_177986_g();
        Entry old = ENTRIES.get(key);
        boolean tracked = Capture.ENABLED;
        if (old != null && !old.stale && tracked) {
            // The copy already holds exactly this geometry: any later upload would have marked it stale.
            old.handedOver = false;
            old.vanillaChanged = 0L;
            reused++;
            return;
        }
        BlockRenderLayer[] layers = BlockRenderLayer.values();
        int[] ids = new int[LAYERS];
        int[] counts = new int[LAYERS];
        long[] hashes = new long[LAYERS];
        long total = 0;
        boolean any = false;
        boolean unknown = !tracked;
        for (int l = 0; l < LAYERS; l++) {
            if (cc.func_178491_b(layers[l])) {
                continue;
            }
            VertexBuffer vb = rc.func_178565_b(l);
            if (vb == null) {
                continue;
            }
            IAfterimageVbo v = (IAfterimageVbo) (Object) vb;
            int src = v.afterimage$glId();
            if (src <= 0 || v.afterimage$vertexSize() != stride) {
                continue;
            }
            int size;
            if (tracked && leaving && STEAL && !renderRegions()) {
                // Vanilla never draws these bytes again: keep the buffer itself as the copy and give the VertexBuffer a new,
                // empty one. No allocation or copy in the driver, which stalled flights at every chunk border.
                size = v.afterimage$lastSize();
                if (size < stride * 4 || size % stride != 0) {
                    continue;
                }
                int fresh = GL15.glGenBuffers();
                if (fresh <= 0) {
                    continue;
                }
                v.afterimage$setGlId(fresh);
                Capture.onStolen(vb);
                ids[l] = src;
                stolen++;
            } else {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, src);
                if (tracked) {
                    // size of the last upload, recorded by the upload hook; glGetBufferParameter would stall the CPU on the driver
                    size = v.afterimage$lastSize();
                } else {
                    size = GL15.glGetBufferParameteri(GL31.GL_COPY_READ_BUFFER, GL15.GL_BUFFER_SIZE);
                }
                if (size < stride * 4 || size % stride != 0) {
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                    continue;
                }
                int dst = GL15.glGenBuffers();
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, dst);
                GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, (long) size, GL15.GL_STATIC_DRAW);
                GL31.glCopyBufferSubData(GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, 0L, 0L, (long) size);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                ids[l] = dst;
            }
            counts[l] = size / stride;
            hashes[l] = tracked ? Capture.layerHash(key, l, size) : 0L;
            if (hashes[l] == 0L) {
                unknown = true;
            }
            total += size;
            any = true;
        }
        if (old != null) {
            ENTRIES.remove(key);
            free(old);
        }
        if (!any) {
            return;
        }
        Entry e = new Entry(key, p);
        e.stride = stride;
        System.arraycopy(ids, 0, e.ids, 0, LAYERS);
        System.arraycopy(counts, 0, e.counts, 0, LAYERS);
        System.arraycopy(hashes, 0, e.hash, 0, LAYERS);
        e.stale = unknown;
        e.geomTime = GEOM.get(key);
        e.bytes = total;
        ENTRIES.put(key, e);
        bytes += total;
        captures++;
        if (bytes > budgetNow) {
            evict();
        }
    }

    private static void drop(long key) {
        Entry e = ENTRIES.remove(key);
        if (e != null) {
            free(e);
            drops++;
        }
    }

    private static void free(Entry e) {
        for (int l = 0; l < LAYERS; l++) {
            if (e.ids[l] > 0) {
                TRASH.add(e.ids[l]);
                e.ids[l] = 0;
            }
        }
        bytes -= e.bytes;
    }

    /** GL buffers of dropped copies, deleted at most DELETE_PER_TICK per client tick (a world change freed ~30k at once: 1 s). */
    private static final it.unimi.dsi.fastutil.ints.IntArrayList TRASH = new it.unimi.dsi.fastutil.ints.IntArrayList();
    private static final int DELETE_PER_TICK = 512;
    private static final java.nio.IntBuffer TRASH_IDS = BufferUtils.createIntBuffer(DELETE_PER_TICK);

    /** Client tick, main thread. */
    public static void deleteTrash() {
        int n = Math.min(DELETE_PER_TICK, TRASH.size());
        if (n == 0) {
            return;
        }
        TRASH_IDS.clear();
        int from = TRASH.size() - n;
        for (int i = TRASH.size() - 1; i >= from; i--) {
            TRASH_IDS.put(TRASH.getInt(i));
        }
        TRASH.size(from);
        TRASH_IDS.flip();
        GL15.glDeleteBuffers(TRASH_IDS);
    }

    private static void evict() {
        ArrayList<Entry> all = new ArrayList<Entry>(ENTRIES.values());
        for (Entry e : all) {
            double dx = e.x + 8 - camX;
            double dy = e.y + 8 - camY;
            double dz = e.z + 8 - camZ;
            e.dist2 = dx * dx + dy * dy + dz * dz;
        }
        Collections.sort(all, (a, b) -> Double.compare(b.dist2, a.dist2));
        long target = budgetNow - budgetNow / 10;
        for (Entry e : all) {
            if (bytes <= target) {
                break;
            }
            ENTRIES.remove(e.key);
            free(e);
            evictions++;
        }
    }

    // ================= render =================

    /** Called at the head of RenderGlobal.renderBlockLayer(layer, partialTicks, pass, entity). */
    public static int frame() {
        return frame;
    }

    /** Bytes per vertex of the copies taken now: the layout the game builds its sections in. */
    public static int stride() {
        return stride;
    }

    /** True while the copies are in OptiFine's shader layout (kept apart from the vanilla ones on disk). */
    public static boolean shaderFormat() {
        return shaders;
    }

    /**
     * A shader pack was switched on or off. The copies in VRAM are in the other vertex layout and their bytes mean
     * nothing to the programs drawing now, so they go; the cache of the new layout is read from disk instead.
     */
    private static void checkShaderPack() {
        boolean now = ShaderPack.active();
        if (now == shaders) {
            return;
        }
        shaders = now;
        stride = now ? ShaderPack.stride() : VS;
        clearAll();
        Disk.rescan();
        Capture.logInfo("far: shader pack " + (now ? "on" : "off") + ", vertex layout " + stride
                + " bytes, the far zone starts over");
    }

    public static void render(BlockRenderLayer layer, double partialTicks, Entity viewer, ViewFrustum frustum) {
        if (layer == BlockRenderLayer.SOLID) {
            frame++;
            checkShaderPack();
        }
        if (!ENABLED || layer != BlockRenderLayer.SOLID || viewer == null) {
            return;
        }
        try {
            if (!supported()) {
                return;
            }
            Disk.pump(frustum);
            if (ENTRIES.isEmpty()) {
                return;
            }
            renderImpl(partialTicks, viewer, frustum);
        } catch (Throwable t) {
            fail("render", t);
        }
    }

    private static void renderImpl(double pt, Entity e, ViewFrustum frustum) {
        Minecraft mc = Minecraft.func_71410_x();
        if (mc.field_71441_e == null) {
            return;
        }
        camX = e.field_70142_S + (e.field_70165_t - e.field_70142_S) * pt;
        camY = e.field_70137_T + (e.field_70163_u - e.field_70137_T) * pt;
        camZ = e.field_70136_U + (e.field_70161_v - e.field_70136_U) * pt;

        MAT.clear();
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, MAT);
        MAT.get(PROJ);
        MAT.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MAT);
        MAT.get(MV);
        if (Math.abs(PROJ[11] + 1f) > 1e-3f) {
            return;
        }
        double p10 = PROJ[10];
        double p14 = PROJ[14];
        double near = p14 / (p10 - 1.0);
        double far = p14 / (p10 + 1.0);
        if (!(near > 0.0) || !(far > near)) {
            return;
        }
        if (!shaders) {
            // Without a pack the copies are drawn with a far plane of their own, and the depth they write is cleared
            // again before vanilla draws. A pack reads that depth in its later passes to find where a pixel is in the
            // world (fog, shadows, lighting), so with one loaded the copies keep the game's own projection: they are
            // then cut off at the game's far plane, which is well past the pack's fog anyway, and everything they draw
            // is lit like the terrain in front of it. Copies further out than that simply do not appear.
            double newNear = Math.max(near, FAR_NEAR);
            double newFar = Math.max(far, FAR_PLANE);
            PROJ[10] = (float) (-(newFar + newNear) / (newFar - newNear));
            PROJ[14] = (float) (-2.0 * newFar * newNear / (newFar - newNear));
        }
        multiply(PROJ, MV, CLIP);
        extractPlanes(CLIP);

        VISIBLE.clear();
        int skippedVanilla = 0;
        int hidden = 0;
        int culled = 0;
        ViewFrustumAccessor vf = frustum == null ? null : (ViewFrustumAccessor) (Object) frustum;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        long now = System.nanoTime();
        for (Entry en : ENTRIES.values()) {
            float minX = (float) (en.x - camX);
            float minY = (float) (en.y - camY);
            float minZ = (float) (en.z - camZ);
            if (!boxVisible(minX, minY, minZ, minX + 16f, minY + 16f, minZ + 16f)) {
                culled++;
                continue;
            }
            en.vanillaNow = false;
            en.hidingVanilla = false;
            if (vf != null) {
                probe.func_181079_c(en.x, en.y, en.z);
                RenderChunk rc = vf.afterimage$getRenderChunk(probe);
                en.vanillaNow = rc != null && rc.func_178568_j().func_177986_g() == en.key
                        && rc.func_178571_g() != CompiledChunk.field_178502_a && chunkLoadedThisFrame(probe);
                if (en.vanillaNow) {
                    if (settled(en, now, rc)) {
                        skippedVanilla++;
                        continue;
                    }
                    if (en.stale && REFRESH.size() < REFRESH_PER_FRAME && en.vanillaChanged != 0L
                            && now - en.vanillaChanged > REFRESH_QUIET_NANOS && !rc.func_178569_m()) {
                        // 0.5.4 recaptured such sections on every camera move (ViewFrustum repositions all RenderChunks);
                        // 0.5.5 stopped that, and stale copies (glass of other sections among them) stayed on screen
                        REFRESH.add(rc);
                    }
                    double dx = en.x + 8 - camX;
                    double dy = en.y + 8 - camY;
                    double dz = en.z + 8 - camZ;
                    if (dx * dx + dy * dy + dz * dz > HIDE_NEAR * HIDE_NEAR) {
                        // vanilla is still assembling this section (tile entities arriving): show the whole copy instead
                        ((IAfterimageRenderChunk) (Object) rc).afterimage$setHideFrame(frame);
                        en.hidingVanilla = true;
                        hidden++;
                    }
                }
            }
            {
                double dx = en.x + 8 - camX;
                double dy = en.y + 8 - camY;
                double dz = en.z + 8 - camZ;
                en.dist2 = dx * dx + dy * dy + dz * dz;
            }
            VISIBLE.add(en);
        }
        // nearest first: the depth test then rejects most fragments of the sections behind (HashMap order drew them in
        // random order, so a far section was often fully shaded before a near one covered it)
        Collections.sort(VISIBLE, (a, b) -> Double.compare(a.dist2, b.dist2));
        if (!REFRESH.isEmpty()) {
            for (int i = 0, n = REFRESH.size(); i < n; i++) {
                capture(REFRESH.get(i), false);
                refreshed++;
            }
            REFRESH.clear();
        }
        lastSkippedVanilla = skippedVanilla;
        lastHidden = hidden;
        lastCulled = culled;
        lastDrawn = VISIBLE.size();
        if (VISIBLE.isEmpty()) {
            return;
        }

        boolean fog = !shaders && fogEnd <= 0 && GL11.glIsEnabled(GL11.GL_FOG);
        boolean alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.func_179138_g(OpenGlHelper.field_77476_b);
        boolean lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.func_179138_g(OpenGlHelper.field_77478_a);

        GlStateManager.func_179128_n(GL11.GL_PROJECTION);
        GlStateManager.func_179094_E();
        MAT.clear();
        MAT.put(PROJ);
        MAT.flip();
        GL11.glLoadMatrix(MAT);
        GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
        if (fog) {
            GlStateManager.func_179106_n();
        }
        RenderHelper.func_74518_a();
        if (!lightmap) {
            mc.field_71460_t.func_180436_i();
        }

        GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
        OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
        GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
        GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
        if (shaders) {
            ShaderPack.preChunkLayer(BlockRenderLayer.SOLID);
        }
        try {
            for (int l = 0; l < OPAQUE_LAYERS; l++) {
                if (l == 1) {
                    GlStateManager.func_179141_d();
                }
                for (int i = 0, n = VISIBLE.size(); i < n; i++) {
                    drawLayer(VISIBLE.get(i), l);
                }
            }
            // Translucent (glass, water, ice): after every opaque copy, back to front, blended, without depth writes,
            // with the same state vanilla sets for its own TRANSLUCENT pass.
            VISIBLE_T.clear();
            for (int i = 0, n = VISIBLE.size(); i < n; i++) {
                Entry en = VISIBLE.get(i);
                // A section vanilla shows draws its own translucent layer, or has none. A copy's glass or water over it could
                // only be stale, and then it floats where nothing translucent is (user screenshots, 2026-09-12).
                if (en.ids[TRANSLUCENT] > 0 && (!en.vanillaNow || en.hidingVanilla)) {
                    double dx = en.x + 8 - camX;
                    double dy = en.y + 8 - camY;
                    double dz = en.z + 8 - camZ;
                    en.dist2 = dx * dx + dy * dy + dz * dz;
                    VISIBLE_T.add(en);
                }
            }
            lastDrawnTranslucent = VISIBLE_T.size();
            if (!VISIBLE_T.isEmpty()) {
                Collections.sort(VISIBLE_T, (a, b) -> Double.compare(b.dist2, a.dist2));
                GlStateManager.func_179141_d();
                GlStateManager.func_179092_a(GL11.GL_GREATER, 0.1f);
                GlStateManager.func_179147_l();
                GlStateManager.func_179120_a(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
                GlStateManager.func_179132_a(false);
                try {
                    for (int i = 0, n = VISIBLE_T.size(); i < n; i++) {
                        drawLayer(VISIBLE_T.get(i), TRANSLUCENT);
                    }
                } finally {
                    GlStateManager.func_179132_a(true);
                    if (!blend) {
                        GlStateManager.func_179084_k();
                    }
                }
            }
        } finally {
            if (shaders) {
                ShaderPack.postChunkLayer(BlockRenderLayer.SOLID);
            }
            OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, 0);
            GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
            GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
            GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            if (alpha) {
                GlStateManager.func_179141_d();
            } else {
                GlStateManager.func_179118_c();
            }
            if (!lightmap) {
                mc.field_71460_t.func_175072_h();
            }
            if (fog) {
                GlStateManager.func_179127_m();
            }
            GlStateManager.func_179128_n(GL11.GL_PROJECTION);
            GlStateManager.func_179121_F();
            GlStateManager.func_179128_n(GL11.GL_MODELVIEW);
            if (!shaders) {
                GlStateManager.func_179086_m(GL11.GL_DEPTH_BUFFER_BIT);
            }
        }
    }

    /** One layer of one copy, with the vertex layout and RenderChunk transform VboRenderList uses. */
    private static void drawLayer(Entry en, int l) {
        int id = en.ids[l];
        if (id <= 0) {
            return;
        }
        if (en.stride != stride) {
            // taken in the other vertex layout (a shader pack was switched on or off since): its bytes mean nothing here
            return;
        }
        OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, id);
        if (shaders) {
            if (!ShaderPack.setupPointers()) {
                return;
            }
        } else {
            GL11.glVertexPointer(3, GL11.GL_FLOAT, VS, 0L);
            GL11.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, VS, 12L);
            GL11.glTexCoordPointer(2, GL11.GL_FLOAT, VS, 16L);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77476_b);
            GL11.glTexCoordPointer(2, GL11.GL_SHORT, VS, 24L);
            OpenGlHelper.func_77472_b(OpenGlHelper.field_77478_a);
        }
        GlStateManager.func_179094_E();
        GlStateManager.func_179109_b((float) (en.x - camX), (float) (en.y - camY), (float) (en.z - camZ));
        GlStateManager.func_179109_b(-8.0f, -8.0f, -8.0f);
        GlStateManager.func_179152_a(1.000001f, 1.000001f, 1.000001f);
        GlStateManager.func_179109_b(8.0f, 8.0f, 8.0f);
        GL11.glDrawArrays(GL11.GL_QUADS, 0, en.counts[l]);
        GlStateManager.func_179121_F();
    }

    /** True once vanilla may draw the section alone: same geometry as the copy, or no vanilla change for SETTLE_NANOS. */
    /**
     * True once vanilla may show the section alone: its geometry equals the copy, or the server reported a change newer
     * than the copy (the copy is known to be outdated), or vanilla has been quiet for SETTLE_NANOS with no rebuild pending.
     * A static city settles through the first rule as soon as all its tile entities have arrived.
     */
    private static boolean settled(Entry e, long now, RenderChunk rc) {
        if (e.handedOver) {
            return true;
        }
        boolean same = true;
        for (int l = 0; l < LAYERS; l++) {
            if (e.hash[l] != e.vanillaHash[l]) {
                same = false;
                break;
            }
        }
        boolean quiet = e.vanillaChanged == 0L || (now - e.vanillaChanged > SETTLE_NANOS && !rc.func_178569_m());
        if (same || quiet || outdated(e.key, e.geomTime)) {
            e.handedOver = true;
            return true;
        }
        settling++;
        return false;
    }

    /** Column-major 4x4: out = a * b. */
    private static void multiply(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) {
                    s += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = s;
            }
        }
    }

    private static void extractPlanes(float[] m) {
        // row r of a column-major matrix: (m[r], m[4+r], m[8+r], m[12+r])
        for (int i = 0; i < 6; i++) {
            int axis = i / 2;
            float sign = (i % 2 == 0) ? 1f : -1f;
            float a = m[3] + sign * m[axis];
            float b = m[7] + sign * m[4 + axis];
            float c = m[11] + sign * m[8 + axis];
            float d = m[15] + sign * m[12 + axis];
            PLANES[i][0] = a;
            PLANES[i][1] = b;
            PLANES[i][2] = c;
            PLANES[i][3] = d;
        }
    }

    private static boolean boxVisible(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (float[] p : PLANES) {
            float px = p[0] >= 0f ? x1 : x0;
            float py = p[1] >= 0f ? y1 : y0;
            float pz = p[2] >= 0f ? z1 : z0;
            if (p[0] * px + p[1] * py + p[2] * pz + p[3] < 0f) {
                return false;
            }
        }
        return true;
    }

    // ================= disk-loaded sections =================

    private static long diskUploads;

    public static long budget() {
        return budgetNow;
    }

    /** Main thread. False when a live copy of that layer exists or vanilla shows the section itself. */
    public static boolean acceptFromDisk(long key, int layer, int x, int y, int z, ViewFrustum frustum) {
        if (!ENABLED || !supported()) {
            return false;
        }
        Entry e = ENTRIES.get(key);
        if (e != null && e.ids[layer] > 0) {
            return false;
        }
        if (frustum != null) {
            BlockPos probe = new BlockPos(x, y, z);
            RenderChunk rc = ((ViewFrustumAccessor) (Object) frustum).afterimage$getRenderChunk(probe);
            if (rc != null && rc.func_178568_j().func_177986_g() == key
                    && rc.func_178571_g() != CompiledChunk.field_178502_a && chunkLoaded(probe)) {
                return false;
            }
        }
        return bytes + data0Size(layer) <= budgetNow;
    }

    private static long data0Size(int layer) {
        return 0L;
    }

    /**
     * Main thread: upload one layer read from disk into a new GPU buffer. The cache keeps the vanilla layout, so with a
     * shader pack loaded the vertices get the pack's fields back first (VertexLayout).
     */
    public static void uploadLayer(long key, int x, int y, int z, int layer, java.nio.ByteBuffer data, long geomTime) {
        int raw = data.remaining();
        if (raw < VertexLayout.VANILLA * 4 || raw % (VertexLayout.VANILLA * 4) != 0) {
            return;
        }
        java.nio.ByteBuffer grown = null;
        java.nio.ByteBuffer use = data;
        if (stride != VertexLayout.VANILLA) {
            grown = VertexLayout.expand(data, stride);
            if (grown == null) {
                return;
            }
            use = grown;
        }
        try {
            int size = use.remaining();
            if (bytes + size > budgetNow) {
                return;
            }
            uploadBuffer(key, x, y, z, layer, use, size, geomTime);
        } finally {
            if (grown != null) {
                DirectPool.release(grown);
            }
        }
    }

    private static void uploadBuffer(long key, int x, int y, int z, int layer, java.nio.ByteBuffer data, int size,
            long geomTime) {
        int id = GL15.glGenBuffers();
        OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, id);
        if (storage()) {
            org.lwjgl.opengl.GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, data, 0);
        } else {
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        }
        OpenGlHelper.func_176072_g(GL15.GL_ARRAY_BUFFER, 0);
        Entry e = ENTRIES.get(key);
        if (e == null) {
            e = new Entry(key, x, y, z);
            e.geomTime = geomTime;
            ENTRIES.put(key, e);
        } else {
            e.geomTime = Math.min(e.geomTime, geomTime);
        }
        e.stride = stride;
        e.ids[layer] = id;
        e.counts[layer] = size / stride;
        e.hash[layer] = Disk.diskHash(key, layer);
        if (e.hash[layer] == 0L) {
            e.stale = true;
        }
        e.bytes += size;
        bytes += size;
        diskUploads++;
    }

    // ================= server change sync =================

    private static boolean chunkLoaded(int cx, int cz) {
        WorldClient w = Minecraft.func_71410_x().field_71441_e;
        if (w == null) {
            return false;
        }
        Chunk c = w.func_72863_F().func_186026_b(cx, cz);
        return c != null && !c.func_76621_g();
    }

    /** Latest server change tick known for the chunk of that section, 0 if none. */
    public static long changeTime(long sectionKey) {
        BlockPos p = BlockPos.func_177969_a(sectionKey);
        return CHANGES.get(ChunkPos.func_77272_a(p.func_177958_n() >> 4, p.func_177952_p() >> 4));
    }

    /** True when the server reported a change of that section's chunk after the given geometry tick. */
    public static boolean outdated(long sectionKey, long geomTime) {
        long t = changeTime(sectionKey);
        return t != 0L && t > geomTime + SYNC_TOLERANCE_TICKS;
    }

    /** Copy of the known change ticks, for the disk loader thread. */
    public static Long2LongOpenHashMap changesSnapshot() {
        return new Long2LongOpenHashMap(CHANGES);
    }

    /** Client thread: the server says chunk (cx, cz) changed at server tick t. */
    public static void invalidateChunk(int cx, int cz, long t) {
        long ck = ChunkPos.func_77272_a(cx, cz);
        if (CHANGES.get(ck) < t) {
            CHANGES.put(ck, t);
        }
        if (ENTRIES.isEmpty()) {
            return;
        }
        boolean loaded = chunkLoaded(cx, cz);
        for (int sy = 0; sy < 16; sy++) {
            long key = new BlockPos(cx << 4, sy << 4, cz << 4).func_177986_g();
            Entry e = ENTRIES.get(key);
            if (e == null || e.geomTime + SYNC_TOLERANCE_TICKS >= t) {
                continue;
            }
            if (loaded) {
                // vanilla has the chunk as it is now; the copy is replaced when the section leaves
                e.stale = true;
            } else {
                ENTRIES.remove(key);
                free(e);
                invalidated++;
            }
        }
    }

    // ================= fog =================

    /**
     * EntityViewRenderEvent.RenderFogEvent. Vanilla and OptiFine fire it only for normal linear fog (not liquids, not
     * blindness). While the far zone has content the fog is pushed out, so terrain and far zone fade into the sky together.
     */
    public static void onFog(Object entity, Object state, int mode, float farPlane) {
        int end = fogEnd;
        if (!ENABLED || shaders || mode < 0 || end <= farPlane || ENTRIES.isEmpty()) {
            return;
        }
        try {
            if (state instanceof IBlockState && ((IBlockState) state).func_185904_a().func_76224_d()) {
                return;
            }
            if (entity instanceof EntityLivingBase && ((EntityLivingBase) entity).func_70644_a(MobEffects.field_76440_q)) {
                return;
            }
            GlStateManager.func_179102_b(end * 0.6f);
            GlStateManager.func_179153_c((float) end);
        } catch (Throwable t) {
            fail("fog", t);
        }
    }

    // ================= control & stats =================

    public static void clearAll() {
        for (Entry e : ENTRIES.values()) {
            free(e);
        }
        ENTRIES.clear();
        bytes = 0;
        GEOM.clear();
        CHANGES.clear();
    }

    /** For hitch reports: far copies in VRAM, tracked vanilla section geometry, free VRAM. */
    public static String gpuState() {
        return "far " + (bytes >> 20) + "/" + (budgetNow >> 20) + " MB, sections live " + Capture.liveMb() + " MB, all VBOs live " + Capture.allLiveMb() + " MB, vram free " + (availableVram() >> 20) + " MB";
    }

    public static String summary() {
        return "far " + (ENABLED ? "ON" : "OFF") + (shaders ? " (shader pack, vertex " + stride + " bytes)" : "")
            + ": sections " + ENTRIES.size() + ", VRAM "
            + String.format("%.1f MB", bytes / 1048576.0) + " of " + (budgetNow >> 20) + "/" + (BUDGET >> 20) + " MB (lowered " + budgetLowered + "x, immutable " + storage + ", vram free " + (availableVram() >> 20) + " MB, all VBOs live " + Capture.allLiveMb() + " MB), captures " + captures + " (buffers taken " + stolen + ", stale refreshed " + refreshed + "), reused " + reused + ", settling frames " + settling + ", hiding vanilla sections " + lastHidden + ", fog " + fogEnd + ", invalidated by server " + invalidated
            + ", drops " + drops + ", evictions " + evictions + ", from disk " + diskUploads + " | last frame drawn " + lastDrawn + " (translucent " + lastDrawnTranslucent + ")"
            + ", vanilla " + lastSkippedVanilla + ", culled " + lastCulled + ", errors " + errors;
    }

    private static void fail(String where, Throwable t) {
        errors++;
        ENABLED = false;
        Capture.logError("far." + where, t);
    }
}
