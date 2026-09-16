# Keystone

Keystone is a Forge mod for Minecraft 1.12.2 that keeps huge builds playable. It is meant for servers and players whose
worlds are full of [Chisels & Bits](https://www.curseforge.com/minecraft/mc-mods/chisels-bits) and
[LittleTiles](https://www.curseforge.com/minecraft/mc-mods/littletiles) blocks: detailed cities, interiors, statues,
anything made of many small pieces.

Every chiseled or LittleTiles block carries extra data the game has to keep track of. A few thousand are no problem; a
city has millions, and at that size parts of Minecraft, Forge and these mods, written with small worlds in mind, freeze
the server on large edits, make every flight stutter and lose LittleTiles blocks. Keystone fixes those spots without
changing the game itself: blocks, builds and mod behaviour stay the same, only the slow work is done faster, in the
background or in smaller steps. One jar serves the client and the server.

Keystone includes **Afterimage**, which keeps everything you have seen on screen far beyond the render distance.

Author: Aleksei Usenko (arthaix). All rights reserved: you may use the released jar, but not modify or redistribute it (see LICENSE).

## Installation

Put `z-keystone-<version>.jar` into the `mods` folder of the client and of the server, together with MixinBooter 10.x.
Every part loads only where it applies: server fixes on a dedicated server, client fixes on the client, and the fixes
for LittleTiles, Chisels & Bits, UniversalModCore and OnlinePictureFrame only when that mod is installed.

- Keep the file name. Forge loads coremods in file-name order, and a name that sorts before `mixinbooter` stops the
  game at launch with `NoClassDefFoundError: zone/rong/mixinbooter/IEarlyMixinLoader`.
- Remove the separate jars Keystone replaces: `z-afterimage`, `teunloadbatch`, `ltfix`, `z-chunkkeep`, `packetbudget`,
  `cbbakecache`, `umctickfix`, `opffix`.
- OptiFine: **Render Regions off** (Afterimage).
- The mod list shows two entries, Keystone and Afterimage: client and server find each other's Afterimage by that name.

## What it fixes

### Server

- **Tile entity lists in constant time.** `World.loadedTileEntityList` is an indexed list: remove, removeAll and
  contains no longer scan millions of tile entities, which froze the server on WorldEdit operations and chunk unloads.
- **Large block edits.** A replaced tile entity leaves the ticking list in an ordered batch instead of a scan per block;
  Forge's list of changed blocks per chunk no longer compares each change with all earlier ones; LittleTiles' neighbour
  update queue checks for duplicates with a hash set. These three were 72% of the server time of large WorldEdit edits.
- **Chunk packets reuse tile data.** The update tags of unchanged Chisels & Bits and LittleTiles tile entities are
  serialized once and reused. The first 2000 and every 500th reuse are compared with a fresh tag; any difference turns
  the cache off.
- **Tile entity ticking** skips the loaded-chunk lookup for tile entities whose chunk cannot have unloaded since.
- **Chunk keep.** Chunks a player has received stay loaded and registered to them within a keep radius, so flying back
  and forth no longer unloads, reloads and re-sends them. Chunk packets get a time budget per tick, and chunks that
  enter view all at once (login, teleport) are sent nearest first over the following ticks.
- **City preload.** Chunks listed in `config/chunkkeep-pins.txt` (one `chunkX chunkZ` pair per line, `#` starts a
  comment) are loaded at start through Forge's async chunk IO, paced by tick time, and stay loaded.
- **Large edits no longer flood the connection.** A chunk with over 64 changed blocks in a tick is re-sent whole, tile
  entities included; an import or undo of tens of thousands of blocks did that for the same chunks every tick, for half
  a minute, and the player timed out behind gigabytes of chunk packets. A chunk is now re-sent whole at most every
  1.5 s, chunk sending waits while more than 12 MB is queued to the player, and big packets are compressed at level 1.
- **Huge chunks reach the player.** A chunk packet over 2 MB (a model imported at hundreds of LittleTiles tiles per
  block) disconnected the player with "unable to fit ... into 3" every time they came near. It is now sent as the
  chunk followed by packets with the rest of its tile entities, written together so nothing gets between them.
- **Builders fly.** Creative and spectator players are not pulled back by "moved too quickly".
- **No kick** with "Internal server error" when a modded entity has no bounding box yet.
- **LittleTiles loading** splits tile group NBT in linear time (it was quadratic, 29% of chunk loading on dense builds)
  and looks up tile constructors and block names once.

### Client

- **Afterimage**, see below.
- **LittleTiles data is parsed in the background.** Right after a chunk packet is decoded, the tiles of its LittleTiles
  tile entities are read on three worker threads; the client thread takes the result instead of parsing.
- **LittleTiles tiles never vanish.** Tile geometry is kept in memory instead of being read back from the chunk's GPU
  buffer, which made tiles disappear or show another chunk's bytes. Geometry not drawn for 30 s is packed until it is
  needed again. Tiles whose data arrives after their chunk was built are rendered again, and the rendering thread no
  longer spins and floods the log on tiles without data.
- **No "Direct buffer memory" crashes.** Every build result of a tile entity is copied into the Java heap the moment
  it is finished, so nothing of LittleTiles' geometry stays in direct memory, which the game only got back once the
  collector reached it: rebuilding a big import in view filled it and crashed the game. Each rendering thread reuses one
  build buffer instead of allocating one per tile and layer. Unpacked geometry has a budget (an eighth of the heap);
  above it everything not in use right now is packed at once, and above a fifth chunk workers wait briefly. When the
  heap itself is over three quarters full after collections, new copies stay in direct memory instead. A chunk buffer
  allocation that still fails is retried for up to 10 s. Render slots that move to sections without tiles forget the
  previous section's geometry instead of holding it.
- **Packet budget.** The scheduled-task queue, chunk packets included, runs for at most 10 ms per frame, and the tile
  entity tags of a chunk are applied 4 ms per frame. Nothing is dropped or reordered.
- **Chisels & Bits baking** filters each voxel blob once per state and layer instead of seven times per block, and its
  model caches are thread-safe (chunk worker crash).
- **UniversalModCore** tracks its tile entities incrementally instead of scanning every loaded tile entity each tick.
- **Render builders.** Three per chunk worker instead of ten, and builders that grew are replaced once the pool holds
  more than 1 GB of native memory. LittleTiles' direct VBO upload reuses one buffer per call site instead of allocating
  a chunk layer's worth of direct memory for every upload and read-back.
- **Screenshots** are written on a background thread.
- **Immersive Vehicles** textures are decoded off the client thread; **JourneyMap** writes and decodes region images in
  the background.
- **OnlinePictureFrame** downloads one picture at a time, so pictures stop failing with "Failed to parse GIF".
- **Immersive Vehicles pack icons with VintageFix.** A content pack whose ID has a character other than letters,
  digits, `_`, `-` or `.` (for example `miszkolights&signs`) showed the missing texture on all its items: VintageFix's
  texture scan does not accept such names. Keystone reads those items' models before the atlas is built and adds
  their icons itself.
- **Immersive Vehicles lit the same in every frame.** Immersive Vehicles draws vehicles, signs and poles at the end of
  the frame and turns lighting on through Minecraft's GL state cache. Mods that change GL state directly at that point
  (selection outlines, waypoint beams) left the cache wrong while they had something in view, and signs and poles
  flipped between shaded and flat bright as the camera moved. The GL state is now reset to match the cache before
  Immersive Vehicles draws.
- **Immersive Railroading stays in sight.** A dedicated server sends entities to a player only within its
  view-distance (80 blocks at 6) and only in chunks the player watches, so trains vanished as soon as you moved away,
  and rails disappeared with their chunks. Trains and other UniversalModCore entities are now sent to each player up to
  1.5x the render distance that player set (at most 1024 blocks), kept and animated where the client has no chunk and
  drawn there at sky light. Rails and other UniversalModCore blocks of chunks the client unloads keep being drawn within
  that range until the chunk is loaded again. `-Dirfar.factor` (1.5), `-Dirfar.maxBlocks` (1024), `-Dirfar.enabled=false`;
  install Keystone on the server and the client.

### Lag diagnostics

In the game or server folder:

| File | Content |
|---|---|
| `ltfix-metrics.log` | a line every 10 s: frame or tick spikes, queues, memory, cache and edit counters |
| `ltfix-hitches.log` | stacks and GC time of client frames over 50 ms; frames over 1 s also list the chunk uploads of the last 3 s and the VRAM state |
| `ltfix-ticks.log` | stacks and GC time of server ticks over 150 ms |
| `ltfix-freeze.log` | the stack of any server tick running for over 5 s, repeated while it stays stuck |
| `ltfix-heap.log` | the biggest classes on the client heap when it is still 85% full after a collection |

## Afterimage

Chunk sections that vanilla throws away (when you fly past the render distance, when the server unloads chunks, when
renderers are reloaded) are kept as exact GPU copies, drawn beyond the render distance and cached on disk, so
everything you have seen is back the moment you rejoin.

- **Exact, not LOD**: the far zone draws the very bytes the game uploaded for each section. No downsampling, no
  simplified buildings. Rebuild determinism and capture correctness were proven in game by a built-in verifier
  (`-Dafterimage.verify=true`).
- **All block layers**: solid, cutout and translucent (glass, stained glass, water, ice). Translucent copies are drawn
  after the opaque ones, back to front and blended, and only where vanilla does not draw that section itself.
- **No copies in the driver**: a section leaving the view keeps its own GL buffer as its copy. Otherwise geometry is
  duplicated with `glCopyBufferSubData` at the moment vanilla would lose it, with no readback to the CPU and no GL
  queries that would stall on the driver. Upload fingerprints are computed on the chunk workers.
- **Persistent**: sections are written to a per-server, per-dimension cache in the background (deflate, atomic writes,
  newer versions supersede queued older ones). On join the cache is restored nearest-first, uploaded to the GPU for at
  most 4 ms per frame.
- **No half-built buildings while chunks load**: vanilla compiles a section before its LittleTiles / Chisels & Bits tile
  entities arrive. Until vanilla's geometry equals the copy (or the server reports a newer change, or vanilla has been
  quiet for 20 s), vanilla's section is hidden and the complete copy is drawn. Sections within 32 blocks are never
  hidden, so your own edits show at once.
- **Never fights vanilla**: a section vanilla shows itself always wins. Its copy stays in VRAM and is replaced only if
  the section's geometry changed (upload fingerprints), so flying back and forth costs no copies; the disk cache is
  refreshed from every new build.
- **Seamless fog**: while the far zone has content, normal fog is pushed out (2048 blocks by default), so near terrain
  and far zone fade into the sky together. Water, lava and blindness keep their vanilla fog.
- **Budgeted**: 8 GB of VRAM for the far zone by default, farthest sections are evicted first. Each GB of copies also
  holds RAM in the graphics driver, so while the machine is short of RAM the budget shrinks, and it grows back afterwards.
- **Works with** OptiFine (Render Regions off), Chisels & Bits and LittleTiles, including LittleTiles' merged
  re-uploads of chunk buffers.
- **Server sync**: with Keystone on the server, the server records the tick of every change clients have to re-render
  (block changes and block update notifications, which LittleTiles and Chisels & Bits use for their own edits; chunk
  loading does not count). On join the client asks for everything since its last sync and drops copies and cached files
  made before those changes; while playing, new changes arrive every second. Caches of different worlds on one server
  stay apart.
- **Follows the texture layout**: copies store texture coordinates in the block texture atlas, and adding or removing
  any texture (a mod, a resource pack) moves most of them. The cache remembers a fingerprint of the layout it was made
  with; when the layout differs at start-up the old cache is moved aside and deleted in the background, so far terrain
  never shows other textures (roads as grass). A resource reload that changes the layout also drops the copies in VRAM.
- **Measures itself**: live section-geometry VRAM, unique meshes, per-section sizes (`sections.csv`) and raw geometry
  samples for offline analysis.

Recommended video settings: **Render Distance** 16-24 (the far zone keeps the rest), OptiFine **Render Regions: Off**
(required). Fog can stay on. Play: every place you visit is cached as you see it, and when you rejoin the cached city is
on screen again within seconds, before the server has sent a single far chunk.

### Commands

```
/afterimage                 status: GPU section geometry, far zone, disk cache, verifier
/afterimage far             far zone status
/afterimage far off|on      disable the far zone (frees all copies) / enable it again
/afterimage fog [blocks]    show / set where fog ends while the far zone is shown (0 = vanilla fog)
/afterimage disk            disk cache status
/afterimage sync            server sync status
/afterimage disk off|on     stop / resume writing and restoring the cache
/afterimage disk clear      delete the cache of the current server and dimension
/afterimage csv             write per-section geometry sizes to afterimage/sections.csv
/afterimage off|on          stop / resume upload tracking
```

### Files

Everything lives in `minecraft/afterimage/`:

| Path | Content |
|---|---|
| `cache/<server>/[<world id>/]DIM<n>/r.<rx>.<rz>/<cx>.<sy>.<cz>.L<layer>.aimg` | cached section geometry (world id when the server has Keystone) |
| `cache/<server>/<world id>/DIM<n>/sync.txt` | last server tick whose changes this cache has applied |
| `cache/atlas.txt` | fingerprint of the block texture layout the cache was made with |
| `cache-stale-<time>/` | a cache made with another texture layout, being deleted in the background |
| `summary.log` | one status line per minute |
| `verify.log`, `mismatch/` | verifier results and dumps of any mismatch |
| `sections.csv`, `samples/` | measurements for the tools below |
| `errors.log` | any error; a failing part disables itself instead of crashing the game |

On the server, changes are recorded in `<world>/afterimage/`.

### Limitations

- Tile entity special renderers and entities draw themselves outside chunk geometry (signs, chests, banners, beds,
  Immersive Railroading tracks and trains, LittleTiles animated structures, vehicles), so they are not in the far zone.
- Without Keystone on the server, edits made by other players while you are far away stay out of date in your cache
  until you come near them.
- Shader packs and OptiFine Render Regions are not supported.

## Configuration (JVM arguments)

Everything works with the defaults; these are for tuning and for turning a part off.

### Server

| Key | Default | Meaning |
|---|---|---|
| `-Dteunloadbatch.tagCache` | true | reuse serialized tile entity update tags in chunk packets (dedicated server) |
| `-Dteunloadbatch.tagCacheMB` | 768 | bytes of reusable tags kept, oldest dropped first |
| `-Dteunloadbatch.deferTickableRemoval` | true | replaced tile entities leave the ticking list in ordered batches |
| `-Dteunloadbatch.dedupBlockChanges` | true | changed blocks of a chunk are deduplicated with a bit set |
| `-Dltfix.neighborDedup` | true | LittleTiles' neighbour update queue checks duplicates with a hash set |
| `-Dchunkkeep.radius` | 20 | keep radius in chunks |
| `-Dchunkkeep.sweepTicks` | 20 | how often kept chunks are checked, in ticks |
| `-Dchunkkeep.heapGuardPercent` | 85 | old generation after its last collection above which the farthest half of kept chunks is released (off again 10 points lower) |
| `-Dchunkkeep.sendBudgetMs` | 15 | time for chunk packets per tick; 0 sends as vanilla does |
| `-Dchunkkeep.enterBudgetMs` | 10 | time per tick for chunks that enter view at once; the rest follows nearest first |
| `-Dchunkkeep.preload` | true | load the pinned chunks at start |
| `-Dchunkkeep.preloadMs` | 10 | main-thread time per tick for queuing pinned chunks |
| `-Dchunkkeep.preloadTickHighMs` | 40 | a longer tick halves the number of chunks loading at once |
| `-Dchunkkeep.preloadTickLowMs` | 25 | a shorter tick lets one more load at once |
| `-Dchunkkeep.preloadMaxInFlight` | 32 | most pinned chunks loading at once |
| `-Dchunkkeep.preloadIoThreads` | cores / 3, 2 to 8 | chunk IO threads during preload |
| `-Dchunkkeep.skipSpeedCheckForBuilders` | true | creative and spectator players skip "moved too quickly" |
| `-Dchunkkeep.packetBytes` | 1900000 | uncompressed size above which a chunk packet is split |
| `-Dchunkkeep.backlogMB` | 12 | chunk packet bytes queued to a player's connection above which chunk sending waits (a keep-alive stuck behind them timed players out) |
| `-Dchunkkeep.fastDeflateKB` | 256 | packets at least this big are compressed at zlib level 1 instead of 6 |
| `-Dchunkkeep.fullResendMs` | 1500 | least time between two whole-chunk re-sends of a chunk with many changed blocks (large edits changed the same chunks every tick) |

### Client

| Key | Default | Meaning |
|---|---|---|
| `-Dltfix.preparse` | true | parse LittleTiles data from chunk packets in the background |
| `-Dltfix.preparseThreads` | 3 | threads for that |
| `-Dltfix.preparseQueue` | 256 | chunks parsed ahead at most; the client thread parses the rest itself when it reaches them |
| `-Dltfix.pack` | true | keep LittleTiles geometry in the heap and pack what has not been drawn for a while; false keeps it raw in direct memory |
| `-Dltfix.packAfterMs` | 30000 | time without drawing before geometry is packed |
| `-Dltfix.packThreads` | 2 | packing threads |
| `-Dltfix.rawBudgetMB` | heap / 8 | unpacked geometry above which everything idle for `packPressureIdleMs` is packed at once |
| `-Dltfix.rawHardMB` | heap / 5 | unpacked geometry above which chunk workers wait (up to 2 s) before merging more tiles |
| `-Dltfix.packPressureIdleMs` | 1000 | idle time that is enough to pack while over the budget |
| `-Dltfix.heapPressurePercent` | 60 | heap after collections (lowest reading of 30 s) above which the packers work as over budget |
| `-Dltfix.heapTightPercent` | 75 | heap after collections above which new geometry copies stay in direct memory |
| `-Dltfix.heapHistogramPercent` | 85 | heap after a collection above which the biggest classes are written to `ltfix-heap.log` |
| `-Dltfix.heapHistogramMinutes` | 10 | least time between two such histograms |
| `-Dltfix.packGcMB` | 1024 | freed geometry after which a concurrent GC cycle is requested (only with `-XX:+ExplicitGCInvokesConcurrent` or Shenandoah) |
| `-Dltfix.directGcMB` | 6144 | direct memory above which such a cycle is requested |
| `-Dltfix.renderOnRead` | true | render tiles again whose data arrives after their chunk was built |
| `-Dltfix.retryMs` | 200 | delay before a tile without data is tried again; doubles per retry |
| `-Dltfix.retryMaxMs` | 5000 | longest such delay |
| `-Dltfix.jmPrewarm` | true | decode JourneyMap region images around the player ahead |
| `-Dltfix.viewChunks` | 6 | server view distance, for the missing-chunks counter in the metrics |
| `-Dpacketbudget.ms` | 10 | scheduled-task time per frame |
| `-Dpacketbudget.teMs` | 4 | chunk tile entity tag time per frame |
| `-Dcbbakecache.othersize` | 4096 | entries of the Chisels & Bits neighbour blob cache |
| `-Dumctickfix.slice` | 20000 | tile entities checked per tick for new UniversalModCore ones |
| `-Dteunloadbatch.buildersPerWorker` | 3 | render builders per chunk worker |
| `-Dteunloadbatch.builderBudgetMB` | 1024 | native memory of the builder pool above which grown builders are replaced |

### Afterimage

| Key | Default | Meaning |
|---|---|---|
| `-Dafterimage.far` | true | far zone on or off |
| `-Dafterimage.farBudgetMB` | 8192 | VRAM for far-zone copies, farthest evicted first |
| `-Dafterimage.lowVramMB` | 2560 | free VRAM (NVIDIA cards report it) below which the budget shrinks by the shortfall, never below `farMinBudgetMB` (1024) |
| `-Dafterimage.highVramMB` | 3584 | free VRAM above which it grows back, 256 MB per check |
| `-Dafterimage.diskCapMB` | 8192 | disk cache size above which the files least recently written are removed at join |
| `-Dafterimage.lowFreeMB` | 4096 | same, on available RAM, only where copies are not immutable (no ARB_buffer_storage) |
| `-Dafterimage.highFreeMB` | 6144 | |
| `-Dafterimage.farPlane` | 8192 | far clipping plane used for the far zone, in blocks |
| `-Dafterimage.farNear` | 6 | near clipping plane of the far pass, in blocks (depth precision far away) |
| `-Dafterimage.fogEnd` | 2048 | fog end while the far zone has content, in blocks; 0 keeps vanilla fog |
| `-Dafterimage.settleMs` | 20000 | a freshly loaded vanilla section stays hidden behind its copy until they match, the server reports a change, or vanilla is quiet this long |
| `-Dafterimage.hideNear` | 32 | sections nearer than this many blocks are never hidden behind their copy |
| `-Dafterimage.steal` | true | a leaving section's own GL buffer becomes its copy; false copies it on the GPU |
| `-Dafterimage.workerHash` | true | upload fingerprints are computed on the chunk workers |
| `-Dafterimage.disk` | true | disk cache on or off |
| `-Dafterimage.diskUploadMs` | 4 | per-frame time budget for uploading restored sections |
| `-Dafterimage.enabled` | true | upload tracking and measurements (far-zone reuse and the disk cache rely on it) |
| `-Dafterimage.verify` | false | development only: verifier (forced rebuilds, GPU readbacks) and raw geometry samples |

### Diagnostics

| Key | Default | Meaning |
|---|---|---|
| `-Dltfix.hitchMs` | 50 | client frames longer than this are sampled |
| `-Dltfix.logRotateMB` | 8 | a diagnostic log bigger than this at start-up is moved aside as `.prev` |
| `-Dltfix.tickMs` | 150 | server ticks longer than this are sampled |
| `-Dltfix.sampleMs` | 10 | sampling interval |
| `-Dltfix.sampleFromMs` | 50 | a frame or tick is sampled only once it is this old (every sample is a safepoint for all threads) |
| `-Dltfix.freezeMs` | 5000 | a server tick running longer than this is written to `ltfix-freeze.log` |

## Source layout

| Package | Part |
|---|---|
| `ru.arthaix.keystone` | the mod class, the coremod with the early mixin configurations, the late loader for the mixins into other mods |
| `ru.arthaix.afterimage` | Afterimage (its mod classes are in `src/mod/java`) |
| `ru.arthaix.keystone.teunloadbatch` | Minecraft and Forge: tile entity lists, block edits, chunk packet tags, render builders, screenshots |
| `ru.arthaix.keystone.chunkkeep` | keep radius, chunk sending budgets, pinned chunks and their preload (dedicated server) |
| `ru.arthaix.keystone.ltfix` | LittleTiles 1.5.14, Immersive Vehicles and JourneyMap fixes, lag metrics and freeze logs |
| `ru.arthaix.keystone.packetbudget` | client packet budget |
| `ru.arthaix.keystone.cbbakecache` | Chisels & Bits baking cache |
| `ru.arthaix.keystone.umctickfix` | UniversalModCore tile entity tracking |
| `ru.arthaix.keystone.opffix` | OnlinePictureFrame downloads |
| `ru.arthaix.keystone.vfcompat` | item icons of Immersive Vehicles packs that VintageFix cannot read |
| `ru.arthaix.keystone.irfar` | Immersive Railroading / UniversalModCore view range: entity tracking, far entities and kept tile entities |

## Building

The sources call Minecraft by SRG names and hook it with MixinBooter mixins, so there is no reobfuscation step and the
build is a plain two-pass `javac` instead of Gradle. Put these jars into `libs/`:

```
libs/mixinbooter-10.7.jar
libs/forge-1.12.2-srg.jar                          Forge 1.12.2 srgBin jar (Minecraft + Forge, SRG names)
libs/forge-1.12.2-universal.jar                    Forge 1.12.2-14.23.5.2860 universal jar
libs/forge-1.12.2-dev.jar                          Forge 1.12.2 dev jar (MCP names), used only for Afterimage's mod classes
libs/lwjgl-2.9.4.jar                               LWJGL 2.9.4-nightly-20150209
libs/netty-all-4.1.9.Final.jar                     Netty 4.1.9 (Minecraft 1.12.2 library)
libs/fastutil-7.1.0.jar                            fastutil 7.1.0 (Minecraft 1.12.2 library)
libs/guava-21.0.jar                                Guava 21.0 (Minecraft 1.12.2 library)
libs/log4j-api-2.17.1.jar                          Log4j API 2.x
libs/LittleTiles_v1.5.14_mc1.12.2.jar              the mods Keystone patches, to compile against
libs/CreativeCore_v1.10.61_mc1.12.2.jar
libs/chiselsandbits-14.33.jar
libs/UniversalModCore-1.12.2-forge-1.1.4-580823d.jar
libs/OnlinePicFrame_v1.5.0-pre1_mc1.12.2.jar
libs/log4j-core-2.17.1.jar                         tests only
```

then

```
JAVA8_HOME=/path/to/jdk8 ./build.sh     # build/z-keystone-<version>.jar
JAVA8_HOME=/path/to/jdk8 ./test.sh      # unit tests in src/test/java
```

`tools/analyze_quads.py [samples dir]` measures how compressible captured geometry is,
`tools/roundtrip_quads.py [samples dir]` checks a bit-exact rectangle encoding of it,
`tools/analyze_mismatch.py [mismatch dir]` explains verifier mismatches quad by quad.

## Requirements

Minecraft 1.12.2, Forge 14.23.5.2860, MixinBooter 10.x; OpenGL 3.1 for Afterimage.

Optional, each with its own fixes (tested versions): LittleTiles 1.5.14 with CreativeCore 1.10.61, Chisels & Bits
14.33, UniversalModCore 1.1.4, OnlinePictureFrame 1.5.0, Immersive Vehicles 22.5.0, JourneyMap 5.7.1, VintageFix 0.6.2.
