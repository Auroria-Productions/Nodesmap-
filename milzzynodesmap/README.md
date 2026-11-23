# Square Map Edition (0.3.9-square)

See `../SQUARE-MAP-EDITION.md` in the combined source.

# MilzzyNodesMap 0.3.4 for Paper Nodes

NodesMap provides a browser viewer, a territory editor, and its own terrain renderer.

## Install

1. Stop Paper and replace the existing NodesMap JAR in `plugins/` with
   `dist/MilzzyNodesMap-0.3.4-WarViewer-Paper.jar`. Keep the Nodes core JAR and both plugins’ data folders.
2. Start Paper. Check that `web.update-viewer` is enabled in
   `plugins/NodesMap/config.yml` so the updated web files are installed.
3. Open the viewer from `/nodesmap link`. The **Fit territories** button
   returns the view to the mapped area. If that area has no cached terrain,
   the viewer draws the territories and NodesMap attempts a small initial render.
   The renderer supports Minecraft’s older and newer saved block palettes.
   For all saved chunks, run `nodesmap fullrender` in the server console.
   Set `render.bootstrap-radius-chunks` to `0` to disable the initial preview.

## Paint and publish new territories

1. Run `/nodesmap editor` and open the sign-in link.
2. Choose **New**, paint the chunks, then choose **Save draft**. The editor
   validates against the current map and displays a publish command.
3. Run `nodesmap publish <draft-file>` in the server console, or run
   `/nodesmap publish <draft-file>` in game with `nodesmap.admin`. NodesMap
   backs up the previous `world.json`, loads the draft into Nodes, and refreshes
   the public viewer on the next data poll.

Live publishing supports additions and edits that keep existing territory
chunks and cores in place. A draft must use the current map revision and
pass claim and overlap checks. Finish any active flag attacks before publishing.

Drafts are stored under `plugins/NodesMap/drafts/`. For a draft from 0.3.1 or
0.3.2, open the current map in the updated editor first, then use **Import**
to open the old JSON, review the result, and save a new draft.

## If data or terrain is missing

The viewer uses each valid snapshot as it becomes available. It retains the
last good data when a save is temporarily unreadable and retries every five
seconds. After repeated errors the console logs the affected file and reason.
The snapshot limit is controlled by `nodes-sync.max-snapshot-mib`.

If using a public HTTPS URL, configure the web host or reverse proxy with a
certificate valid for that hostname and set `web.public-url` to the matching
external URL. The bundled site is served over HTTP.

## Build

Run `./rebuild.sh` with JDK 21 or newer. The source package includes the
map adapter, viewer and editor changes, and the dependencies needed to rebuild
against the supplied NodesMap base JAR.

Tested with Nodes core dev.5.2 on Paper 1.21.11 and Paper 26.3.


## 0.3.4: Configuration-aware war panel in the PUBLIC VIEWER ONLY

The viewer now shows a separate section for every enabled war subsystem:
Flag War, NodesWar, Key-point Wars, Warzones, and Raids. Each shows its active
battles, or an empty-state message if none are running. Disabled systems are
hidden (including their markers). The top right war pill shows the number of
active systems. Existing town/nation views, editor functionality, renderer,
and publish flow are retained.

NodesMap reads Nodes's `plugins/nodes/config.yml` and publishes a read-only
`/data/nodes/war-systems.json` snapshot via the existing NodesMap web server.
No separate web API, server permission or manually hosted JSON is required.
Updates are polled every five seconds. If the endpoint is unavailable, the
viewer keeps the original live.json functionality.

### Example supported Nodes config

```yaml
war:
  enabled: true
  flagWar:
    enabled: true
  nodesWar:
    enabled: false
    stateFile: data/war/nodeswar.json
  keyPointWars:
    enabled: true
    stateFile: data/war/key-point-battles.json
  warzones:
    enabled: true
  raids:
    enabled: false
```

The publisher understands `war.systems.<name>.enabled` too, and accepts
`keyPointWar`, `keypoints`, and `keyPoints` aliases for the key-point section.
Existing `oldWarSystem` determines the fallback for NodesWar. Explicit
config always overrides that fallback. `raid.enabled` remains supported.

### Key-point battle JSON contract

`plugins/nodes/data/war/key-point-battles.json` (or the configured stateFile):

```json
{
  "battles": [
    {
      "id": "castle-001", "name": "Northern Citadel", "type": "Castle",
      "x": 1600, "z": -800, "attacker": "Kingdom A",
      "defender": "Kingdom B", "status": "LIVE",
      "eliminated": ["fighter1"], "endsAt": 0
    }
  ]
}
```

The viewer also reads key-point and NodesWar events already present in
`live.json`, if the game core publishes them there. It never writes battle
state, changes land ownership, or overrides war sickness. The actual one-life,
no-return-on-death logic must be implemented by the matching Nodes core war
system; the viewer only presents its state.

### Deployment

1. Back up the current NodesMap plugin JAR and its config/data folder.
2. Stop the Paper server and replace only the NodesMap plugin JAR.
3. Keep your existing Nodes core JAR from the war-system implementation.
4. Start Paper; check `web.update-viewer: true` and reload the public viewer
   with a hard refresh. Do not replace your editor files manually.
5. Visit `/data/nodes/war-systems.json` on the map host to confirm the
   displayed enabled states match Nodes config; then open the War panel.

**Compatibility note:** The package is based on the latest available 0.3.3
NodesMap source bundle, not a newer unprovided 0.3.4/war-system source from
the referenced shared chat. Back up first and test on a staging server.
