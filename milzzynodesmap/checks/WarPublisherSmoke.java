package phonon.nodesmap.compat;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.reflect.Method;

public final class WarPublisherSmoke {
    public static void main(String[] args) throws Exception {
        Method read = WarSystemsPublisher.class.getDeclaredMethod("read", YamlConfiguration.class, boolean.class, String[].class);
        read.setAccessible(true);
        YamlConfiguration config = new YamlConfiguration();
        config.set("war.keyPointWars.enabled", true);
        boolean active = (boolean) read.invoke(null, config, false, new String[]{"war.keyPointWars.enabled"});
        if (!active) throw new AssertionError("Nested key-point flag not read");
        config.set("war.keyPointWars.enabled", false);
        if ((boolean) read.invoke(null, config, true, new String[]{"war.keyPointWars.enabled"}))
            throw new AssertionError("Explicitly disabled systems must remain hidden");

        Method extract = WarSystemsPublisher.class.getDeclaredMethod("extractRecords", JsonElement.class);
        extract.setAccessible(true);
        JsonArray array = (JsonArray) extract.invoke(null, JsonParser.parseString("""
          {"activeBattles":{"citadel-1":{"name":"Citadel","status":"LIVE"}}}
          """));
        if (array.size() != 1 || !array.get(0).getAsJsonObject().get("id").getAsString().equals("citadel-1"))
            throw new AssertionError("Battle keyed-object state not converted to array");
        System.out.println("PASS: War publisher respects disabled configuration and keyed battle JSON");
    }
}
