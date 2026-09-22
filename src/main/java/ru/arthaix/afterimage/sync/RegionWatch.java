package ru.arthaix.afterimage.sync;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chunks that were edited while the server was not running.
 *
 * The far zone of a client is only dropped for chunks the server reports as changed, and the server only sees what it
 * changes itself. A world edited from the outside - a script that fills an ocean, moves a city, rewrites biomes - is
 * invisible to it, so clients keep drawing copies of what used to be there (buildings of the old city showing through
 * the new one, 2026-09-22).
 *
 * Minecraft's own region files answer this: the 4 KB table after the chunk offsets holds, for every chunk, the time it
 * was last written. A snapshot of those times is kept beside the change log; whatever moved on while the server was
 * down is recorded as changed, and the clients drop exactly those copies. Reading it costs one 8 KB header per region.
 */
final class RegionWatch {
    private static final int MAGIC = 0x41495257;
    private static final int VERSION = 1;
    private static final int CHUNKS = 1024;

    private RegionWatch() {
    }

    /** dimension -> chunk keys whose stored write time moved on since the snapshot. */
    static Map<Integer, List<Long>> offlineEdits(File saveRoot, File snapshot) {
        Map<Integer, List<Long>> out = new HashMap<Integer, List<Long>>();
        Map<String, int[]> before = read(snapshot);
        if (before.isEmpty()) {
            // nothing to compare against: this is the first start that watches the files
            return out;
        }
        for (Map.Entry<Integer, File> dim : regionDirs(saveRoot).entrySet()) {
            File[] files = dim.getValue().listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                int[] rz = regionOf(f);
                if (rz == null) {
                    continue;
                }
                int[] now = times(f);
                if (now == null) {
                    continue;
                }
                int[] old = before.get(key(dim.getKey(), rz[0], rz[1]));
                for (int i = 0; i < CHUNKS; i++) {
                    int was = old == null ? 0 : old[i];
                    if (now[i] != 0 && now[i] != was) {
                        int cx = (rz[0] << 5) + (i & 31);
                        int cz = (rz[1] << 5) + (i >> 5);
                        List<Long> keys = out.get(dim.getKey());
                        if (keys == null) {
                            keys = new ArrayList<Long>();
                            out.put(dim.getKey(), keys);
                        }
                        keys.add(chunkKey(cx, cz));
                    }
                }
            }
        }
        return out;
    }

    /** Writes down when every chunk of every region was last written. */
    static void write(File saveRoot, File snapshot) {
        Map<String, int[]> now = new HashMap<String, int[]>();
        for (Map.Entry<Integer, File> dim : regionDirs(saveRoot).entrySet()) {
            File[] files = dim.getValue().listFiles();
            if (files == null) {
                continue;
            }
            for (File f : files) {
                int[] rz = regionOf(f);
                if (rz == null) {
                    continue;
                }
                int[] times = times(f);
                if (times != null) {
                    now.put(key(dim.getKey(), rz[0], rz[1]), times);
                }
            }
        }
        if (now.isEmpty()) {
            return;
        }
        File tmp = new File(snapshot.getPath() + ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(now.size());
                for (Map.Entry<String, int[]> e : now.entrySet()) {
                    out.writeUTF(e.getKey());
                    for (int t : e.getValue()) {
                        out.writeInt(t);
                    }
                }
            }
            Files.move(tmp.toPath(), snapshot.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            System.out.println("[afterimage] could not write " + snapshot + ": " + t);
        }
    }

    private static Map<String, int[]> read(File snapshot) {
        Map<String, int[]> out = new HashMap<String, int[]>();
        if (!snapshot.isFile()) {
            return out;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(snapshot), 1 << 16))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                return out;
            }
            int regions = in.readInt();
            for (int r = 0; r < regions && r < 1 << 20; r++) {
                String name = in.readUTF();
                int[] times = new int[CHUNKS];
                for (int i = 0; i < CHUNKS; i++) {
                    times[i] = in.readInt();
                }
                out.put(name, times);
            }
        } catch (Throwable t) {
            System.out.println("[afterimage] could not read " + snapshot + ": " + t);
            out.clear();
        }
        return out;
    }

    /** The write times of a region's chunks, or null when the file cannot be read. */
    private static int[] times(File region) {
        try (RandomAccessFile in = new RandomAccessFile(region, "r")) {
            if (in.length() < 8192) {
                return null;
            }
            byte[] table = new byte[4096];
            in.seek(4096);
            in.readFully(table);
            int[] out = new int[CHUNKS];
            for (int i = 0; i < CHUNKS; i++) {
                int o = i * 4;
                out[i] = ((table[o] & 0xFF) << 24) | ((table[o + 1] & 0xFF) << 16)
                        | ((table[o + 2] & 0xFF) << 8) | (table[o + 3] & 0xFF);
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** dimension id -> its region folder. */
    private static Map<Integer, File> regionDirs(File saveRoot) {
        Map<Integer, File> out = new HashMap<Integer, File>();
        if (saveRoot == null) {
            return out;
        }
        File overworld = new File(saveRoot, "region");
        if (overworld.isDirectory()) {
            out.put(0, overworld);
        }
        File[] kids = saveRoot.listFiles();
        if (kids != null) {
            for (File k : kids) {
                String n = k.getName();
                if (!k.isDirectory() || !n.startsWith("DIM")) {
                    continue;
                }
                File region = new File(k, "region");
                if (!region.isDirectory()) {
                    continue;
                }
                try {
                    out.put(Integer.parseInt(n.substring(3)), region);
                } catch (NumberFormatException notADimension) {
                    // a folder that only looks like one
                }
            }
        }
        return out;
    }

    /** {rx, rz} of r.<rx>.<rz>.mca, null for anything else. */
    private static int[] regionOf(File f) {
        String n = f.getName();
        if (!n.startsWith("r.") || !n.endsWith(".mca")) {
            return null;
        }
        String[] parts = n.substring(2, n.length() - 4).split("\\.");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (NumberFormatException notARegion) {
            return null;
        }
    }

    private static String key(int dim, int rx, int rz) {
        return dim + ":" + rx + ":" + rz;
    }

    /** The same chunk key ChunkPos uses. */
    private static long chunkKey(int cx, int cz) {
        return (long) cx & 4294967295L | ((long) cz & 4294967295L) << 32;
    }
}
