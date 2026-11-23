package phonon.nodesmap.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ValidationSmoke {
    public static void main(String[] args) {
        JsonObject world = parse("""
            {"nodes":{"iron":{}},"territories":{
              "1":{"chunks":[0,0,1,0],"coreChunk":[0,0],"nodes":["iron"]},
              "2":{"chunks":[2,0],"coreChunk":[2,0],"nodes":[]}
            }}
            """);
        var state = parse("""
            {"towns":{"Rome":{"territories":[1],"captured":[2],"home":1,"color":[200,70,21]}}}
            """);
        if (!NodesLink.validate(world, state, world.deepCopy()).get("ok").getAsBoolean())
            throw new AssertionError("Unchanged geometry should pass");
        var moved = world.deepCopy();
        moved.getAsJsonObject("territories").getAsJsonObject("1").add("coreChunk", JsonParser.parseString("[1,0]"));
        if (NodesLink.validate(world, state, moved).get("ok").getAsBoolean())
            throw new AssertionError("Moving a claimed core should fail");
        var removed = world.deepCopy();
        removed.getAsJsonObject("territories").remove("2");
        if (NodesLink.validate(world, state, removed).get("ok").getAsBoolean())
            throw new AssertionError("Removing occupied land should fail");
        String viewer = NodesLink.viewerState(state).toString();
        if (!viewer.contains("Rome") || !viewer.contains("#c84615"))
            throw new AssertionError("Viewer snapshot lost town data");
        System.out.println("PASS: protected core and occupied land rejected, town viewer data present.");
    }

    private static JsonObject parse(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
