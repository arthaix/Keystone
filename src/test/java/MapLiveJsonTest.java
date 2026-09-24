import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ru.arthaix.keystone.maplive.MapLive;

public class MapLiveJsonTest {
    public static void main(String[] args) {
        int bad = 0;
        List<MapLive.Seen> none = new ArrayList<>();
        bad += check(MapLive.json(5, true, none), "{\"t\":5,\"stopped\":true,\"players\":[]}");
        List<MapLive.Seen> two = Arrays.asList(
            new MapLive.Seen("arthaix", "11111111-2222-3333-4444-555555555555", 0, 1904.26, 25.0, -512.04, -90.4f, true),
            new MapLive.Seen("a\"b\\c", "u", -1, Double.NaN, 64.55, 3, 725.2f, false));
        bad += check(MapLive.json(1790000000000L, false, two),
            "{\"t\":1790000000000,\"stopped\":false,\"players\":["
            + "{\"name\":\"arthaix\",\"uuid\":\"11111111-2222-3333-4444-555555555555\",\"dim\":0,\"x\":1904.3,\"y\":25.0,"
            + "\"z\":-512.0,\"yaw\":270,\"spectator\":true},"
            + "{\"name\":\"a\\\"b\\\\c\",\"uuid\":\"u\",\"dim\":-1,\"x\":0,\"y\":64.6,\"z\":3.0,\"yaw\":5,\"spectator\":false}]}");
        if (bad > 0) {
            throw new AssertionError(bad + " failed");
        }
        System.out.println("ok");
    }

    private static int check(String got, String want) {
        if (got.equals(want)) {
            return 0;
        }
        System.out.println("FAIL\n  got  " + got + "\n  want " + want);
        return 1;
    }
}
