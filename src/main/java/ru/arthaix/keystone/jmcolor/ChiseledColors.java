package ru.arthaix.keystone.jmcolor;

import journeymap.client.mod.IBlockColorProxy;
import journeymap.client.mod.IModBlockHandler;
import journeymap.client.mod.ModBlockDelegate;
import journeymap.client.model.BlockMD;
import journeymap.client.model.ChunkMD;
import mod.chiselsandbits.chiseledblock.TileEntityBlockChiseled;
import mod.chiselsandbits.chiseledblock.data.VoxelBlob;
import mod.chiselsandbits.helpers.ModUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

/**
 * The colour of a Chisels &amp; Bits block for JourneyMap: for every column of bits the highest bit that is not air
 * gives its colour, and the columns are averaged — the block is drawn in the colours it was chiselled from.
 *
 * Public with a no-argument constructor: JourneyMap builds the handler itself when it resets its own list.
 */
public class ChiseledColors implements IModBlockHandler, IBlockColorProxy {
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
        if (JmColor.broken("chiselsandbits")) {
            return 0;
        }
        try {
            TileEntity te = JmColor.tileAt(chunkMD, pos);
            if (!(te instanceof TileEntityBlockChiseled)) {
                return 0;
            }
            VoxelBlob blob = ((TileEntityBlockChiseled) te).getBlob();
            if (blob == null) {
                return 0;
            }
            int detail = blob.detail;
            if (detail <= 0) {
                return 0;
            }
            TopSurface surface = TopSurface.get();
            for (int x = 0; x < detail; x++) {
                int cx0 = x * TopSurface.R / detail;
                int cx1 = (x + 1) * TopSurface.R / detail;
                if (cx1 <= cx0) {
                    cx1 = cx0 + 1;
                }
                for (int z = 0; z < detail; z++) {
                    for (int y = detail - 1; y >= 0; y--) {
                        int id = blob.get(x, y, z);
                        if (id == 0) {
                            continue;
                        }
                        IBlockState state = ModUtil.getStateById(id);
                        if (state == null) {
                            break;
                        }
                        int cz0 = z * TopSurface.R / detail;
                        int cz1 = (z + 1) * TopSurface.R / detail;
                        if (cz1 <= cz0) {
                            cz1 = cz0 + 1;
                        }
                        surface.cover(cx0, cz0, cx1, cz1, y, state);
                        break;
                    }
                }
            }
            return surface.color(chunkMD, pos);
        } catch (Throwable t) {
            JmColor.fail("chiselsandbits", t);
            return 0;
        }
    }
}
