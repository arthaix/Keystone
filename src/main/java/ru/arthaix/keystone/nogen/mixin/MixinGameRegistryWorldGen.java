package ru.arthaix.keystone.nogen.mixin;

import java.util.Random;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;
import net.minecraftforge.fml.common.registry.GameRegistry;
import ru.arthaix.keystone.nogen.NoGen;

/** Forge runs every registered world generator for each new chunk; the ones NoGen names are not run. */
@Mixin(value = GameRegistry.class, remap = false)
public abstract class MixinGameRegistryWorldGen {
    @Redirect(method = "generateWorld(IILnet/minecraft/world/World;Lnet/minecraft/world/gen/IChunkGenerator;Lnet/minecraft/world/chunk/IChunkProvider;)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraftforge/fml/common/IWorldGenerator;generate(Ljava/util/Random;IILnet/minecraft/world/World;Lnet/minecraft/world/gen/IChunkGenerator;Lnet/minecraft/world/chunk/IChunkProvider;)V"),
              require = 0)
    private static void nogen$skipGenerator(IWorldGenerator generator, Random random, int chunkX, int chunkZ,
                                            World world, IChunkGenerator chunkGenerator, IChunkProvider chunkProvider) {
        if (!NoGen.blocked(generator)) {
            generator.generate(random, chunkX, chunkZ, world, chunkGenerator, chunkProvider);
        }
    }
}
