# Square Map release files

Upload BOTH files into this `release-inputs/` directory on the **`square-map` branch**, keeping their exact names:

1. `MilzzyNodesMap-0.3.9-Square.jar`
2. `MilzzyNodesMap-0.3.9-Square-Source.zip`

GitHub Actions will verify the JAR's `plugin.yml`, inspect the matching source archive, extract editable `milzzynodesmap/src/`, `web/`, `tools/`, `checks/`, `examples/` (without vendored binary dependencies), and publish a GitHub Release with those two exact assets.

**Why this exists:** The GitHub connector can write source text but cannot directly upload the existing binary JAR and ZIP attachments. The repository action handles the binary attachments once GitHub receives them.

The source ZIP includes the historical upstream compiled `base.jar` and dependencies required to rebuild its patch-based release. Those binary inputs are intentionally excluded from the readable repository code tree.

Only use the JAR for your installed edition; never install both editions at the same time.
