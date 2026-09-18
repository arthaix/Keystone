import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import ru.arthaix.afterimage.VertexLayout;

/** VertexLayout: the vanilla part survives, and the fields a shader pack reads describe the quad it came from. */
public class VertexLayoutTest {
    private static final int V = VertexLayout.VANILLA;
    private static final int S = 56;

    static int checks;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    static void close(float got, float want, float eps, String what) {
        check(Math.abs(got - want) <= eps, what + ": " + got + " != " + want);
    }

    /** One quad, four vertices, in the vanilla layout. */
    static void quad(ByteBuffer b, int at, float[][] pos, float[][] uv) {
        for (int i = 0; i < 4; i++) {
            int p = at + i * V;
            b.putFloat(p, pos[i][0]);
            b.putFloat(p + 4, pos[i][1]);
            b.putFloat(p + 8, pos[i][2]);
            b.put(p + 12, (byte) 0xFF);
            b.put(p + 13, (byte) 0x80);
            b.put(p + 14, (byte) 0x40);
            b.put(p + 15, (byte) 0xFF);
            b.putFloat(p + 16, uv[i][0]);
            b.putFloat(p + 20, uv[i][1]);
            b.putShort(p + 24, (short) 240);
            b.putShort(p + 26, (short) 240);
        }
    }

    public static void main(String[] args) {
        // a quad lying flat, facing up, with the texture laid out the usual way
        float[][] pos = {{0f, 4f, 0f}, {0f, 4f, 1f}, {1f, 4f, 1f}, {1f, 4f, 0f}};
        float[][] uv = {{0.25f, 0.5f}, {0.25f, 0.75f}, {0.5f, 0.75f}, {0.5f, 0.5f}};
        ByteBuffer in = ByteBuffer.allocate(4 * V).order(ByteOrder.nativeOrder());
        quad(in, 0, pos, uv);
        in.position(0);
        in.limit(4 * V);

        ByteBuffer out = VertexLayout.expand(in, S);
        check(out != null, "expanded");
        check(out.remaining() == 4 * S, "size " + out.remaining());

        for (int i = 0; i < 4; i++) {
            int p = i * V;
            int q = out.position() + i * S;
            for (int b = 0; b < V; b++) {
                check(out.get(q + b) == in.get(p + b), "vanilla byte " + b + " of vertex " + i);
            }
            // facing up: the normal is +Y (the winding of this quad gives -Y before normalising, so only the axis is checked)
            check(out.get(q + 28) == 0 && out.get(q + 30) == 0, "normal axis");
            check(Math.abs(out.get(q + 29)) == 127, "normal length " + out.get(q + 29));
            close(out.getFloat(q + 32), 0.375f, 1e-6f, "mid u");
            close(out.getFloat(q + 36), 0.625f, 1e-6f, "mid v");
            float tx = out.getShort(q + 40) / 32767f;
            float ty = out.getShort(q + 42) / 32767f;
            float tz = out.getShort(q + 44) / 32767f;
            close((float) Math.sqrt(tx * tx + ty * ty + tz * tz), 1f, 1e-3f, "tangent length");
            close(ty, 0f, 1e-3f, "tangent lies in the quad");
            check(Math.abs(out.getShort(q + 46)) == 32767, "handedness");
            check(out.getShort(q + 48) == 0 && out.getShort(q + 50) == 0 && out.getShort(q + 52) == 0, "block id");
        }

        // the vanilla part comes back byte for byte, for many random quads
        Random r = new Random(7);
        int quads = 500;
        ByteBuffer many = ByteBuffer.allocate(quads * 4 * V).order(ByteOrder.nativeOrder());
        for (int q = 0; q < quads; q++) {
            float[][] p = new float[4][3];
            float[][] t = new float[4][2];
            for (int i = 0; i < 4; i++) {
                p[i][0] = r.nextFloat() * 16f;
                p[i][1] = r.nextFloat() * 16f;
                p[i][2] = r.nextFloat() * 16f;
                t[i][0] = r.nextFloat();
                t[i][1] = r.nextFloat();
            }
            quad(many, q * 4 * V, p, t);
        }
        many.position(0);
        many.limit(quads * 4 * V);
        ByteBuffer wide = VertexLayout.expand(many, S);
        check(wide != null && wide.remaining() == quads * 4 * S, "many expanded");
        byte[] back = new byte[quads * 4 * V];
        int wrote = VertexLayout.strip(wide, wide.position(), wide.remaining(), S, back);
        check(wrote == back.length, "stripped size " + wrote);
        for (int i = 0; i < back.length; i++) {
            check(back[i] == many.get(i), "byte " + i + " after expand and strip");
        }

        // degenerate quads (all vertices in one point, no texture direction) must not produce NaN
        ByteBuffer flat = ByteBuffer.allocate(4 * V).order(ByteOrder.nativeOrder());
        quad(flat, 0, new float[][] {{1f, 1f, 1f}, {1f, 1f, 1f}, {1f, 1f, 1f}, {1f, 1f, 1f}},
                new float[][] {{0f, 0f}, {0f, 0f}, {0f, 0f}, {0f, 0f}});
        flat.position(0);
        flat.limit(4 * V);
        ByteBuffer none = VertexLayout.expand(flat, S);
        check(none != null, "degenerate expanded");
        for (int i = 0; i < 4; i++) {
            int q = none.position() + i * S;
            check(!Float.isNaN(none.getFloat(q + 32)) && !Float.isNaN(none.getFloat(q + 36)), "mid not NaN");
            int len = Math.abs(none.get(q + 28)) + Math.abs(none.get(q + 29)) + Math.abs(none.get(q + 30));
            check(len > 0, "some normal");
        }

        System.out.println("VertexLayoutTest ok (" + checks + " checks)");
    }
}
