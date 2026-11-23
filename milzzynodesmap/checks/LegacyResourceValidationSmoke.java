package phonon.nodesmap.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Regression for an older world whose territory nodes no longer have definitions. */
public final class LegacyResourceValidationSmoke {
    private static JsonObject parse(String raw) { return JsonParser.parseString(raw).getAsJsonObject(); }
    private static JsonObject world() {
        return parse("""
            {"nodes":{"iron":{"income":{"iron_ingot":1}}},"territories":{
              "0":{"name":"Old capital","chunks":[0,0],"coreChunk":[0,0],"nodes":["removed_old_refinery"]},
              "1":{"chunks":[2,0],"coreChunk":[2,0],"nodes":["iron","removed_old_refinery"]}
            }}
            """);
    }
    private static void assertOkay(JsonObject out, String message) {
        if (!out.get("ok").getAsBoolean()) throw new AssertionError(message+": "+out);
    }
    private static void assertFails(JsonObject out, String wanted) {
        if (out.get("ok").getAsBoolean() || !out.getAsJsonArray("errors").toString().contains(wanted))
            throw new AssertionError("Expected blocked change: "+wanted+" got "+out);
    }
    public static void main(String[] args) {
        var towns = parse("{\"towns\":{}}");
        var saved = world();
        var unchanged = NodesLink.validate(saved, towns, saved.deepCopy());
        assertOkay(unchanged,"Untouched legacy resources must not block editor");
        if (unchanged.getAsJsonArray("warnings").size()!=1) throw new AssertionError("Expected one consolidated warning: "+unchanged);
        if (!unchanged.getAsJsonArray("warnings").get(0).getAsString().contains("2 territories"))
            throw new AssertionError("Wrong legacy count: "+unchanged);

        var renamed = saved.deepCopy();
        renamed.getAsJsonObject("territories").getAsJsonObject("0").addProperty("name", "Renamed capital");
        assertOkay(NodesLink.validate(saved,towns,renamed),"Editing name should preserve old resource");
        var added = saved.deepCopy();
        added.getAsJsonObject("territories").add("2",parse("{\"chunks\":[4,0],\"coreChunk\":[4,0],\"nodes\":[\"iron\"]}"));
        assertOkay(NodesLink.validate(saved,towns,added),"New territory with valid resource");

        var newlyAdded = saved.deepCopy();
        newlyAdded.getAsJsonObject("territories").getAsJsonObject("0").add("nodes",parse("{\"x\":[\"removed_old_refinery\",\"not_in_config\"]}").get("x"));
        assertFails(NodesLink.validate(saved,towns,newlyAdded), "not_in_config");
        var duplicates = saved.deepCopy();
        duplicates.getAsJsonObject("territories").getAsJsonObject("0").add("nodes",parse("{\"x\":[\"removed_old_refinery\",\"removed_old_refinery\"]}").get("x"));
        assertFails(NodesLink.validate(saved,towns,duplicates), "removed_old_refinery");
        var newTerritoryUnknown = saved.deepCopy();
        newTerritoryUnknown.getAsJsonObject("territories").add("2",parse("{\"chunks\":[5,0],\"coreChunk\":[5,0],\"nodes\":[\"removed_old_refinery\"]}"));
        assertFails(NodesLink.validate(saved,towns,newTerritoryUnknown), "removed_old_refinery");
        var removedOld = saved.deepCopy();
        removedOld.getAsJsonObject("territories").getAsJsonObject("0").add("nodes",parse("{\"x\":[]}").get("x"));
        assertOkay(NodesLink.validate(saved,towns,removedOld),"Removing obsolete resource is permitted");
        // Reproduces the user's large imported map: 853 territory records
        // whose historical resource definitions were removed. Do not flood
        // the editor or prevent editing every territory in the world.
        var big = parse("{\"nodes\":{},\"territories\":{}}");
        var territories = big.getAsJsonObject("territories");
        for (int i = 0; i < 853; i++) {
            var territory = parse("{\"chunks\":[" + (10+i) + ",1],\"coreChunk\":[" + (10+i) + ",1],\"nodes\":[\"old_missing\"]}");
            territories.add(Integer.toString(i), territory);
        }
        var bigResult = NodesLink.validate(big, towns, big.deepCopy());
        assertOkay(bigResult, "Large historic map should permit drafts");
        if (bigResult.getAsJsonArray("warnings").size() != 1 ||
            !bigResult.getAsJsonArray("warnings").get(0).getAsString().contains("853 territories"))
            throw new AssertionError("Validation must summarise legacy resource problems: " + bigResult);
        var changedDefinitions = saved.deepCopy();
        changedDefinitions.getAsJsonObject("nodes").add("new",parse("{}"));
        assertOkay(NodesLink.validate(saved,towns,changedDefinitions),"New resource definitions may be created in the Resources tab");
        if (!changedDefinitions.getAsJsonObject("nodes").has("new")) throw new AssertionError("New definition was removed");
        System.out.println("PASS: existing undefined resources preserved with one warning; new undefined references rejected; new definitions accepted");
    }
}
