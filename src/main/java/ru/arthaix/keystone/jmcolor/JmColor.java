package ru.arthaix.keystone.jmcolor;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import journeymap.client.mod.IModBlockHandler;
import journeymap.client.mod.ModBlockDelegate;
import journeymap.client.model.BlockMD;
import journeymap.client.model.ChunkMD;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.Loader;

/**
 * JourneyMap colours for blocks whose looks live in a tile entity.
 *
 * A LittleTiles or Chisels &amp; Bits block is one block state for every build in the world: its own texture says
 * nothing about what was built, so the map paints a whole city in the one colour that state happened to get. What the
 * block really shows is in its tile entity, so these blocks are taken apart per map pixel: the highest part of every
 * column is found and their colours are averaged (TopSurface), which is what a pixel seen from above is.
 *
 * JourneyMap already has the hook for this: ModBlockDelegate keeps one IModBlockHandler per mod id and asks it to set
 * an IBlockColorProxy on each BlockMD of that mod, and the proxy is asked for a colour per position. The handler map is
 * private and filled from a fixed list, so the two handlers are put in by reflection, into the class list as well so
 * they survive JourneyMap's own reset.
 *
 * -Dkeystone.jmcolor=false leaves JourneyMap alone.
 */
public final class JmColor {
    public static final boolean ENABLED = !"false".equals(System.getProperty("keystone.jmcolor"));

    /** No colour for this state: it is air, ignored, or a block of parts itself. */
    static final int NO_COLOR = Integer.MIN_VALUE;

    static final String LITTLETILES = "littletiles";
    static final String CHISELS = "chiselsandbits";

    private static final Logger LOG = LogManager.getLogger("keystone");

    private static boolean failed;

    private JmColor() {
    }

    /** Client, after every mod is loaded and before any world is mapped. */
    public static void init() {
        if (!ENABLED || !Loader.isModLoaded("journeymap")) {
            return;
        }
        try {
            ModBlockDelegate delegate = ModBlockDelegate.INSTANCE;
            Map<String, Class<? extends IModBlockHandler>> classes = field(delegate, "handlerClasses");
            Map<String, IModBlockHandler> handlers = field(delegate, "handlers");
            int added = 0;
            if (Loader.isModLoaded(LITTLETILES)) {
                classes.put(LITTLETILES, LittleTilesColors.class);
                handlers.put(LITTLETILES, new LittleTilesColors());
                added++;
            }
            if (Loader.isModLoaded(CHISELS)) {
                classes.put(CHISELS, ChiseledColors.class);
                handlers.put(CHISELS, new ChiseledColors());
                added++;
            }
            if (added > 0) {
                LOG.info("[jmcolor] JourneyMap draws " + added + (added == 1 ? " kind" : " kinds")
                        + " of block in the colours of what is inside them");
            }
        } catch (Throwable t) {
            fail("init", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> field(ModBlockDelegate delegate, String name) throws Exception {
        Field f = ModBlockDelegate.class.getDeclaredField(name);
        f.setAccessible(true);
        Object v = f.get(delegate);
        if (v == null) {
            HashMap<String, T> fresh = new HashMap<String, T>();
            f.set(delegate, fresh);
            return fresh;
        }
        return (Map<String, T>) v;
    }

    /** JourneyMap's colour for one block state at that position, NO_COLOR when it has none. */
    static int colorOf(IBlockState state, ChunkMD chunkMD, BlockPos pos) {
        try {
            BlockMD md = BlockMD.get(state);
            if (md == null || md.isIgnore()) {
                return NO_COLOR;
            }
            String domain = md.getBlockDomain();
            if (domain != null) {
                domain = domain.toLowerCase();
                if (LITTLETILES.equals(domain) || CHISELS.equals(domain)) {
                    // a block of parts inside a block of parts: no colour of its own to give
                    return NO_COLOR;
                }
            }
            int color = md.getBlockColor(chunkMD, pos);
            return color == 0 ? NO_COLOR : color;
        } catch (Throwable t) {
            fail("color", t);
            return NO_COLOR;
        }
    }

    /** The tile entity at that position, without creating one; null when there is none (chunk gone, other thread). */
    static net.minecraft.tileentity.TileEntity tileAt(ChunkMD chunkMD, BlockPos pos) {
        try {
            net.minecraft.world.chunk.Chunk chunk = chunkMD == null ? null : chunkMD.getChunk();
            if (chunk == null) {
                return null;
            }
            return chunk.func_177424_a(pos, net.minecraft.world.chunk.Chunk.EnumCreateEntityType.CHECK);
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean broken() {
        return failed;
    }

    static void fail(String what, Throwable t) {
        if (!failed) {
            failed = true;
            LOG.warn("[jmcolor] " + what + " failed, JourneyMap keeps its own colours", t);
        }
    }
}
