package ru.arthaix.keystone.jmcolor;

import journeymap.client.model.ChunkMD;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;

/**
 * What a block of small parts looks like from above: the block is divided into a grid of columns, each column keeps the
 * highest part covering it, and the colours of those parts are averaged the way a map pixel of that block would show
 * them. Reused by every module that takes a block apart (LittleTiles, Chisels &amp; Bits).
 *
 * One instance per thread (JourneyMap maps on its own threads) is reused between blocks: no allocation per block.
 */
final class TopSurface {
    /** Columns per block edge. */
    static final int R = 16;

    private static final ThreadLocal<TopSurface> PER_THREAD = new ThreadLocal<TopSurface>() {
        @Override
        protected TopSurface initialValue() {
            return new TopSurface();
        }
    };

    private final int[] topY = new int[R * R];
    private final IBlockState[] topState = new IBlockState[R * R];

    /** Distinct states of the columns and how many columns each one covers. */
    private final IBlockState[] states = new IBlockState[64];
    private final int[] counts = new int[64];
    private int distinct;

    static TopSurface get() {
        TopSurface s = PER_THREAD.get();
        s.clear();
        return s;
    }

    private void clear() {
        for (int i = 0; i < topY.length; i++) {
            topY[i] = Integer.MIN_VALUE;
            topState[i] = null;
        }
        distinct = 0;
    }

    /**
     * One part of the block: the columns x0..x1-1, z0..z1-1 (in grid columns) are covered up to height y. The part is
     * kept where nothing higher was seen.
     */
    void cover(int x0, int z0, int x1, int z1, int y, IBlockState state) {
        if (state == null) {
            return;
        }
        int ax = x0 < 0 ? 0 : x0;
        int az = z0 < 0 ? 0 : z0;
        int bx = x1 > R ? R : x1;
        int bz = z1 > R ? R : z1;
        for (int x = ax; x < bx; x++) {
            int row = x * R;
            for (int z = az; z < bz; z++) {
                int i = row + z;
                if (y > topY[i]) {
                    topY[i] = y;
                    topState[i] = state;
                }
            }
        }
    }

    /** True when no part covered any column. */
    boolean isEmpty() {
        for (int i = 0; i < topY.length; i++) {
            if (topState[i] != null) {
                return false;
            }
        }
        return true;
    }

    /**
     * The colour of the block for the map: every column's part contributes the colour JourneyMap gives that block,
     * weighted by how many columns it covers. Returns 0 when nothing inside has a colour.
     */
    int color(ChunkMD chunkMD, BlockPos pos) {
        distinct = 0;
        for (int i = 0; i < topState.length; i++) {
            IBlockState s = topState[i];
            if (s != null) {
                add(s);
            }
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int i = 0; i < distinct; i++) {
            int c = JmColor.colorOf(states[i], chunkMD, pos);
            if (c == JmColor.NO_COLOR) {
                continue;
            }
            int k = counts[i];
            r += (long) ((c >> 16) & 0xFF) * k;
            g += (long) ((c >> 8) & 0xFF) * k;
            b += (long) (c & 0xFF) * k;
            n += k;
        }
        if (n == 0) {
            return 0;
        }
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    private void add(IBlockState state) {
        for (int i = 0; i < distinct; i++) {
            if (states[i] == state) {
                counts[i]++;
                return;
            }
        }
        if (distinct == states.length) {
            // more different blocks in one block than we count: the rest goes to the first one, the colour barely moves
            counts[0]++;
            return;
        }
        states[distinct] = state;
        counts[distinct] = 1;
        distinct++;
    }
}
