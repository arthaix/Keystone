package ru.arthaix.keystone.nogen;

/**
 * World generators of chosen mods are skipped, so their decoration stops appearing in newly generated chunks while the
 * mod's blocks keep working everywhere they were placed by hand.
 *
 * -Dkeystone.nogen=morevegetation,othermod: a generator is skipped when its class name contains one of these (the mod
 * id is usually part of the package). Empty (the default) changes nothing.
 */
public final class NoGen {
    private static final String[] NAMES = parse(System.getProperty("keystone.nogen", ""));

    private static final ClassValue<Boolean> BLOCKED = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            String name = type.getName().toLowerCase(java.util.Locale.ROOT);
            for (String n : NAMES) {
                if (name.contains(n)) {
                    System.out.println("[keystone] world generator " + type.getName() + " skipped (keystone.nogen)");
                    return true;
                }
            }
            return false;
        }
    };

    private NoGen() {
    }

    public static boolean blocked(Object generator) {
        return NAMES.length != 0 && generator != null && BLOCKED.get(generator.getClass());
    }

    private static String[] parse(String value) {
        String[] raw = value.toLowerCase(java.util.Locale.ROOT).split(",");
        int n = 0;
        for (String s : raw) {
            if (!s.trim().isEmpty()) {
                raw[n++] = s.trim();
            }
        }
        String[] out = new String[n];
        System.arraycopy(raw, 0, out, 0, n);
        return out;
    }
}
