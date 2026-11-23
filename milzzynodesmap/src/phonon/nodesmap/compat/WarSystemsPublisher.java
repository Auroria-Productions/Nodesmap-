package phonon.nodesmap.compat;

import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Public, read-only war systems snapshot used by NodesMap's viewer extension.
 *
 * It does NOT control war gameplay. Battle state is read from Nodes-owned JSON data.
 * Systems absent from config are disabled except for the legacy flag-war interface.
 */
public final class WarSystemsPublisher {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_STATE_BYTES = 4L * 1024 * 1024;
    private WarSystemsPublisher() {}

    public static String snapshot(JavaPlugin nodes) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(nodes.getDataFolder(), "config.yml"));
        boolean global = read(yaml, true, "war.enabled", "war.systems.enabled");
        boolean classic = read(yaml, true,
            "war.systems.flagWar.enabled", "war.flagWar.enabled", "flagWar.enabled");
        // In classic configs oldWarSystem selects legacy vs modern war behaviour;
        // explicit war.systems.* values always win.
        boolean nodesWar = read(yaml, !yaml.getBoolean("oldWarSystem", true),
            "war.systems.nodesWar.enabled", "war.nodesWar.enabled", "nodesWar.enabled");
        boolean keyPoint = read(yaml, false,
            "war.systems.keyPointWars.enabled", "war.keyPointWars.enabled",
            "war.keypointWars.enabled", "war.keyPointWar.enabled", "war.keyPoints.enabled",
            "war.keypoints.enabled", "keyPointWar.enabled", "keyPointWars.enabled");
        boolean zones = read(yaml, true,
            "war.systems.warzones.enabled", "war.warzones.enabled", "warzone.enabled", "warzones.enabled");
        boolean raids = read(yaml, false,
            "war.systems.raids.enabled", "war.raids.enabled", "raid.enabled");
        JsonObject systems = new JsonObject();
        addSystem(systems, "flagWar", "Flag War", global && classic);
        addSystem(systems, "nodesWar", "NodesWar", global && nodesWar);
        addSystem(systems, "keyPointWars", "Key-point Wars", global && keyPoint);
        addSystem(systems, "warzones", "Warzones", global && zones);
        addSystem(systems, "raids", "Raids", global && raids);

        JsonObject response = new JsonObject();
        response.addProperty("schema", 1);
        response.add("config", new JsonObject());
        response.getAsJsonObject("config").addProperty("enabled", global);
        response.add("systems", systems);
        if (global && nodesWar) {
            response.add("nodesWarBattles", loadBattleRecords(nodes, yaml,
                new String[]{"war.nodesWar.stateFile", "war.systems.nodesWar.stateFile"},
                "data/war/nodeswar.json", "data/wars/nodeswar.json", "data/nodeswar/wars.json",
                "data/war/nodes-war-battles.json"));
        }
        if (global && keyPoint) {
            response.add("keyPointBattles", loadBattleRecords(nodes, yaml,
                new String[]{"war.keyPointWars.stateFile", "war.keyPointWar.stateFile", "war.keypoints.stateFile",
                    "war.systems.keyPointWars.stateFile"},
                "data/war/key-point-battles.json", "data/war/keypoint-wars.json",
                "data/wars/keypoint-wars.json", "data/wars/keypoints.json",
                "data/keypoints/battles.json", "data/keypoint-wars.json", "keypoint-wars.json"));
        }
        return GSON.toJson(response);
    }

    private static boolean read(YamlConfiguration yaml, boolean fallback, String... paths) {
        for (String path : paths) {
            if (yaml.isBoolean(path)) return yaml.getBoolean(path);
            if (yaml.isConfigurationSection(path) && yaml.isBoolean(path + ".enabled"))
                return yaml.getBoolean(path + ".enabled");
        }
        return fallback;
    }

    private static void addSystem(JsonObject systems, String id, String name, boolean enabled) {
        var item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("title", name);
        item.addProperty("enabled", enabled);
        systems.add(id, item);
    }

    private static JsonArray loadBattleRecords(JavaPlugin nodes, YamlConfiguration config,
                                               String[] configPaths, String... candidates) {
        var paths = new ArrayList<String>();
        for (var path : configPaths) if (config.isString(path)) paths.add(config.getString(path));
        paths.addAll(Arrays.asList(candidates));
        Path root = nodes.getDataFolder().toPath().toAbsolutePath().normalize();
        for (String name : paths) {
            if (name == null || name.isBlank()) continue;
            Path file = root.resolve(name).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) continue;
            try {
                if (Files.size(file) > MAX_STATE_BYTES) continue;
                String body = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement parsed = JsonParser.parseString(body);
                JsonArray records = extractRecords(parsed);
                if (records != null) return publicRecords(records);
            } catch (IOException | JsonParseException | IllegalStateException ignored) {
                // A save can be mid-write. The next scheduled refresh tries again.
            }
        }
        return new JsonArray();
    }

    /** Never expose staff-only battle metadata or internal participant UUIDs on a public map. */
    private static JsonArray publicRecords(JsonArray records) {
        JsonArray out = new JsonArray();
        var safe = Set.of("id", "name", "battleName", "keyPointName", "strongholdName", "type",
            "strongholdType", "attacker", "defender", "attackingNation", "defendingNation",
            "status", "phase", "winner", "x", "z", "fx", "fz", "blockX", "blockZ",
            "territory", "territoryId", "endsAt", "endAt", "startsAt", "startAt",
            "eliminatedCount", "deaths", "attackerAlive", "defenderAlive", "attackerDeaths",
            "defenderDeaths", "location", "position", "core", "keyPoint", "stronghold");
        for (JsonElement item : records) {
            if (!item.isJsonObject()) continue;
            JsonObject battle = item.getAsJsonObject();
            JsonObject visible = new JsonObject();
            for (String key : safe) {
                if (!battle.has(key)) continue;
                JsonElement value = battle.get(key);
                if (value.isJsonPrimitive()) visible.add(key, value.deepCopy());
                else if (List.of("location", "position", "core", "keyPoint", "stronghold").contains(key)) {
                    if (value.isJsonObject()) {
                        JsonObject position = new JsonObject();
                        for (String field : List.of("x", "z", "name", "type")) {
                            if (value.getAsJsonObject().has(field)
                                && value.getAsJsonObject().get(field).isJsonPrimitive())
                                position.add(field, value.getAsJsonObject().get(field).deepCopy());
                        }
                        visible.add(key, position);
                    } else if (value.isJsonArray() && value.getAsJsonArray().size() == 2
                        && value.getAsJsonArray().get(0).isJsonPrimitive()
                        && value.getAsJsonArray().get(1).isJsonPrimitive()) {
                        visible.add(key, value.deepCopy());
                    }
                }
            }
            if (battle.has("eliminated") && battle.get("eliminated").isJsonArray())
                visible.addProperty("eliminatedCount", battle.getAsJsonArray("eliminated").size());
            if (battle.has("deadPlayers") && battle.get("deadPlayers").isJsonArray())
                visible.addProperty("eliminatedCount", battle.getAsJsonArray("deadPlayers").size());
            out.add(visible);
        }
        return out;
    }

    private static JsonArray extractRecords(JsonElement parsed) {
        if (parsed.isJsonArray()) return parsed.getAsJsonArray();
        if (!parsed.isJsonObject()) return null;
        JsonObject root = parsed.getAsJsonObject();
        for (String key : List.of("battles", "keypoints", "keyPoints", "keyPointBattles",
            "activeBattles", "ongoingBattles", "wars", "events", "active", "sessions")) {
            if (!root.has(key)) continue;
            JsonElement data = root.get(key);
            if (data.isJsonArray()) return data.getAsJsonArray();
            if (data.isJsonObject()) return objectValues(data.getAsJsonObject());
        }
        // Handle an object keyed directly by battle ID.
        if (!root.isEmpty() && root.asMap().values().stream().allMatch(JsonElement::isJsonObject))
            return objectValues(root);
        return null;
    }

    private static JsonArray objectValues(JsonObject source) {
        JsonArray out = new JsonArray();
        for (var entry : source.entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            JsonObject battle = entry.getValue().getAsJsonObject().deepCopy();
            if (!battle.has("id")) battle.addProperty("id", entry.getKey());
            out.add(battle);
        }
        return out;
    }
}
