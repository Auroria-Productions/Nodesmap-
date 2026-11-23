package phonon.nodesmap.compat;

import java.util.concurrent.atomic.AtomicInteger;

/** Regression test for old Nodes lookup and the preferred renamed-plugin selection. */
public final class RenamedCoreLookupSmoke {
    public static void main(String[] args) {
        AtomicInteger legacyReads = new AtomicInteger();
        String selected = NodesLink.preferEnabled("milzzynodes",
            () -> { legacyReads.incrementAndGet(); return "nodes"; },
            name -> !name.equals("disabled"));
        if (!"milzzynodes".equals(selected) || legacyReads.get() != 0)
            throw new AssertionError("New core must win without probing old one");
        selected = NodesLink.preferEnabled("disabled",
            () -> { legacyReads.incrementAndGet(); return "nodes"; },
            name -> !name.equals("disabled"));
        if (!"nodes".equals(selected) || legacyReads.get() != 1)
            throw new AssertionError("Legacy enabled plugin should be used only as fallback");
        selected = NodesLink.preferEnabled(null, () -> null, name -> true);
        if (selected != null) throw new AssertionError("Missing plugins must fail closed");
        selected = NodesLink.preferEnabled("disabled", () -> "disabled", name -> !name.equals("disabled"));
        if (selected != null) throw new AssertionError("Disabled plugins must fail closed");
        System.out.println("PASS: renamed-core selection, legacy fallback, and fail-closed behavior");
    }
}
