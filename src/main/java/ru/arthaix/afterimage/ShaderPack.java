package ru.arthaix.afterimage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.util.BlockRenderLayer;

/**
 * OptiFine shader packs.
 *
 * A pack builds chunk geometry in OptiFine's own vertex layout (SVertexFormat: the vanilla 28 bytes plus normal, mid
 * texture coordinate, tangent and block id, 56 bytes in all) and its programs read those attributes, so copies of
 * sections are only interchangeable within one layout: a copy taken with a pack loaded must be drawn with the pack's
 * pointers, a copy taken without one must not be drawn while a pack is loaded. Far keeps one layout at a time and
 * starts over when the player switches packs on or off; the disk cache keeps both, each under its own file name.
 */
public final class ShaderPack {
    /** OptiFine's chunk vertex size with a pack loaded; read from SVertexFormat, this is the fallback. */
    private static final int SHADER_STRIDE = 56;

    private static Field loadedField;
    private static Method setupPointers;
    private static Method preLayer;
    private static Method postLayer;
    private static boolean layerChecked;
    private static int shaderStride;
    private static boolean checked;
    private static boolean absent;
    private static boolean pointersChecked;

    private ShaderPack() {
    }

    /** True while a shader pack is drawing the world (false without OptiFine). */
    public static boolean active() {
        if (absent) {
            return false;
        }
        try {
            if (!checked) {
                checked = true;
                Field f = Class.forName("net.optifine.shaders.Shaders").getDeclaredField("shaderPackLoaded");
                f.setAccessible(true);
                loadedField = f;
            }
            return loadedField != null && loadedField.getBoolean(null);
        } catch (Throwable t) {
            absent = true;
            loadedField = null;
            return false;
        }
    }

    /** Bytes per vertex of chunk geometry while a pack is loaded. */
    public static int stride() {
        if (shaderStride == 0) {
            shaderStride = SHADER_STRIDE;
            try {
                Field f = Class.forName("net.optifine.shaders.SVertexFormat").getDeclaredField("vertexSizeBlock");
                f.setAccessible(true);
                int v = f.getInt(null);
                // OptiFine counts the size in ints
                if (v >= 7 && v <= 64) {
                    shaderStride = v * 4;
                } else if (v >= 28 && v <= 256) {
                    shaderStride = v;
                }
            } catch (Throwable ignored) {
                // the fallback stays
            }
        }
        return shaderStride;
    }

    /** Enables the arrays the pack's terrain program reads (normal, mid texture coordinate, tangent, block id). */
    public static void preChunkLayer(BlockRenderLayer layer) {
        findLayerMethods();
        call(preLayer, layer);
    }

    /** Ends what preChunkLayer set up. */
    public static void postChunkLayer(BlockRenderLayer layer) {
        findLayerMethods();
        call(postLayer, layer);
    }

    private static void findLayerMethods() {
        if (layerChecked) {
            return;
        }
        layerChecked = true;
        try {
            Class<?> c = Class.forName("net.optifine.shaders.ShadersRender");
            preLayer = c.getMethod("preRenderChunkLayer", BlockRenderLayer.class);
            postLayer = c.getMethod("postRenderChunkLayer", BlockRenderLayer.class);
        } catch (Throwable ignored) {
            preLayer = null;
            postLayer = null;
        }
    }

    private static void call(Method m, BlockRenderLayer layer) {
        if (m == null) {
            return;
        }
        try {
            m.invoke(null, layer);
        } catch (Throwable ignored) {
            layerChecked = true;
            preLayer = null;
            postLayer = null;
        }
    }

    /**
     * Sets the array pointers for the bound chunk buffer the way OptiFine does for its own sections: position, colour,
     * texture, lightmap, normal and the pack attributes (mid texture coordinate, tangent, block id). Returns false when
     * OptiFine is not there, and the caller keeps the vanilla layout.
     */
    public static boolean setupPointers() {
        if (!pointersChecked) {
            pointersChecked = true;
            try {
                Method m = Class.forName("net.optifine.shaders.ShadersRender").getMethod("setupArrayPointersVbo");
                m.setAccessible(true);
                setupPointers = m;
            } catch (Throwable ignored) {
                setupPointers = null;
            }
        }
        if (setupPointers == null) {
            return false;
        }
        try {
            setupPointers.invoke(null);
            return true;
        } catch (Throwable t) {
            setupPointers = null;
            return false;
        }
    }
}
