package phonon.nodesmap.compat;

/** Verifies the missing MapEditApi startup regression without a running server. */
public final class EditorBridgeSmoke {
    public static void main(String[] args) throws Exception {
        // Map links are deliberately inactive here (same condition as startup).
        NodesLink.stop();
        var hostileLoader = new ClassLoader(EditorBridgeSmoke.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("phonon.nodes.map.MapEditApi")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        Class<?> bridge = NodesLink.editorClass("phonon.nodes.map.MapEditApi", true, hostileLoader);
        if (bridge != NodesLink.class) throw new AssertionError("Map editor compatibility unavailable at startup");
        if (bridge.getMethod("worldJson").getReturnType() != String.class)
            throw new AssertionError("Missing worldJson() bridge");
        if (bridge.getMethod("apply", String.class, boolean.class, boolean.class, String.class)
                  .getReturnType() != String.class)
            throw new AssertionError("Missing apply(json,dryRun,force,actor) bridge");
        Class<?> townBridge = NodesLink.editorClass("phonon.nodes.map.MapTownApi", true, hostileLoader);
        if (townBridge != NodesLink.class) throw new AssertionError("Town writes bypass the draft bridge");
        if (townBridge.getMethod("townsJson").getReturnType() != String.class
            || townBridge.getMethod("act", String.class, String.class).getReturnType() != String.class)
            throw new AssertionError("Missing staged town editor methods");
        if (NodesLink.editorClass("java.lang.String", true, hostileLoader) != String.class)
            throw new AssertionError("Class resolution must not alter other APIs");
        System.out.println("PASS: editor bridge resolves before NodesLink.start() with MapEditApi absent");
    }
}
