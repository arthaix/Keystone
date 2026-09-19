package ru.arthaix.keystone.jmcolor;

import cam72cam.immersiverailroading.tile.TileRailBase;
import cam72cam.immersiverailroading.tile.TileRailPreview;
import cam72cam.mod.block.BlockEntity;

import journeymap.client.mod.IBlockColorProxy;
import journeymap.client.mod.IModBlockHandler;
import journeymap.client.mod.ModBlockDelegate;
import journeymap.client.model.BlockMD;
import journeymap.client.model.ChunkMD;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * The colour of Immersive Railroading track for JourneyMap.
 *
 * Track is one block state for every piece of railway: the rails, the sleepers and the bed are a model drawn by the
 * tile entity, so the map painted whole lines in the one colour that state happened to get. The bed is the material the
 * track was laid on and it is what is mostly seen from above, so the colour is the bed's own colour (gravel, concrete,
 * whatever was used) with the share the sleepers and the rails cover on top of it, and snow on the track whitens it.
 *
 * Public with a no-argument constructor: JourneyMap builds the handler itself when it resets its own list.
 */
public class RailColors implements IModBlockHandler, IBlockColorProxy {
    /** Sleepers seen from above. */
    private static final int TIE = 0x4A3B2A;

    /** Rail heads, polished steel. */
    private static final int STEEL = 0x9A9A9E;

    private static final int SNOW = 0xEEEEF2;

    /** How much of a track block seen from above is bed, sleepers and rail; the rest of one is the other two. */
    private static final float BED = share("keystone.jmcolor.railbed", 0.62f);
    private static final float TIES = share("keystone.jmcolor.railties", 0.28f);
    private static final float RAILS = Math.max(0f, 1f - BED - TIES);

    private static float share(String property, float fallback) {
        try {
            String v = System.getProperty(property);
            if (v != null) {
                float f = Float.parseFloat(v);
                if (f >= 0f && f <= 1f) {
                    return f;
                }
            }
        } catch (RuntimeException ignored) {
            // the fallback stays
        }
        return fallback;
    }

    @Override
    public void initialize(BlockMD blockMD) {
        blockMD.setBlockColorProxy(this);
    }

    @Override
    public int getBlockColor(ChunkMD chunkMD, BlockMD blockMD, BlockPos pos) {
        int color = color(chunkMD, pos);
        return color != 0 ? color
                : ModBlockDelegate.INSTANCE.getDefaultBlockColorProxy().getBlockColor(chunkMD, blockMD, pos);
    }

    @Override
    public int deriveBlockColor(BlockMD blockMD, ChunkMD chunkMD, BlockPos pos) {
        int color = color(chunkMD, pos);
        return color != 0 ? color
                : ModBlockDelegate.INSTANCE.getDefaultBlockColorProxy().deriveBlockColor(blockMD, chunkMD, pos);
    }

    /** 0 for anything that is not track, so the rest of Immersive Railroading keeps its own colours. */
    private static int color(ChunkMD chunkMD, BlockPos pos) {
        if (JmColor.broken()) {
            return 0;
        }
        try {
            TileEntity te = JmColor.tileAt(chunkMD, pos);
            if (!(te instanceof cam72cam.mod.block.tile.TileEntity)) {
                return 0;
            }
            BlockEntity instance = ((cam72cam.mod.block.tile.TileEntity) te).instance();
            if (!(instance instanceof TileRailBase) || instance instanceof TileRailPreview) {
                return 0;
            }
            TileRailBase rail = (TileRailBase) instance;
            int bed = bedColor(rail, chunkMD, pos);
            float bedShare = bed == JmColor.NO_COLOR ? 0f : BED;
            float rest = 1f - bedShare;
            float all = TIES + RAILS;
            float tieShare = all <= 0f ? rest : rest * (TIES / all);
            float railShare = rest - tieShare;
            float r = (bedShare > 0f ? ((bed >> 16) & 0xFF) * bedShare : 0f)
                    + ((TIE >> 16) & 0xFF) * tieShare + ((STEEL >> 16) & 0xFF) * railShare;
            float g = (bedShare > 0f ? ((bed >> 8) & 0xFF) * bedShare : 0f)
                    + ((TIE >> 8) & 0xFF) * tieShare + ((STEEL >> 8) & 0xFF) * railShare;
            float b = (bedShare > 0f ? (bed & 0xFF) * bedShare : 0f)
                    + (TIE & 0xFF) * tieShare + (STEEL & 0xFF) * railShare;
            int color = (int) r << 16 | (int) g << 8 | (int) b;
            int snow = rail.getSnowLayers();
            if (snow > 0) {
                float covered = snow * 0.12f;
                color = mix(SNOW, color, covered > 0.85f ? 0.85f : covered);
            }
            return color == 0 ? 1 : color;
        } catch (Throwable t) {
            JmColor.fail("immersiverailroading", t);
            return 0;
        }
    }

    /** JourneyMap's colour for the block the track was laid on, NO_COLOR when the track has no bed. */
    private static int bedColor(TileRailBase rail, ChunkMD chunkMD, BlockPos pos) {
        try {
            cam72cam.mod.item.ItemStack bed = rail.getRenderRailBed();
            net.minecraft.item.ItemStack stack = bed == null ? null : bed.internal;
            if (stack == null || stack.func_190926_b()) {
                return JmColor.NO_COLOR;
            }
            Block block = Block.func_149634_a(stack.func_77973_b());
            if (block == null || block == Blocks.field_150350_a) {
                return JmColor.NO_COLOR;
            }
            IBlockState state = block.func_176203_a(stack.func_77952_i());
            return JmColor.colorOf(state, chunkMD, pos);
        } catch (Throwable t) {
            return JmColor.NO_COLOR;
        }
    }

    /** weight of the first colour, the rest of the second. */
    private static int mix(int a, int b, float weight) {
        float w = weight < 0f ? 0f : weight > 1f ? 1f : weight;
        int r = (int) (((a >> 16) & 0xFF) * w + ((b >> 16) & 0xFF) * (1f - w));
        int g = (int) (((a >> 8) & 0xFF) * w + ((b >> 8) & 0xFF) * (1f - w));
        int bl = (int) ((a & 0xFF) * w + (b & 0xFF) * (1f - w));
        return r << 16 | g << 8 | bl;
    }
}
