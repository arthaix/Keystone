package ru.arthaix.keystone;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLConstructionEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import ru.arthaix.keystone.chunkkeep.ChunkKeep;
import ru.arthaix.keystone.ltfix.LtFix;

/**
 * Keystone: performance and bug fixes for Minecraft 1.12.2 worlds with huge Chisels & Bits and LittleTiles builds,
 * in one jar for the client and the server.
 *
 * Each former mod is a module that keeps its package, mixin configuration and -D switches:
 *   teunloadbatch  Minecraft/Forge: tile entity lists, chunk packets, block edits, render builders (both sides)
 *   chunkkeep      keep radius, pinned city chunks and their preload (dedicated server only)
 *   packetbudget   client packet processing spread over frames (client)
 *   ltfix          LittleTiles 1.5.14 fixes, lag metrics and freeze logs (both sides, with LittleTiles)
 *   cbbakecache    Chisels & Bits chunk baking cache (client, with Chisels & Bits)
 *   umctickfix     UniversalModCore render scans (client, with UniversalModCore)
 *   opffix         OnlinePictureFrame downloads (client, with OnlinePictureFrame)
 *   vfcompat       item icons of Immersive Vehicles packs VintageFix cannot read (client)
 *   ivlight        Immersive Vehicles signs and poles lit the same in every frame (client, with Immersive Vehicles)
 *   irfar          Immersive Railroading / UniversalModCore trains and rails visible to 1.5x render distance (both sides)
 *   nogen          world generators of chosen mods skipped, -Dkeystone.nogen (both sides)
 *   jmcolor        JourneyMap draws LittleTiles, Chisels & Bits and Immersive Railroading track in the colours of
 *                  what is inside them (client, with JourneyMap)
 *   Afterimage     far city copies and disk cache (client) and chunk change tracking (server); built from its own
 *                  repository and kept as its own mod "afterimage", because client and server recognise each other's
 *                  far-city sync by that mod id
 *
 * Early mixins: KeystoneCore. Mixins into other mods: KeystoneLateLoader, queued only when that mod is installed.
 * This class runs the start-up work that the modules' own mod classes did (Afterimage keeps its mod class).
 */
@Mod(modid = Keystone.MODID, name = "Keystone", version = Keystone.VERSION,
     dependencies = "required-after:mixinbooter@[10.0,);after:littletiles;after:chiselsandbits;after:universalmodcore;"
                    + "after:opframe;after:journeymap",
     acceptableRemoteVersions = "*")
public class Keystone {
    public static final String MODID = "keystone";
    public static final String VERSION = "1.3.6";

    private ChunkKeep chunkKeep;

    @Mod.EventHandler
    public void construct(FMLConstructionEvent event) {
        if (event.getSide().isClient()) {
            // before the first atlas stitch (see PackIconSprites)
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ru.arthaix.keystone.vfcompat.PackIconSprites());
        }
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (Loader.isModLoaded("littletiles")) {
            new LtFix().preInit(event);
        }
        if (event.getSide().isClient() && Loader.isModLoaded("universalmodcore") && ru.arthaix.keystone.irfar.IrFar.ENABLED) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ru.arthaix.keystone.irfar.IrFarClient());
        }
        if (event.getSide().isServer()) {
            this.chunkKeep = new ChunkKeep();
            this.chunkKeep.preInit(event);
        }
    }

    @Mod.EventHandler
    public void loadComplete(net.minecraftforge.fml.common.event.FMLLoadCompleteEvent event) {
        if (net.minecraftforge.fml.common.FMLCommonHandler.instance().getSide().isClient()) {
            // after JourneyMap's own set-up, before any world is mapped
            ru.arthaix.keystone.jmcolor.JmColor.init();
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        if (this.chunkKeep != null) {
            this.chunkKeep.serverStarting(event);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (this.chunkKeep != null) {
            this.chunkKeep.serverStopping(event);
        }
    }
}
