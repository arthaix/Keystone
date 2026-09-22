import java.lang.reflect.Method;

import ru.arthaix.keystone.ivsign.SignAngle;

public class SignAngleTest {
    public static void main(String[] args) throws Exception {
        Method m = SignAngle.class.getDeclaredMethod("fromPlayerYaw", double.class, double.class);
        m.setAccessible(true);
        double[][] cases = {
            // player yaw, snap, expected angle
            { 0, 7, 180 }, { 180, 7, 0 }, { -90, 7, 90 }, { 90, 7, 270 },
            { 3, 7, 180 }, { -6.9, 7, 180 }, { 8, 7, 188 }, { 40, 7, 225 }, { 36.4, 7, 216 },
            { 225, 7, 45 }, { 219, 7, 45 }, { 211, 7, 31 }, { 359.6, 7, 180 }, { -180, 7, 0 },
            { 3, 0, 183 }, { 177, 7, 0 }, { 176.6, 7, 0 }, { 765, 7, 225 },
        };
        int bad = 0;
        for (double[] c : cases) {
            double got = (Double) m.invoke(null, c[0], c[1]);
            if (Math.abs(got - c[2]) > 1e-9) {
                System.out.println("FAIL yaw " + c[0] + " snap " + c[1] + ": " + got + ", expected " + c[2]);
                bad++;
            }
        }
        for (double a = 0; a < 360; a += 1) {
            if (Math.abs(SignAngle.decode(SignAngle.encode(a)) - a) > 1e-9) {
                System.out.println("FAIL encode " + a);
                bad++;
            }
        }
        if (!Double.isNaN(SignAngle.decode(null)) || !Double.isNaN(SignAngle.decode(0.0))) {
            System.out.println("FAIL decode of an unset angle");
            bad++;
        }
        if (bad > 0) {
            throw new AssertionError(bad + " failures");
        }
        System.out.println("ok, " + cases.length + " angles");
    }
}
