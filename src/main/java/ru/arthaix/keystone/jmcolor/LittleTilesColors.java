package ru.arthaix.keystone.jmcolor;

import com.creativemd.creativecore.common.utils.type.Pair;
import com.creativemd.littletiles.common.tile.LittleTile;
import com.creativemd.littletiles.common.tile.math.box.LittleBox;
import com.creativemd.littletiles.common.tileentity.TileEntityLittleTiles;
import com.creativemd.littletiles.common.util.grid.LittleGridContext;

import journeymap.client.mod.IBlockColorProxy;
import journeymap.client.mod.IModBlockHandler;
import journeymap.client.mod.ModBlockDelegate;
import journeymap.client.model.BlockMD;
import journeymap.client.model.ChunkMD;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * The colour of a LittleTiles block for JourneyMap: the highest tile over every column of the block decides that
 * column's colour, and the columns are averaged. A wall of white concrete is white on the map, a glass roof is the
 * colour of glass, a road of several materials is their mix, instead of the one colour the LittleTiles block state has.
 *
 * Public with a no-argument constructor: JourneyMap builds the handler itself when it resets its own list.
 */
public class LittleTilesColors implements IModBlockHandler, IBlockColorProxy {
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

    /** 0 when there is nothing to take the colour from and JourneyMap should do what it always did. */
    private static int color(ChunkMD chunkMD, BlockPos pos) {
        if (JmColor.broken("littletiles")) {
            return 0;
        }
        try {
            TileEntity te = JmColor.tileAt(chunkMD, pos);
            if (!(te instanceof TileEntityLittleTiles)) {
                return 0;
            }
            TileEntityLittleTiles tiles = (TileEntityLittleTiles) te;
            if (!tiles.hasLoaded()) {
                // its tiles are still being read: asking now throws inside LittleTiles
                return 0;
            }
            LittleGridContext context = tiles.getContext();
            int grid = context == null ? 0 : context.size;
            if (grid <= 0) {
                return 0;
            }
            TopSurface surface = TopSurface.get();
            for (Pair<?, LittleTile> pair : tiles.allTiles()) {
                LittleTile tile = pair.value;
                if (tile == null || tile.invisible) {
                    continue;
                }
                LittleBox box = tile.getBox();
                if (box == null) {
                    continue;
                }
                surface.cover(low(box.minX, grid), low(box.minZ, grid), high(box.maxX, grid), high(box.maxZ, grid),
                        box.maxY, tile.getBlockState());
            }
            return surface.color(chunkMD, pos);
        } catch (Throwable t) {
            JmColor.fail("littletiles", t);
            return 0;
        }
    }

    /** First column a tile edge at that grid coordinate covers. */
    private static int low(int coord, int grid) {
        return coord * TopSurface.R / grid;
    }

    /** One past the last column, so a tile thinner than a column still covers one. */
    private static int high(int coord, int grid) {
        int c = (coord * TopSurface.R + grid - 1) / grid;
        return c <= 0 ? 1 : c;
    }
}
