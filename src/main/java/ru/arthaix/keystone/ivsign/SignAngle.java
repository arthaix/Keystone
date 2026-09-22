package ru.arthaix.keystone.ivsign;

/**
 * Free angles for Immersive Vehicles pole components (signs, traffic signals, street lights). Immersive Vehicles turns a
 * component to one of the pole's sides (every 90 degrees, or 45 on poles that allow diagonals); here it faces the player
 * who places it, at any whole-degree angle, pulled to the nearest multiple of 45 degrees when that is close.
 *
 * The angle is kept as an entity variable of the component, so Immersive Vehicles saves it with the world, sends it to
 * clients and keeps it on the item when the component is taken off with a wrench.
 */
public final class SignAngle {
    /** Entity variable holding the angle, stored as angle + OFFSET so that no angle is ever saved as 0. */
    public static final String VARIABLE = "keystone_sign_yaw";
    static final double OFFSET = 360.0;

    /** Degrees within which an angle snaps to a multiple of 45 (-Dkeystone.signsnap, 0 = no snapping). */
    public static final double SNAP = snapSetting();

    private SignAngle() {
    }

    /** The angle a component faces when placed by a player looking along playerYaw (Immersive Vehicles' yaw), 0..360. */
    public static double fromPlayerYaw(double playerYaw) {
        return fromPlayerYaw(playerYaw, SNAP);
    }

    static double fromPlayerYaw(double playerYaw, double snap) {
        double a = normalize(playerYaw + 180.0);
        double step = Math.round(a / 45.0) * 45.0;
        return normalize(Math.abs(a - step) <= snap ? step : Math.round(a));
    }

    /** The player yaw whose side of the pole matches an angle: Immersive Vehicles picks the side opposite the view. */
    public static double viewYaw(double angle) {
        return normalize(angle + 180.0);
    }

    public static double encode(double angle) {
        return angle + OFFSET;
    }

    /** NaN for a component placed without an angle (before Keystone, or by a server without it). */
    public static double decode(Double stored) {
        return stored == null || stored < OFFSET ? Double.NaN : normalize(stored - OFFSET);
    }

    static double normalize(double a) {
        a %= 360.0;
        return a < 0 ? a + 360.0 : a;
    }

    private static double snapSetting() {
        try {
            return Math.max(0.0, Math.min(22.5, Double.parseDouble(System.getProperty("keystone.signsnap", "7"))));
        } catch (NumberFormatException e) {
            return 7.0;
        }
    }
}
