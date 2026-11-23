# Building MilzzyNodesMap

The historical v0.3.8 and v0.3.9-square bundles are **patch-build projects**, not complete from-scratch compilations of the original upstream NodesMap.

Their build steps are:

```bash
# With Java 21 and the matching files available:
cd milzzynodesmap
bash rebuild.sh
# output: dist/milzzynodesmap.jar
```

Required inputs (from the supplied historical build archives):

- `milzzynodesmap/base.jar`: the compiled upstream NodesMap 0.3.0 baseline (not the MilzzyNodes core)
- `milzzynodesmap/deps/*.jar`: Paper API, Gson, ASM, Kotlin/Adventure APIs and related compile dependencies
- `milzzynodesmap/src/phonon/nodesmap/compat/*.java`: version-specific compatibility and publishing extensions
- `milzzynodesmap/web/*`: viewer and editor web changes
- `milzzynodesmap/tools/Build.java`: repackaging and bytecode transformations

**Do not accidentally bundle the compile-time MilzzyNodes core or dependency JARs inside the map plugin.** A build-only artifact must still be checked using `unzip -t` and by examining `plugin.yml` for the correct name and edition version.

The compiled map plugin has a dependency on `milzzynodes` at runtime. The original project used Paper 1.21.x and Java 21.

Both historical archives contain large third-party binary dependencies; distributing them requires respecting their original licenses. This repository documents the specific build inputs rather than substituting an unrelated file for a missing base JAR.
