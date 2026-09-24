package ru.arthaix.keystone.maplive;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Where the players are, for the Artburg web map: once a second the dedicated server writes
 * {@code <server>/maplive/players.json} with every player's name, dimension, position, heading and whether they are
 * a spectator, and the map's own server hands it to the browsers together with the cursors of the people looking at
 * the map. The file is written by a thread of its own, through a temporary file and a rename, so the server thread
 * never waits for the disk and a reader never sees half a file. {@code "t"} is the time of writing: a file that stops
 * getting newer means the server is gone. {@code -Dkeystone.maplive=false} turns it off, {@code -Dkeystone.maplive.ticks}
 * sets how often (20).
 */
public final class MapLive {
    public static final boolean ENABLED = !"false".equals(System.getProperty("keystone.maplive"));
    private static final int EVERY = Math.max(5, Integer.getInteger("keystone.maplive.ticks", 20));

    /** One player as the map needs them; plain data, so the JSON can be tested without Minecraft. */
    public static final class Seen {
        final String name, uuid;
        final int dim;
        final double x, y, z;
        final float yaw;
        final boolean spectator;

        public Seen(String name, String uuid, int dim, double x, double y, double z, float yaw, boolean spectator) {
            this.name = name; this.uuid = uuid; this.dim = dim; this.x = x; this.y = y; this.z = z;
            this.yaw = yaw; this.spectator = spectator;
        }
    }

    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Keystone maplive");
        t.setDaemon(true);
        return t;
    });
    private int ticks;

    public MapLive(File serverDir) {
        this.file = new File(new File(serverDir, "maplive"), "players.json");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++this.ticks % EVERY != 0) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        List<Seen> seen = new ArrayList<>();
        for (EntityPlayerMP p : server.func_184103_al().func_181057_v()) {
            seen.add(new Seen(p.func_70005_c_(), p.func_110124_au().toString(), p.field_71093_bK,
                              p.field_70165_t, p.field_70163_u, p.field_70161_v, p.field_70177_z, p.func_175149_v()));
        }
        final String json = json(System.currentTimeMillis(), false, seen);
        this.io.execute(() -> write(json));
    }

    /** The server is going down: say so at once, so the map clears its players instead of waiting for them to age. */
    public void stop() {
        this.io.execute(() -> write(json(System.currentTimeMillis(), true, new ArrayList<>())));
        this.io.shutdown();
        try {
            this.io.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(String json) {
        try {
            File dir = this.file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(dir, "players.json.tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // the map only misses one second of positions; the next write tries again
        }
    }

    public static String json(long time, boolean stopped, List<Seen> players) {
        StringBuilder b = new StringBuilder(64 + players.size() * 128);
        b.append("{\"t\":").append(time).append(",\"stopped\":").append(stopped).append(",\"players\":[");
        for (int i = 0; i < players.size(); i++) {
            Seen s = players.get(i);
            if (i > 0) {
                b.append(',');
            }
            b.append("{\"name\":");
            str(b, s.name);
            b.append(",\"uuid\":");
            str(b, s.uuid);
            b.append(",\"dim\":").append(s.dim)
             .append(",\"x\":").append(round(s.x)).append(",\"y\":").append(round(s.y)).append(",\"z\":").append(round(s.z))
             .append(",\"yaw\":").append(Math.round(((s.yaw % 360f) + 360f) % 360f))
             .append(",\"spectator\":").append(s.spectator).append('}');
        }
        return b.append("]}").toString();
    }

    private static String round(double v) {
        return Double.isFinite(v) ? String.valueOf(Math.round(v * 10) / 10.0) : "0";
    }

    private static void str(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        b.append('"');
    }
}
