package ru.arthaix.afterimage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Chunk geometry in the two vertex layouts the game builds it in, and the way from one to the other.
 *
 * Vanilla lays a vertex out in 28 bytes: position (3 floats), colour (4 bytes), texture (2 floats), lightmap (2 shorts).
 * With an OptiFine shader pack loaded the same 28 bytes come first and four fields follow, which the pack's programs
 * read: normal (3 bytes and one spare), mid texture coordinate (2 floats), tangent (4 shorts) and block id (3 shorts),
 * 56 bytes in all.
 *
 * The disk cache keeps the vanilla layout only, so one cache serves both: what a pack built is written without its four
 * fields, and what is read is given them back. Normal and tangent follow from the quad itself (its plane and how the
 * texture lies on it), the mid texture coordinate is the middle of the quad's texture, and the block id is left at zero:
 * packs then treat a far section as plain blocks, without the waving or the special materials they give to a few ids.
 */
public final class VertexLayout {
    /** Vanilla bytes per vertex. */
    public static final int VANILLA = 28;

    private static final int OFF_NORMAL = 28;
    private static final int OFF_MID = 32;
    private static final int OFF_TANGENT = 40;
    private static final int OFF_ENTITY = 48;

    private VertexLayout() {
    }

    /** Copies the vanilla part of every vertex; src holds vertices of stride bytes. Returns bytes written. */
    public static int strip(ByteBuffer src, int offset, int size, int stride, byte[] out) {
        int vertices = size / stride;
        int w = 0;
        for (int v = 0; v < vertices; v++) {
            int base = offset + v * stride;
            for (int b = 0; b < VANILLA; b++) {
                out[w++] = src.get(base + b);
            }
        }
        return w;
    }

    /**
     * Vanilla vertices in, a pack's vertices out. Works on whole quads: the four vertices of a quad share the normal,
     * the tangent and the mid texture coordinate.
     */
    public static ByteBuffer expand(ByteBuffer src, int stride) {
        int size = src.remaining();
        int vertices = size / VANILLA;
        int quads = vertices / 4;
        if (quads <= 0) {
            return null;
        }
        ByteBuffer in = src.duplicate();
        in.order(ByteOrder.nativeOrder());
        int base = in.position();
        ByteBuffer out = DirectPool.acquire(quads * 4 * stride);
        out.order(ByteOrder.nativeOrder());
        out.clear();
        float[] px = new float[4];
        float[] py = new float[4];
        float[] pz = new float[4];
        float[] tu = new float[4];
        float[] tv = new float[4];
        for (int q = 0; q < quads; q++) {
            int v0 = base + q * 4 * VANILLA;
            for (int i = 0; i < 4; i++) {
                int p = v0 + i * VANILLA;
                px[i] = in.getFloat(p);
                py[i] = in.getFloat(p + 4);
                pz[i] = in.getFloat(p + 8);
                tu[i] = in.getFloat(p + 16);
                tv[i] = in.getFloat(p + 20);
            }
            float e1x = px[1] - px[0];
            float e1y = py[1] - py[0];
            float e1z = pz[1] - pz[0];
            float e2x = px[2] - px[0];
            float e2y = py[2] - py[0];
            float e2z = pz[2] - pz[0];
            float nx = e1y * e2z - e1z * e2y;
            float ny = e1z * e2x - e1x * e2z;
            float nz = e1x * e2y - e1y * e2x;
            float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1.0e-6f) {
                nx /= nl;
                ny /= nl;
                nz /= nl;
            } else {
                nx = 0.0f;
                ny = 1.0f;
                nz = 0.0f;
            }
            float du1 = tu[1] - tu[0];
            float dv1 = tv[1] - tv[0];
            float du2 = tu[2] - tu[0];
            float dv2 = tv[2] - tv[0];
            float det = du1 * dv2 - du2 * dv1;
            float tx;
            float ty;
            float tz;
            if (Math.abs(det) > 1.0e-12f) {
                float r = 1.0f / det;
                tx = (e1x * dv2 - e2x * dv1) * r;
                ty = (e1y * dv2 - e2y * dv1) * r;
                tz = (e1z * dv2 - e2z * dv1) * r;
            } else {
                // no texture direction to follow: any vector in the quad's plane
                tx = e1x;
                ty = e1y;
                tz = e1z;
            }
            float tl = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 1.0e-6f) {
                tx /= tl;
                ty /= tl;
                tz /= tl;
            } else {
                tx = 1.0f;
                ty = 0.0f;
                tz = 0.0f;
            }
            // bitangent from the texture's other direction decides which way the tangent frame turns
            float bx;
            float by;
            float bz;
            if (Math.abs(det) > 1.0e-12f) {
                float r = 1.0f / det;
                bx = (e2x * du1 - e1x * du2) * r;
                by = (e2y * du1 - e1y * du2) * r;
                bz = (e2z * du1 - e1z * du2) * r;
            } else {
                bx = ny * tz - nz * ty;
                by = nz * tx - nx * tz;
                bz = nx * ty - ny * tx;
            }
            float cx = ny * tz - nz * ty;
            float cy = nz * tx - nx * tz;
            float cz = nx * ty - ny * tx;
            float handed = cx * bx + cy * by + cz * bz < 0.0f ? -1.0f : 1.0f;
            float midU = (tu[0] + tu[1] + tu[2] + tu[3]) * 0.25f;
            float midV = (tv[0] + tv[1] + tv[2] + tv[3]) * 0.25f;
            for (int i = 0; i < 4; i++) {
                int p = v0 + i * VANILLA;
                int w = (q * 4 + i) * stride;
                for (int b = 0; b < VANILLA; b++) {
                    out.put(w + b, in.get(p + b));
                }
                out.put(w + OFF_NORMAL, toByte(nx));
                out.put(w + OFF_NORMAL + 1, toByte(ny));
                out.put(w + OFF_NORMAL + 2, toByte(nz));
                out.put(w + OFF_NORMAL + 3, (byte) 0);
                out.putFloat(w + OFF_MID, midU);
                out.putFloat(w + OFF_MID + 4, midV);
                out.putShort(w + OFF_TANGENT, toShort(tx));
                out.putShort(w + OFF_TANGENT + 2, toShort(ty));
                out.putShort(w + OFF_TANGENT + 4, toShort(tz));
                out.putShort(w + OFF_TANGENT + 6, toShort(handed));
                out.putShort(w + OFF_ENTITY, (short) 0);
                out.putShort(w + OFF_ENTITY + 2, (short) 0);
                out.putShort(w + OFF_ENTITY + 4, (short) 0);
                if (stride >= OFF_ENTITY + 8) {
                    out.putShort(w + OFF_ENTITY + 6, (short) 0);
                }
            }
        }
        out.position(0);
        out.limit(quads * 4 * stride);
        return out;
    }

    private static byte toByte(float v) {
        int i = Math.round(v * 127.0f);
        return (byte) (i > 127 ? 127 : i < -127 ? -127 : i);
    }

    private static short toShort(float v) {
        int i = Math.round(v * 32767.0f);
        return (short) (i > 32767 ? 32767 : i < -32767 ? -32767 : i);
    }
}
