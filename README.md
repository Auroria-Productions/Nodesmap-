# MilzzyNodesMap — Square Edition (0.3.9-square)

**Square-layout branch** of [MilzzyNodesMap](../../tree/main), designed for Paper 1.21.x and the existing `milzzynodes` core plugin.

[View the standard edition](../../tree/main) · [Releases](../../releases) · [Build requirements](BUILDING.md)

## Square Edition features

- **Square framing** in the public viewer and editor, enclosing the entire configured mapped area.
- **256×256-block sector grid** (16×16 Minecraft chunks), adjusting grid density for large worlds.
- **Square territory painting**: click **Square** or press `Q`, then drag to set the square's side length.
- **Viewer toggle:** Layers → Square-map edition.
- **Editor toggle:** Settings → Square map.
- Preserves the 0.3.8 resource-definition draft fixes, war viewer, towns, claimed-land restrictions, and revision-safe publishing.

## What Square Edition does **not** do

It does **not** automatically reshape, delete or overwrite existing irregularly shaped territories. Existing ownership and core assignments stay the same. The Square tool makes or paints square territory footprints; when **Land only**, **Only paint empty land** or **Lock claimed land** is enabled, skipped chunks can make the drawn result incomplete.

## Compatibility and install

- Paper 1.21.x / Java 21
- Compatible `milzzynodes.jar` installed
- Install **only one** `milzzynodesmap.jar` edition at a time. The `main` and `square-map` branch builds cannot run simultaneously.

1. Stop the server; back up `plugins/milzzynodesmap/`, `world.json`, and the current plugin JAR.
2. Replace the old map JAR with the **Square Edition** JAR from its GitHub Release.
3. Keep the existing compatible MilzzyNodes core in place.
4. Set `web.update-viewer: true` and ensure your dedicated map web port is allocated.
5. Restart Paper; hard-refresh your browser (`Cmd + Shift + R` on Safari).
6. Open `/nodesmap editor`; you should see the **Square** editing tool.
7. Save a reviewed draft and publish it via `/nodesmap publish <draft-file>` as an administrator.

## Viewer and editor commands

| Command | Purpose |
|---|---|
| `/nodesmap link` | Public viewer URL |
| `/nodesmap editor` | Authenticated editor URL |
| `/nodesmap fullrender` | Generate terrain tiles for saved chunks |
| `/nodesmap publish <draft-file>` | Validate and publish a saved draft |

Do not share the private editor authentication token.

## Development and source

This edition is built from the v0.3.8 standard map as a **patch-based Java + browser frontend** distribution (not a complete upstream source rewrite). The build project requires a matching compiled `base.jar`, Java 21, and the included compile dependencies. [Read the rebuild guide](BUILDING.md).

Download [Square Edition v0.3.9](../../releases/tag/v0.3.9-square): [JAR](../../releases/download/v0.3.9-square/MilzzyNodesMap-0.3.9-Square.jar) and [matching source ZIP](../../releases/download/v0.3.9-square/MilzzyNodesMap-0.3.9-Square-Source.zip).

## Related projects

- [Standard MilzzyNodesMap](../../tree/main)
- [Nodes-Extra](https://github.com/Auroria-Productions/Nodes-Extra-)

**Status:** Compiled and tested offline against its supplied project; production Paper/MilzzyNodes compatibility has not been independently confirmed on your UltraServers host.
