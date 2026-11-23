package phonon.nodesmap.compat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Reproduces the UI's imported preset 'basic' being assigned to 7 territories. */
public final class ResourceDefinitionDraftSmoke {
    private static JsonObject json(String s) {return JsonParser.parseString(s).getAsJsonObject();}
    private static void ok(JsonObject o, String message) {
        if (!o.get("ok").getAsBoolean()) throw new AssertionError(message + " " + o);
    }
    private static void fail(JsonObject o, String expected) {
        if (o.get("ok").getAsBoolean() || !o.get("errors").toString().contains(expected))
            throw new AssertionError("Expected " + expected + " " + o);
    }
    public static void main(String[] args) {
        var before = json("{\"nodes\":{\"steel_mill_1\":{\"cost\":{\"constant\":10}}},\"territories\":{}}");
        for (int i = 606; i < 613; i++) before.getAsJsonObject("territories").add(""+i,
            json("{\"chunks\":["+i+",0],\"coreChunk\":["+i+",0],\"nodes\":[]}"));
        var updated = before.deepCopy();
        updated.getAsJsonObject("nodes").add("basic", json("{\"icon\":null,\"cost\":{\"constant\":0,\"scale\":1},\"priority\":0,\"income\":{}}"));
        for (int i = 606; i < 613; i++) updated.getAsJsonObject("territories").getAsJsonObject(""+i)
            .add("nodes", JsonParser.parseString("[\"basic\"]"));
        var result = NodesLink.validate(before,json("{\"towns\":{}}"),updated);
        ok(result,"The 7 basic assignments should be valid with the new resource definition");
        if (!updated.getAsJsonObject("nodes").has("basic")) throw new AssertionError("Validator discarded definition");
        var edited = before.deepCopy();
        edited.getAsJsonObject("nodes").getAsJsonObject("steel_mill_1").addProperty("priority", 3);
        ok(NodesLink.validate(before,json("{\"towns\":{}}"),edited),"Editing existing resource definition");
        if (edited.getAsJsonObject("nodes").getAsJsonObject("steel_mill_1").get("priority").getAsInt()!=3)
            throw new AssertionError("Existing edit lost");
        var missing = before.deepCopy();
        missing.getAsJsonObject("territories").getAsJsonObject("606").add("nodes",JsonParser.parseString("[\"basic\"]"));
        fail(NodesLink.validate(before,json("{\"towns\":{}}"),missing),"basic");
        var malformed = updated.deepCopy();
        malformed.getAsJsonObject("nodes").addProperty("basic", 42);
        fail(NodesLink.validate(before,json("{\"towns\":{}}"),malformed),"must be a JSON object");
        var deleted = before.deepCopy();
        deleted.getAsJsonObject("nodes").remove("steel_mill_1");
        fail(NodesLink.validate(before,json("{\"towns\":{}}"),deleted),"Cannot remove resource definition");
        var invalidCost = before.deepCopy();
        invalidCost.getAsJsonObject("nodes").add("bad",json("{\"cost\":{\"constant\":\"no\"}}"));
        fail(NodesLink.validate(before,json("{\"towns\":{}}"),invalidCost),"invalid cost field");
        var removals = before.deepCopy();
        removals.getAsJsonObject("territories").remove("606");
        var removedResult = NodesLink.validate(before,json("{\"towns\":{}}"),removals);
        ok(removedResult,"Draft with deletions must be stageable, not publishable");
        if (!removedResult.getAsJsonArray("warnings").toString().contains("offline replacement"))
            throw new AssertionError("Deletions need an explicit safe migration warning");
        System.out.println("PASS: UI basic resource, 7 territories, new and edited definitions, missing/malformed rejection, deletion warning");
    }
}
