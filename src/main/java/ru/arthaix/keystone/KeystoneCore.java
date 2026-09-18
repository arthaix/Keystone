package ru.arthaix.keystone;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * Coremod entry of Keystone: mixin configurations that patch Minecraft and Forge themselves and therefore apply before
 * mods load. They were the separate coremods teunloadbatch, chunkkeep, packetbudget and Afterimage; their configurations
 * are unchanged except that chunkkeep's mixins are listed for the dedicated server only (it was only ever installed
 * there, and one of them changes World ticking).
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("KeystoneCore")
public class KeystoneCore implements IFMLLoadingPlugin, IEarlyMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.teunloadbatch.json", "mixins.chunkkeep.json", "mixins.packetbudget.json", "mixins.afterimage.json",
            "mixins.irfar.json", "mixins.nogen.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
