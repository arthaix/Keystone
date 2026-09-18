package ru.arthaix.afterimage;

import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import ru.arthaix.afterimage.sync.ChangeTracker;
import ru.arthaix.afterimage.sync.Net;
import ru.arthaix.afterimage.sync.ServerEvents;

/**
 * Afterimage. On the client: far zone and disk cache of section geometry. On a server (optional, dedicated or
 * integrated): chunk change tracking, so clients drop cached geometry of chunks that changed while they were away.
 * Either side works without the other. Compiled against the Forge dev jar; only calls Forge API and plain Java.
 */
@Mod(modid = Afterimage.MODID, name = "Afterimage", version = Afterimage.VERSION,
     dependencies = "required-after:mixinbooter@[10.0,)", acceptableRemoteVersions = "*")
public class Afterimage {
    public static final String MODID = "afterimage";
    public static final String VERSION = "0.6.0";

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Net.init();
        MinecraftForge.EVENT_BUS.register(new ServerEvents());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            ClientBoot.init(event.getModConfigurationDirectory().getParentFile());
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        ChangeTracker.start(DimensionManager.getCurrentSaveRootDirectory());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ChangeTracker.stop();
    }
}
