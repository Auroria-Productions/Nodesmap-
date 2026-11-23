# MilzzyNodesMap

**Two editions of the MilzzyNodes live web map, editor and territory viewer.**

MilzzyNodesMap is a Paper plugin that provides an independent browser map for **MilzzyNodes** territories, nations, towns, terrain, active wars, and an administrator map editor.

> **Editions:** This repository keeps the standard map on the default `main` branch and the square-layout variant on the `square-map` branch. Both compile to the same plugin ID (`milzzynodesmap`). **Install only one edition at a time.**

## Editions

| Edition | Branch | Version | Highlights |
|---|---|---|---|
| **Standard / Dynmap-style map** | [`main`](../../tree/main) | `0.3.8` | Conventional viewer, terrain renderer, editable territories, resources, towns, war overlays |
| **Square Map Edition** | [`square-map`](../../tree/square-map) | `0.3.9-square` | Everything in standard, plus a square world boundary, 256×256 block sector grid and square-territory painting tool |

**Important:** The standard release is a **standalone NodesMap web server**, not the external `webbukkit/Dynmap` plugin. You can use a reverse proxy for an HTTPS map URL, but installing Dynmap is not required. The term *Dynmap-style* only describes the conventional map layout.

## Features

- Live browser-based map with Minecraft terrain tiles and towns, nations and territories
- Private editor link: `/nodesmap editor` (administrators only)
- Public viewer link: `/nodesmap link`
- Territories, town ownership, nations, resources and terrain editing
- War view for Flag War, NodesWar, Key-point Wars, warzones and raids when enabled in the core
- Draft review and live publishing: **Save draft → `/nodesmap publish <draft-file>`**
- Map revision validation, core backup safeguards and claimed-land protection
- Resilient map snapshots and offline terrain rendering: `nodesmap fullrender`

## Requirements

- **Paper 1.21.x**, Java 21 (original builds target the supplied MilzzyNodes Paper 1.21.x environment)
- A compatible **`milzzynodes.jar`** core plugin installed and enabled
- An externally accessible additional TCP/HTTP port on the hosting panel for the web viewer
- Do not install both editions at the same time

## Installation

1. Back up your existing `plugins/milzzynodesmap/` folder and the Nodes `world.json`.
2. Stop your Minecraft server and remove the **old map JAR**, avoiding duplicates.
3. Download your preferred edition's JAR from the [standard release](../../releases/tag/v0.3.8-standard) or [Square Edition release](../../releases/tag/v0.3.9-square) and place it under `plugins/`.
4. Leave your running compatible `milzzynodes.jar` core unchanged.
5. Configure the web port in `plugins/milzzynodesmap/config.yml` to match your host's extra allocation.
6. Set `web.update-viewer: true` so that new website resources are installed.
7. Start the server and confirm `milzzynodesmap` loads via `/plugins`.
8. Use `/nodesmap link`; for editing use the authenticated `/nodesmap editor` URL.

Use `web.public-url` to provide the externally reachable HTTP(S) address, rather than `localhost` or `0.0.0.0`.

## Editor, safety and publishing

Edits are not immediately pushed into the live world. Review your changes in the editor, choose **Save draft**, and run the generated `/nodesmap publish <draft-file>` as an authorized administrator.

**Do not delete or resize existing claimed territories without an offline backup and review.** The publisher intentionally restricts unsafe changes to avoid desynchronizing the live core. The v0.3.8 resource validation update permits existing legacy references and valid new resource definitions in drafts, but newly undefined resources remain errors.

## Branches and releases

- [Standard map source (`main`)](../../tree/main)
- [Square Edition source (`square-map`)](../../tree/square-map)
- [**Standard v0.3.8 release**](../../releases/tag/v0.3.8-standard): [JAR](../../releases/download/v0.3.8-standard/MilzzyNodesMap-0.3.8-Standard.jar) + [source ZIP](../../releases/download/v0.3.8-standard/MilzzyNodesMap-0.3.8-Standard-Source.zip)\n- [**Square v0.3.9 release**](../../releases/tag/v0.3.9-square): [JAR](../../releases/download/v0.3.9-square/MilzzyNodesMap-0.3.9-Square.jar) + [source ZIP](../../releases/download/v0.3.9-square/MilzzyNodesMap-0.3.9-Square-Source.zip)

These editions share a single `milzzynodesmap` Bukkit plugin ID. Switching editions means **replacing** the JAR and restarting—not loading them side by side.

## Rebuilding and source scope

This project originated as a patch-based build over the precompiled `NodesMap` base plugin. The provided working project contains viewer/editor JavaScript, compatibility adapters and ASM packaging tools. Rebuilding the precise release JAR needs the matching `base.jar` plus Paper/ASM/dependency JARs; merely compiling the adapters without the base will not produce a usable plugin. See [BUILDING.md](BUILDING.md).

Some historical bundled files describe older v0.3.4 paths; this repository's versions are **0.3.8 standard** and **0.3.9-square**.

## Related project

- [Nodes-Extra](https://github.com/Auroria-Productions/Nodes-Extra-) — independent logistics and government addon for MilzzyNodes
- [Upstream Phonon Nodes](https://github.com/phonon/minecraft-nodes) — original Nodes ecosystem

## Notes

**Release verification is separate from live-server testing.** Validate any release on a staging copy with the exact MilzzyNodes core before upgrading production.
