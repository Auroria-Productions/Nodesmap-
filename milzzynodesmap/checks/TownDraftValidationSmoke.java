package phonon.nodesmap.compat;
import com.google.gson.JsonParser;
public class TownDraftValidationSmoke {
    private static void accept(String data) {
        NodesLink.validateTownOperation(JsonParser.parseString(data).getAsJsonObject());
    }
    private static void reject(String data) {
        try { accept(data); throw new AssertionError("Accepted invalid town request: " + data); }
        catch (IllegalArgumentException expected) { /* valid rejection */ }
    }
    public static void main(String[] args) {
        accept("{\"op\":\"town.create\",\"name\":\"Rome\",\"territories\":[1,2],\"nation\":\"Empire\"}");
        accept("{\"op\":\"town.setNation\",\"town\":\"Rome\",\"nation\":\"Empire\"}");
        accept("{\"op\":\"town.release\",\"territories\":[7]}");
        accept("{\"op\":\"nation.color\",\"nation\":\"Empire\",\"color\":\"#ffaa44\"}");
        reject("{\"op\":\"town.create\",\"name\":\"bad town\",\"territories\":[1]}");
        reject("{\"op\":\"town.create\",\"name\":\"Rome\",\"territories\":[]}");
        reject("{\"op\":\"town.create\",\"name\":\"Rome\",\"territories\":[1.5]}");
        reject("{\"op\":\"town.create\",\"name\":\"Rome\",\"territories\":[-7]}");
        reject("{\"op\":\"town.color\",\"town\":\"Rome\",\"color\":\"red\"}");
        reject("{\"op\":\"town.rank\",\"town\":\"Rome\",\"rank\":\"admin\"}");
        reject("{\"op\":\"town.delete\",\"town\":\"\"}");
        reject("{\"op\":\"internal.admin\",\"town\":\"Rome\"}");
        System.out.println("PASS: staged town operation syntax and safety validation");
    }
}
