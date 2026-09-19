package ru.arthaix.keystone.irfar.umcmixin;

import org.spongepowered.asm.mixin.Mixin;

import ru.arthaix.keystone.irfar.IrFar;
import ru.arthaix.keystone.irfar.IrFarClient;

/**
 * Minecraft draws a tile entity only within 64 blocks of the camera. Immersive Railroading's track is drawn by tile
 * entities, so rails turned up piece by piece right in front of the player while the city behind them stood in full
 * view. UniversalModCore's tile entities now reach as far as the rest of the far zone; the chunks the game draws
 * anyway are the real limit, so this costs nothing beyond the render distance.
 */
@Mixin(targets = "cam72cam.mod.block.tile.TileEntity", remap = false)
public abstract class MixinTileEntityFar {

    /** getMaxRenderDistanceSquared */
    public double func_145833_n() {
        if (!IrFar.ENABLED) {
            return 4096.0;
        }
        double r = IrFarClient.rangeBlocks();
        return r * r;
    }
}
