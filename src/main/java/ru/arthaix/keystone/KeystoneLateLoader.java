package ru.arthaix.keystone;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraftforge.fml.common.Loader;
import zone.rong.mixinbooter.Context;
import zone.rong.mixinbooter.ILateMixinLoader;

/**
 * Mixin configurations that patch other mods (formerly the late loaders of ltfix, cbbakecache, umctickfix and opffix).
 * Each is queued only when the mod it patches is installed, so the jar also works without, say, OnlinePictureFrame.
 */
public class KeystoneLateLoader implements ILateMixinLoader {
    private static final Logger LOG = LogManager.getLogger("keystone");

    /** mixin configuration, mod id it needs */
    private static final String[][] CONFIGS = {
        { "mixins.ltfix.json", "littletiles" },
        { "mixins.cbbakecache.json", "chiselsandbits" },
        { "mixins.umctickfix.json", "universalmodcore" },
        { "mixins.irfarumc.json", "universalmodcore" },
        { "mixins.opffix.json", "opframe" },
        { "mixins.ivlight.json", "mts" },
        { "mixins.ivsign.json", "mts" },
    };

    @Override
    public List<String> getMixinConfigs() {
        List<String> out = new ArrayList<String>();
        for (String[] c : CONFIGS) {
            out.add(c[0]);
        }
        return out;
    }

    @Override
    public boolean shouldMixinConfigQueue(Context context) {
        String mod = requiredMod(context.mixinConfig());
        return mod == null || report(context.mixinConfig(), mod, context.isModPresent(mod));
    }

    @Override
    public boolean shouldMixinConfigQueue(String config) {
        String mod = requiredMod(config);
        return mod == null || report(config, mod, Loader.isModLoaded(mod));
    }

    private static String requiredMod(String config) {
        for (String[] c : CONFIGS) {
            if (c[0].equals(config)) {
                return c[1];
            }
        }
        return null;
    }

    private static boolean report(String config, String mod, boolean present) {
        LOG.info("[keystone] {} {}", config, present ? "queued" : "skipped: " + mod + " is not installed");
        return present;
    }
}
