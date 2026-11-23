package phonon.nodesmap.compat;

import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Level;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import kotlin.Unit;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import phonon.nodesmap.NodesMapPlugin;
import phonon.nodesmap.render.RenderScheduler;
import phonon.nodesmap.web.WebServer;

public final class NodesLink {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson DRAFT = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String REVISION = "_nodesmapRevision";
    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final Pattern DRAFT_NAME = Pattern.compile("world-draft-[\\da-f]{8}(?:-[\\da-f]{4}){3}-[\\da-f]{12}\\.json");
    private static final Pattern TOWN_DRAFT_NAME = Pattern.compile("town-draft-[\\da-f]{8}(?:-[\\da-f]{4}){3}-[\\da-f]{12}\\.json");
    private static volatile NodesLink active;
    private final JavaPlugin nodesPlugin;
    private volatile String lastWarSnapshot;
    private final SnapshotFile world;
    private final SnapshotFile towns;
    private final Path drafts;
    private final int maxBytes;
    private final java.util.logging.Logger logger;
    private final java.util.concurrent.atomic.AtomicBoolean refreshing = new java.util.concurrent.atomic.AtomicBoolean();

    private record Stamp(FileTime modified, long size, Object fileKey) {}
    private record Snapshot(Stamp stamp, JsonObject data, String revision) {}
    private static final Snapshot EMPTY_TOWNS = new Snapshot(null, new JsonObject(), "empty");
    private static final class SnapshotFile {
        final Path path;
        volatile Snapshot current;
        volatile boolean found;
        String problem;
        int failures;

        SnapshotFile(Path path) {
            this.path = path.toAbsolutePath().normalize();
        }
    }

    private NodesLink(JavaPlugin nodesPlugin, Path world, Path towns, Path drafts, int maxBytes, java.util.logging.Logger logger) {
        this.nodesPlugin = nodesPlugin;
        this.world = new SnapshotFile(world);
        this.towns = new SnapshotFile(towns);
        this.drafts = drafts;
        this.maxBytes = maxBytes;
        this.logger = logger;
    }

    /**
     * Resolve the actual core plugin name, including support for older installations.
     * EditorApi was compiled against the historical plugin name "nodes". Its
     * getPlugin("nodes") invocation is rewritten to this adapter in Build.java.
     * A genuine milzzynodes instance ALWAYS wins over a legacy nodes instance.
     */
    public static Plugin findNodesPlugin(PluginManager manager, String legacyName) {
        if (manager == null) return null;
        return preferEnabled(manager.getPlugin("milzzynodes"),
            () -> legacyName == null || legacyName.isBlank() || legacyName.equals("milzzynodes")
                ? null : manager.getPlugin(legacyName), Plugin::isEnabled);
    }

    /** Simple, side-effect-free selection used in the editor-startup regression test. */
    static <T> T preferEnabled(T preferred, java.util.function.Supplier<T> legacy,
                               java.util.function.Predicate<T> enabled) {
        if (preferred != null && enabled.test(preferred)) return preferred;
        T fallback = legacy.get();
        return fallback != null && enabled.test(fallback) ? fallback : null;
    }

    public static void start(JavaPlugin map, WebServer web, RenderScheduler renderer) {
        active = null;
        Plugin nodes = findNodesPlugin(map.getServer().getPluginManager(), "nodes");
        if (nodes == null) {
            map.getLogger().warning("Map editor could not find an enabled milzzynodes core. "
                + "Check for outdated/duplicate Nodes JARs; this map expects milzzynodes and uses /nodesmap publish.");
            return;
        }
        try {
            var config = Class.forName("phonon.nodes.Config", true, nodes.getClass().getClassLoader());
            var instance = config.getField("INSTANCE").get(null);
            int maxMiB = Math.clamp(map.getConfig().getInt("nodes-sync.max-snapshot-mib", 128), 16, 256);
            var link = new NodesLink((JavaPlugin) nodes, (Path) config.getMethod("getPathWorld").invoke(instance),
                (Path) config.getMethod("getPathTowns").invoke(instance),
                map.getDataFolder().toPath().resolve("drafts"), maxMiB * 1024 * 1024, map.getLogger());
            active = link;
            if (web != null) {
                map.getServer().getScheduler().runTaskTimerAsynchronously(map, () -> link.refresh(web), 1L, 100L);
            }
            link.registerPublishCommand(map, nodes.getClass().getClassLoader());
            link.bootstrapTerrain((NodesMapPlugin) map, renderer);
            map.getLogger().info("Connected MilzzyNodesMap editor bridge to " + nodes.getName()
                + " " + nodes.getDescription().getVersion()
                + ". Editing uses Save draft -> /nodesmap publish <draft-file>.");
        } catch (ReflectiveOperationException | LinkageError e) {
            map.getLogger().log(Level.WARNING, "This Nodes build does not expose the expected map save paths", e);
        }
    }

    public static void stop() {
        active = null;
    }

    public static Class<?> editorClass(String name, boolean initialize, ClassLoader original)
        throws ClassNotFoundException {
        // EditorApi.bridge() runs during NodesMap.onEnable, before NodesLink.start()
        // publishes the active link at the end of startup. Always expose our
        // editor bridge here; worldJson()/apply() validate the active link when
        // they are invoked, so the early class lookup must never throw.
        // This also preserves the safety-first staged-draft + /nodesmap publish
        // workflow instead of invoking a legacy direct-write MapEditApi.
        if (name.equals("phonon.nodes.map.MapEditApi") || name.equals("phonon.nodes.map.MapTownApi")) return NodesLink.class;
        return Class.forName(name, initialize, original);
    }

    private static Stamp stamp(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) throw new IOException("Expected a regular file");
        return new Stamp(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey());
    }

    private static String revision(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private Snapshot read(SnapshotFile file, boolean townFile, boolean reuse) throws IOException {
        Stamp before;
        try {
            before = stamp(file.path);
        } catch (NoSuchFileException e) {
            if (townFile && !file.found) return EMPTY_TOWNS;
            throw e;
        }
        file.found = true;
        if (reuse && file.current != null && before.equals(file.current.stamp())) return file.current;
        if (before.size() > maxBytes) {
            throw new IOException("File exceeds the " + maxBytes / (1024 * 1024)
                + " MiB limit. Adjust nodes-sync.max-snapshot-mib if the server has enough memory.");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file.path)) {
            bytes = input.readNBytes((int) before.size() + 1);
        }
        if (bytes.length != before.size() || !before.equals(stamp(file.path))) {
            throw new IOException("File changed while being read; retrying");
        }
        JsonObject data;
        try {
            var parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IOException("Expected a JSON object");
            data = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IOException("Invalid JSON (" + e.getClass().getSimpleName() + ")", e);
        }
        var root = townFile ? "towns" : "territories";
        if (!data.has(root) || !data.get(root).isJsonObject()) {
            throw new IOException("Missing JSON object: " + root);
        }
        return new Snapshot(before, data, revision(bytes));
    }

    private void poll(WebServer web, SnapshotFile file, boolean townFile) {
        try {
            var next = read(file, townFile, true);
            if (next != file.current) {
                var mapped = townFile ? viewerState(next.data()) : viewerWorld(next.data());
                web.put(townFile ? "/data/nodes/state.json" : "/data/nodes/world.json",
                    JSON.toJson(mapped), JSON_TYPE);
                file.current = next;
            }
            if (file.failures == 3) logger.info("NodesMap resumed reading " + file.path);
            file.problem = null;
            file.failures = 0;
        } catch (IOException | RuntimeException e) {
            if (townFile && file.current == null) {
                web.put("/data/nodes/state.json", JSON.toJson(viewerState(EMPTY_TOWNS.data())), JSON_TYPE);
                file.current = EMPTY_TOWNS;
            }
            String issue = e instanceof NoSuchFileException ? "File not found" : Objects.toString(e.getMessage(), e.getClass().getSimpleName());
            if (!Objects.equals(issue, file.problem)) {
                file.problem = issue;
                file.failures = 0;
            }
            if (file.failures < 3 && ++file.failures == 3) {
                String fallback = file.current != null && file.current != EMPTY_TOWNS
                    ? " Keeping the last good map data." : "";
                logger.warning("NodesMap could not read " + file.path + ": " + issue + "." + fallback
                    + " It will retry automatically.");
            }
        }
    }

    private void refresh(WebServer web) {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            var previousWorld = world.current;
            var previousTowns = towns.current;
            poll(web, world, false);
            poll(web, towns, true);
            if (world.current != null && towns.current != null &&
                (previousWorld != world.current || previousTowns != towns.current)) {
                web.put("/data/nodes/version.json", JSON.toJson(Map.of("world", world.current.revision(),
                    "state", towns.current.revision())), JSON_TYPE);
            }
            // An independent public read-only snapshot: never overwrite editor or gameplay data.
            try {
                String warSnapshot = WarSystemsPublisher.snapshot(nodesPlugin);
                if (!warSnapshot.equals(lastWarSnapshot)) {
                    web.put("/data/nodes/war-systems.json", warSnapshot, JSON_TYPE);
                    lastWarSnapshot = warSnapshot;
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not refresh viewer war systems; keeping last good data", e);
            }
        } finally {
            refreshing.set(false);
        }
    }

    public static String worldJson() {
        var link = active;
        if (link == null) throw new IllegalStateException("NodesMap is not linked");
        try {
            var current = link.read(link.world, false, false);
            current.data().addProperty(REVISION, current.revision());
            return JSON.toJson(current.data());
        } catch (IOException e) {
            throw new IllegalStateException("Nodes world.json is unavailable", e);
        }
    }

    public static String apply(String input, boolean dryRun, boolean force, String user) {
        var link = active;
        if (link == null) return error("NodesMap is not linked.");
        try {
            var current = link.read(link.world, false, false);
            var savedTowns = link.read(link.towns, true, false);
            var proposed = JsonParser.parseString(input).getAsJsonObject();
            if (!current.revision().equals(string(proposed, REVISION, ""))) {
                return error("The server map changed or this draft has no baseline. Reload the editor before saving.");
            }
            proposed.remove(REVISION);
            var result = validate(current.data(), savedTowns.data(), proposed);
            if (!dryRun && result.get("ok").getAsBoolean()) {
                var path = Files.createDirectories(link.drafts);
                var name = "world-draft-" + UUID.randomUUID() + ".json";
                var tmp = Files.createTempFile(path, "staging-", ".tmp");
                try {
                    proposed.addProperty(REVISION, current.revision());
                    Files.writeString(tmp, DRAFT.toJson(proposed));
                    if (Files.size(tmp) > link.maxBytes) throw new IOException("Draft exceeds the snapshot limit");
                    Files.move(tmp, path.resolve(name), StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
                result.addProperty("staged", true);
                result.addProperty("draft", name);
            }
            return JSON.toJson(result);
        } catch (IOException e) {
            return error("Could not read the current Nodes files or save the draft. Check the server log and try again.");
        } catch (RuntimeException e) {
            return error("Could not validate the draft. Check the JSON and try again.");
        }
    }

    /** Read live town data only. All town writes are staged until an authorised publish. */
    public static String townsJson() {
        var link = active;
        if (link == null) throw new IllegalStateException("NodesMap is not linked");
        try {
            return link.coreTownApi("townsJson", new Class<?>[0]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read current Nodes towns", e);
        }
    }

    private String coreTownApi(String name, Class<?>[] signature, Object... arguments)
        throws ReflectiveOperationException {
        Class<?> api = Class.forName("phonon.nodes.map.MapTownApi", true,
            nodesPlugin.getClass().getClassLoader());
        return (String) api.getMethod(name, signature).invoke(null, arguments);
    }

    /** Called only by NodesMap's authenticated editor API; NEVER executes an operation. */
    public static String act(String input, String actor) {
        var link = active;
        if (link == null) return townError("NodesMap is not linked to the Nodes core.");
        if (input == null || input.length() > 65536) return townError("Town draft is too large.");
        try {
            var operation = JsonParser.parseString(input).getAsJsonObject();
            validateTownOperation(operation);
            var world = link.read(link.world, false, false);
            for (var id : townLand(operation)) {
                if (!object(world.data(), "territories").has(Integer.toString(id))) {
                    return townError("Territory #" + id + " is not published yet. Publish the world draft first.");
                }
            }
            String townRevision = revision(link.coreTownApi("townsJson", new Class<?>[0])
                .getBytes(StandardCharsets.UTF_8));
            var draft = new JsonObject();
            draft.addProperty("kind", "nodesmap-town-operation-v1");
            draft.addProperty("baselineWorld", world.revision());
            draft.addProperty("baselineTowns", townRevision);
            draft.addProperty("createdAt", System.currentTimeMillis());
            draft.addProperty("requestedBy", actor == null ? "editor" : actor);
            draft.add("operation", operation.deepCopy());
            Path folder = Files.createDirectories(link.drafts);
            if (Files.isSymbolicLink(folder)) return townError("The drafts directory cannot be a symlink.");
            String filename = "town-draft-" + UUID.randomUUID() + ".json";
            Path temporary = Files.createTempFile(folder, ".town-staging-", ".tmp");
            try {
                Files.writeString(temporary, DRAFT.toJson(draft), StandardCharsets.UTF_8);
                Files.move(temporary, folder.resolve(filename), StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            link.logger.info("Staged NodesMap town draft " + filename + " from editor session " + actor);
            return JSON.toJson(Map.of("ok", true, "staged", true, "draft", filename,
                "message", "Town change saved as a draft. No live data was modified."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return townError(Objects.toString(e.getMessage(), "Invalid town draft."));
        } catch (IOException | ReflectiveOperationException e) {
            link.logger.log(Level.WARNING, "Could not stage a NodesMap town draft", e);
            return townError("Could not save the town draft. Check the server logs.");
        }
    }

    private static String townError(String reason) {
        return JSON.toJson(Map.of("ok", false, "message", reason));
    }

    /** Conservative syntax validation; core gameplay rules are rechecked on publish. */
    static void validateTownOperation(JsonObject input) {
        String op = string(input, "op", "");
        if (!Set.of("town.create", "town.rename", "town.color", "town.open", "town.home",
                "town.addTerritories", "town.capture", "town.removeTerritories", "town.release",
                "town.setNation", "town.addPlayer", "town.removePlayer", "town.rank", "town.delete",
                "nation.color", "nation.capital", "nation.rename", "nation.delete").contains(op)) {
            throw new IllegalArgumentException("Unknown town/nation operation.");
        }
        for (String field : List.of("name", "town", "nation", "player")) {
            if (input.has(field) && (!input.get(field).isJsonPrimitive() ||
                !input.get(field).getAsJsonPrimitive().isString() ||
                input.get(field).getAsString().length() > 64))
                throw new IllegalArgumentException("Invalid " + field + " field.");
        }
        if (op.equals("town.create") || op.equals("town.rename") || op.equals("nation.rename")) {
            if (!string(input, "name", "").matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,31}"))
                throw new IllegalArgumentException("Town/nation names must have 1–32 letters, digits, _ or -.");
        }
        if (op.equals("town.create") && !string(input,"nation","").isBlank() &&
            !string(input,"nation","").matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Invalid new nation name.");
        if (op.equals("town.open") && (!input.has("open") ||
            !input.get("open").isJsonPrimitive() || !input.get("open").getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("Invalid open setting.");
        if (op.endsWith(".color") && !string(input, "color", "").matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Invalid RGB colour.");
        if (op.equals("town.rank") && !Set.of("leader", "officer", "member").contains(string(input, "rank", "")))
            throw new IllegalArgumentException("Unknown town rank.");
        if (op.startsWith("town.") && !Set.of("town.create", "town.removeTerritories", "town.release").contains(op)
            && string(input, "town", "").isBlank()) throw new IllegalArgumentException("Select a town.");
        if (op.startsWith("nation.") && string(input, "nation", "").isBlank())
            throw new IllegalArgumentException("Select a nation.");
        townLand(input);
    }

    private static List<Integer> townLand(JsonObject operation) {
        String op = string(operation, "op", "");
        if (!Set.of("town.create", "town.removeTerritories", "town.release", "town.home",
                     "town.addTerritories", "town.capture").contains(op)) return List.of();
        if (!operation.has("territories") || !operation.get("territories").isJsonArray())
            throw new IllegalArgumentException("Choose published territories for this change.");
        JsonArray land = operation.getAsJsonArray("territories");
        if (land.isEmpty() || land.size() > 4096) throw new IllegalArgumentException("Choose 1–4096 territories.");
        var ids = new LinkedHashSet<Integer>();
        for (JsonElement v : land) {
            try {
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("Invalid territory ID.");
                int id = v.getAsBigDecimal().intValueExact();
                if (id < 0) throw new IllegalArgumentException("Invalid territory ID.");
                ids.add(id);
            } catch (ArithmeticException e) { throw new IllegalArgumentException("Invalid territory ID."); }
        }
        return new ArrayList<>(ids);
    }

    /** Publishing is called solely by the /nodesmap publish command after permission checks. */
    private void publishTown(CommandSender sender, String filename) {
        try {
            if (!TOWN_DRAFT_NAME.matcher(filename).matches())
                throw new IOException("Invalid town draft filename.");
            Path source = drafts.resolve(filename);
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > 65536)
                throw new IOException("Town draft is missing or too large.");
            JsonObject draft = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            if (!"nodesmap-town-operation-v1".equals(string(draft, "kind", "")))
                throw new IOException("Unrecognised town draft format.");
            JsonObject operation = draft.getAsJsonObject("operation");
            if (operation == null) throw new IOException("Missing town draft operation.");
            validateTownOperation(operation);
            var currentWorld = read(world, false, false);
            if (!currentWorld.revision().equals(string(draft, "baselineWorld", "")))
                throw new IOException("The world changed. Reopen the editor and save a new town draft.");
            String currentTowns = coreTownApi("townsJson", new Class<?>[0]);
            if (!revision(currentTowns.getBytes(StandardCharsets.UTF_8))
                    .equals(string(draft, "baselineTowns", "")))
                throw new IOException("Towns changed since this draft. Reopen the editor and save a fresh draft.");
            // Save a recovery copy before altering live town state. Do not edit world.json.
            Path backupDir = Files.createDirectories(drafts.resolve("backups"));
            Path backup = backupDir.resolve("towns-before-" + filename);
            if (Files.isRegularFile(towns.path, LinkOption.NOFOLLOW_LINKS))
                Files.copy(towns.path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            String response = coreTownApi("act", new Class<?>[] {String.class, String.class},
                JSON.toJson(operation), "console");
            JsonObject result = JsonParser.parseString(response).getAsJsonObject();
            if (!result.has("ok") || !result.get("ok").getAsBoolean())
                throw new IOException(string(result, "message", "The core rejected the operation."));
            // Optional nation assignment is part of the same town creation draft.
            boolean partial = false;
            if ("town.create".equals(string(operation, "op", "")) &&
                !string(operation, "nation", "").isBlank()) {
                JsonObject assign = new JsonObject();
                assign.addProperty("op", "town.setNation");
                assign.addProperty("town", string(operation,"name",""));
                assign.addProperty("nation", string(operation,"nation",""));
                String follow = coreTownApi("act", new Class<?>[] {String.class, String.class},
                    JSON.toJson(assign), "console");
                JsonObject followResult = JsonParser.parseString(follow).getAsJsonObject();
                if (!followResult.has("ok") || !followResult.get("ok").getAsBoolean()) {
                    partial = true;
                    message(sender, "Town was created, but nation assignment failed: "
                        + string(followResult, "message", "unknown reason"));
                }
            }
            Files.delete(source); // single-use approval; prevent replay
            message(sender, "Published town draft " + filename + ". "
                + (partial ? "Check its nation in game." : string(result,"message","Town updated."))
                + (Files.isRegularFile(backup) ? " Backup: " + backup.getFileName() : ""));
            logger.info("NodesMap town draft " + filename + " published by " + sender.getName());
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            message(sender, "Town draft was not published: " + Objects.toString(e.getMessage(), "Unknown error"));
            logger.log(Level.WARNING, "NodesMap town draft publication failed", e);
        }
    }

    private void bootstrapTerrain(NodesMapPlugin map, RenderScheduler renderer) {
        int radius = Math.clamp(map.getConfig().getInt("render.bootstrap-radius-chunks", 24), 0, 32);
        if (radius == 0 || renderer == null) return;
        var renderWorld = renderer.getWorld();
        var spawn = renderWorld.getSpawnLocation();
        var cache = map.getWebRoot().resolve("tiles").resolve(renderWorld.getName()).resolve("0");
        var scheduler = map.getServer().getScheduler();
        scheduler.runTaskLaterAsynchronously(map, () -> {
            try {
                int x = spawn.getBlockX() >> 4;
                int z = spawn.getBlockZ() >> 4;
                try {
                    var snapshot = read(world, false, true);
                    var cores = new ArrayList<int[]>();
                    long sumX = 0, sumZ = 0;
                    for (var element : object(snapshot.data(), "territories").asMap().values()) {
                        if (!element.isJsonObject()) continue;
                        var core = array(element.getAsJsonObject(), "coreChunk");
                        if (core.size() != 2) continue;
                        try {
                            key(core.get(0), core.get(1));
                            var point = new int[] { core.get(0).getAsInt(), core.get(1).getAsInt() };
                            cores.add(point);
                            sumX += point[0];
                            sumZ += point[1];
                        } catch (IllegalArgumentException | ArithmeticException ignored) { }
                    }
                    if (!cores.isEmpty()) {
                        double centerX = (double) sumX / cores.size();
                        double centerZ = (double) sumZ / cores.size();
                        int[] core = Collections.min(cores, Comparator.comparingDouble(c -> Math.hypot(c[0] - centerX, c[1] - centerZ)));
                        x = core[0];
                        z = core[1];
                    }
                } catch (IOException ignored) {
                    // A first-start world may not have any Nodes territory data.
                }
                // Each native terrain tile spans 32 chunks. Check the area the viewer will open on.
                String filename = Math.floorDiv(x, 32) + "_" + Math.floorDiv(z, 32) + ".png";
                if (Files.isRegularFile(cache.resolve(filename))) return;
                int previewX = x, previewZ = z;
                scheduler.runTask(map, () -> {
                    if (renderer.getFullRenderActive()) return;
                    logger.info("NodesMap is rendering an initial terrain preview. Use /nodesmap fullrender for the rest of the world.");
                    renderer.fullRender(previewX, previewZ, radius, total -> Unit.INSTANCE, (progress, cancelled) -> {
                        if (!cancelled && progress.getTotal() == 0) {
                            logger.info("No saved chunks were available for the NodesMap terrain preview.");
                        }
                        return Unit.INSTANCE;
                    });
                });
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "Could not start the initial NodesMap terrain render", e);
            }
        }, 60L);
    }

    private void registerPublishCommand(JavaPlugin map, ClassLoader nodesLoader) {
        var command = Objects.requireNonNull(map.getCommand("nodesmap"));
        command.setExecutor((sender, cmd, label, args) -> {
            if (args.length == 0 || !args[0].equalsIgnoreCase("publish")) return map.onCommand(sender, cmd, label, args);
            if (!sender.hasPermission("nodesmap.admin")) {
                message(sender, "You need nodesmap.admin to publish a draft.");
            } else if (args.length != 2 || !(DRAFT_NAME.matcher(args[1]).matches()
                || TOWN_DRAFT_NAME.matcher(args[1]).matches())) {
                message(sender, "Usage: /nodesmap publish <world-draft-file.json | town-draft-file.json>");
            } else {
                String name = args[1];
                message(sender, "Checking NodesMap draft " + name);
                if (TOWN_DRAFT_NAME.matcher(name).matches()) publishTown(sender, name);
                else map.getServer().getScheduler().runTaskAsynchronously(map, () -> preparePublish(map, nodesLoader, sender, name));
            }
            return true;
        });
        command.setTabCompleter((sender, cmd, label, args) -> {
            var original = map.onTabComplete(sender, cmd, label, args);
            if (args.length != 1 || !sender.hasPermission("nodesmap.admin")
                || !"publish".startsWith(args[0].toLowerCase(Locale.ROOT))) return original;
            var choices = new ArrayList<String>();
            if (original != null) choices.addAll(original);
            choices.add("publish");
            return choices;
        });
    }

    private record Publication(Stamp original, Path replacement, Set<Integer> added,
                               Set<String> newlyDefinedResources) {}

    private void preparePublish(JavaPlugin map, ClassLoader nodesLoader, CommandSender sender, String name) {
        var scheduler = map.getServer().getScheduler();
        Path replacement = null;
        try {
            var current = read(world, false, false);
            Path draft = drafts.resolve(name);
            if (!Files.isRegularFile(draft, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Draft file was not found in the NodesMap drafts directory.");
            var proposed = read(new SnapshotFile(draft), false, false).data();
            if (!current.revision().equals(string(proposed, REVISION, ""))) {
                throw new IOException("The draft is from an older world snapshot. Open the current server map and save a new draft.");
            }
            proposed.remove(REVISION);
            var validation = validate(current.data(), read(towns, true, false).data(), proposed);
            if (!validation.get("ok").getAsBoolean()) {
                throw new IOException("The draft failed validation: " + validation.getAsJsonArray("errors").get(0).getAsString());
            }
            var before = object(current.data(), "territories");
            var after = object(proposed, "territories");
            for (var id : before.keySet()) {
                if (!after.has(id)) throw new IOException("Live publishing requires keeping existing territory #" + id + ".");
                var oldTerritory = before.getAsJsonObject(id);
                var edited = after.getAsJsonObject(id);
                if (!array(oldTerritory, "coreChunk").equals(array(edited, "coreChunk"))
                    || !chunkSet(array(oldTerritory, "chunks")).equals(chunkSet(array(edited, "chunks")))) {
                    throw new IOException("Live publishing requires keeping the existing chunks and core of territory #" + id + ".");
                }
            }
            var added = new TreeSet<Integer>();
            for (var id : after.keySet()) {
                if (!before.has(id)) added.add(Integer.parseInt(id));
            }
            // Preserve node types and other fields owned by the core. Publish only the fields the editor controls.
            for (var entry : after.entrySet()) {
                var edited = entry.getValue().getAsJsonObject();
                var safe = before.has(entry.getKey()) ? before.getAsJsonObject(entry.getKey()).deepCopy() : new JsonObject();
                for (var field : List.of("name", "color", "nodes", "neighbors", "isEdge", "size")) {
                    if (edited.has(field)) safe.add(field, edited.get(field));
                }
                if (!before.has(entry.getKey())) {
                    safe.add("chunks", edited.get("chunks"));
                    safe.add("coreChunk", edited.get("coreChunk"));
                }
                validateCoreFields(safe, entry.getKey(), after);
                entry.setValue(safe);
            }
            replacement = Files.createTempFile(world.path.getParent(), ".nodesmap-", ".tmp");
            Files.writeString(replacement, JSON.toJson(proposed));
            if (Files.size(replacement) > maxBytes) throw new IOException("The published map would exceed the snapshot limit.");
            var newResources = new TreeSet<String>(object(proposed, "nodes").keySet());
            newResources.removeAll(object(current.data(), "nodes").keySet());
            var publication = new Publication(current.stamp(), replacement, added, newResources);
            scheduler.runTask(map, () -> publish(nodesLoader, sender, publication));
            replacement = null;
        } catch (IOException | RuntimeException e) {
            String message = Objects.toString(e.getMessage(), "Unable to read the draft.");
            scheduler.runTask(map, () -> message(sender, "NodesMap draft was not published. " + message));
        } finally {
            deleteTemporary(replacement);
        }
    }

    private static void validateCoreFields(JsonObject data, String id, JsonObject territories) throws IOException {
        var name = data.get("name");
        var color = data.get("color");
        var edge = data.get("isEdge");
        var neighbors = data.get("neighbors");
        if ((name != null && (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()))
            || (color != null && (!color.isJsonPrimitive() || !color.getAsJsonPrimitive().isNumber()))
            || (edge != null && (!edge.isJsonPrimitive() || !edge.getAsJsonPrimitive().isBoolean()))
            || (neighbors != null && !neighbors.isJsonArray())
            || (data.has("nodes") && !data.get("nodes").isJsonArray())) {
            throw new IOException("Territory #" + id + " has invalid map fields.");
        }
        if (color != null) color.getAsBigDecimal().intValueExact();
        for (var neighbor : array(data, "neighbors")) {
            if (!neighbor.isJsonPrimitive() || !neighbor.getAsJsonPrimitive().isNumber()) {
                throw new IOException("Territory #" + id + " has an invalid neighbor.");
            }
            String target = Integer.toString(neighbor.getAsBigDecimal().intValueExact());
            if (target.equals(id) || !territories.has(target)) throw new IOException("Territory #" + id + " has an invalid neighbor.");
        }
    }

    private void publish(ClassLoader nodesLoader, CommandSender sender, Publication publication) {
        Path backup = null;
        boolean replaced = false;
        try {
            var core = Class.forName("phonon.nodes.Nodes", true, nodesLoader);
            var instance = core.getField("INSTANCE").get(null);
            var reload = core.getMethod("reloadWorldJson$nodes", boolean.class, boolean.class, List.class);
            var getTerritory = core.getMethod("getTerritoryFromId-8M4gw-M", int.class);
            var war = core.getMethod("getWar").invoke(instance);
            var attacks = (Map<?, ?>) war.getClass().getMethod("getChunkToAttacker$nodes").invoke(war);
            if (!attacks.isEmpty()) throw new IOException("Wait for active flag attacks to finish before publishing.");
            if (!publication.original().equals(stamp(world.path))) throw new IOException("The world changed while publishing. Save the draft again.");
            for (int id : publication.added()) {
                if (getTerritory.invoke(instance, id) != null) throw new IOException("Nodes already has a live territory #" + id + ".");
            }
            backup = Files.createTempFile(world.path.getParent(), "world-before-nodesmap-", ".json");
            Files.copy(world.path, backup, StandardCopyOption.REPLACE_EXISTING);
            Files.move(publication.replacement(), world.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
            if (!Boolean.TRUE.equals(reload.invoke(instance, false, false, null))) throw new IOException("Nodes could not reload the map.");
            if (!publication.newlyDefinedResources().isEmpty()) {
                var defined = (Map<?, ?>) core.getMethod("getResourceNodes$nodes").invoke(instance);
                for (var resource : publication.newlyDefinedResources()) {
                    if (!defined.containsKey(resource)) {
                        throw new IOException("Nodes did not load resource definition '" + resource
                            + "'. Check the resource JSON for invalid Minecraft materials.");
                    }
                }
            }
            for (int id : publication.added()) {
                if (getTerritory.invoke(instance, id) == null) throw new IOException("Nodes did not load territory #" + id + ".");
            }
            int added = publication.added().size();
            message(sender, "NodesMap published the draft. " + added
                + (added == 1 ? " new territory loaded" : " new territories loaded") + ". Backup: " + backup.getFileName());
            logger.info("NodesMap draft published by " + sender.getName() + ". New territories: " + publication.added().size());
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (replaced) {
                try {
                    Files.move(backup, world.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    message(sender, "NodesMap restored world.json. Restart the server to reset the live map.");
                } catch (IOException restoreError) {
                    e.addSuppressed(restoreError);
                    message(sender, "NodesMap reload failed. Restore " + backup.getFileName() + " and restart the server.");
                }
            }
            message(sender, "NodesMap could not publish the draft. " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
            logger.log(replaced ? Level.SEVERE : Level.INFO, "NodesMap publish failed: " + e);
        } finally {
            deleteTemporary(publication.replacement());
        }
    }

    private static void message(CommandSender sender, String text) {
        ((Audience) sender).sendMessage(Component.text(text));
    }

    private void deleteTemporary(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.warning("Could not remove NodesMap temporary file " + path.getFileName());
        }
    }

    static JsonObject validate(JsonObject original, JsonObject saved, JsonObject edited) {
        var errors = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        int grandfatheredReferences = 0;
        int grandfatheredTerritories = 0;
        String grandfatheredExample = null;
        // The browser Resources tab edits the same world.json > nodes object.
        // Stage those definitions together with territory assignments so resources
        // created in the UI (for example "basic") are not treated as undefined.
        var originalDefinitions = object(original, "nodes");
        var proposedDefinitions = object(edited, "nodes");
        if (!edited.has("nodes") || !edited.get("nodes").isJsonObject()) {
            errors.add("Resource definitions must be a JSON object (world.json > nodes).");
        } else {
            validateResourceDefinitions(originalDefinitions, proposedDefinitions, errors);
        }
        if (!edited.has("territories") || !edited.get("territories").isJsonObject()) {
            errors.add("Expected a territories object.");
        }
        var before = object(original, "territories");
        var after = object(edited, "territories");
        var used = new HashSet<Long>();
        int added = 0, changed = 0, removed = 0;
        for (var item : after.entrySet()) {
            if (errors.size() >= 30) break;
            var id = item.getKey();
            if (!id.matches("0|[1-9][0-9]{0,8}") || !item.getValue().isJsonObject()) {
                errors.add("Invalid territory ID or record: " + id);
                continue;
            }
            if (!before.has(id)) added++;
            else if (!item.getValue().equals(before.get(id))) changed++;
            var itemData = item.getValue().getAsJsonObject();
            var chunks = array(itemData, "chunks");
            var core = array(itemData, "coreChunk");
            var local = chunkSet(chunks);
            if (chunks.isEmpty() || local.size() * 2 != chunks.size() || core.size() != 2 ||
                !local.contains(key(core.get(0), core.get(1)))) {
                errors.add("#" + id + " needs unique integer chunks and a core in the territory.");
            }
            if (local.stream().anyMatch(used::contains)) errors.add("#" + id + " overlaps another territory.");
            used.addAll(local);
            // Previously saved worlds can contain resource names whose definitions
            // were removed by another Nodes version. Never erase these assignments
            // or block unrelated editor changes. However, refuse all NEW undefined
            // names, including extra copies of a grandfathered name.
            var definitions = proposedDefinitions;
            var inherited = new HashMap<String,Integer>();
            if (before.has(id) && before.get(id).isJsonObject()) {
                for (var oldResource : array(before.getAsJsonObject(id), "nodes")) {
                    if (oldResource.isJsonPrimitive() && oldResource.getAsJsonPrimitive().isString()) {
                        inherited.merge(oldResource.getAsString(), 1, Integer::sum);
                    }
                }
            }
            var encounteredUndefined = new HashMap<String,Integer>();
            boolean hadLegacyResource = false;
            for (var resource : array(itemData, "nodes")) {
                if (!resource.isJsonPrimitive() || !resource.getAsJsonPrimitive().isString()) {
                    errors.add("#" + id + " contains an invalid, non-text resource entry.");
                    break;
                }
                String name = resource.getAsString();
                if (definitions.has(name)) continue;
                int currentCount = encounteredUndefined.merge(name, 1, Integer::sum);
                if (currentCount > inherited.getOrDefault(name, 0)) {
                    errors.add("#" + id + " adds undefined resource '" + name + "'. "
                        + "Create it in the Resources tab (and keep the definition), or select an existing resource.");
                    break;
                }
                hadLegacyResource = true;
                grandfatheredReferences++;
                if (grandfatheredExample == null) grandfatheredExample = "#" + id + ": " + name;
            }
            if (hadLegacyResource) grandfatheredTerritories++;
        }
        var protectedIds = new TreeSet<String>();
        for (var value : object(saved, "towns").entrySet()) {
            if (!value.getValue().isJsonObject()) continue;
            var town = value.getValue().getAsJsonObject();
            for (var field : List.of("territories", "captured", "annexed")) {
                for (var id : array(town, field)) if (id.isJsonPrimitive()) protectedIds.add(id.getAsString());
            }
            if (town.has("home") && town.get("home").isJsonPrimitive()) protectedIds.add(town.get("home").getAsString());
        }
        for (var id : before.keySet()) if (!after.has(id)) removed++;
        if (removed > 0) {
            warnings.add(removed + " territories were removed from this draft. The online publisher cannot "
                + "safely remove already-loaded territory IDs. Restore those territories before publishing, "
                + "or export world.json for an offline replacement with the server stopped.");
        }
        for (var id : protectedIds) {
            if (errors.size() >= 30) break;
            if (!before.has(id)) continue;
            if (!after.has(id) || !after.get(id).isJsonObject()) {
                errors.add("Cannot remove claimed or occupied territory #" + id + ".");
                continue;
            }
            var a = before.getAsJsonObject(id);
            var b = after.getAsJsonObject(id);
            if (!chunkSet(array(a, "chunks")).equals(chunkSet(array(b, "chunks"))) ||
                !array(a, "coreChunk").equals(array(b, "coreChunk"))) {
                errors.add("Cannot move the chunks or core of claimed or occupied territory #" + id + ".");
            }
        }
        if (grandfatheredReferences > 0) {
            warnings.add(grandfatheredTerritories + " territories contain " + grandfatheredReferences
                + " existing resource references missing from world.json definitions (example "
                + grandfatheredExample + "). These are preserved as-is and do not prevent saving a draft. "
                + "Restore their definitions in the core world.json if the resources are meant to function.");
        }
        // Keep all non-editor fields authoritative, but retain the validated
        // resource definitions, which are explicitly editable in the UI.
        edited.keySet().removeIf(k -> !k.equals("territories") && !k.equals("nodes"));
        for (var entry : original.entrySet()) {
            if (!entry.getKey().equals("territories") && !entry.getKey().equals("nodes")) {
                edited.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return JSON.toJsonTree(Map.of("ok", errors.isEmpty(), "applied", false, "errors", errors,
            "warnings", warnings, "added", added, "changed", changed, "removed", removed)).getAsJsonObject();
    }


    /**
     * Validate browser-created resource definitions without permitting removal of
     * already-loaded definitions. The Nodes resource loader populates a live map
     * with putAll(), so removals are NOT safe without a full restart.
     * Unknown extra keys are retained for forward compatibility with core forks.
     */
    private static void validateResourceDefinitions(JsonObject before, JsonObject after, List<String> errors) {
        if (after.size() > 4096) {
            errors.add("Too many resource definitions (limit 4096).");
            return;
        }
        for (var entry : before.entrySet()) {
            if (!after.has(entry.getKey())) {
                errors.add("Cannot remove resource definition '" + entry.getKey() +
                    "' while the server is running. Restore it or make an offline world.json migration.");
                if (errors.size() >= 30) return;
            }
        }
        for (var entry : after.entrySet()) {
            String name = entry.getKey();
            // Existing definitions are already in production and may use fork-specific syntax.
            if (before.has(name) && before.get(name).equals(entry.getValue())) continue;
            if (!name.matches("[A-Za-z0-9_][A-Za-z0-9_.:-]{0,63}")) {
                errors.add("Invalid resource definition name '" + name + "'. Use letters, numbers, _, ., : or -.");
            } else if (!entry.getValue().isJsonObject()) {
                errors.add("Resource '" + name + "' must be a JSON object, not an array or string.");
            } else {
                JsonObject def = entry.getValue().getAsJsonObject();
                if (def.has("icon") && !def.get("icon").isJsonNull() &&
                    (!def.get("icon").isJsonPrimitive() || !def.getAsJsonPrimitive("icon").isString())) {
                    errors.add("Resource '" + name + "' has an invalid icon; expected a string or null.");
                }
                for (var key : List.of("income", "ore", "crops", "animals", "income_multiplier",
                    "ore_multiplier", "crops_multiplier", "animals_multiplier", "neighbor_income",
                    "neighbor_ore", "neighbor_crops", "neighbor_animals", "neighbor_income_multiplier",
                    "neighbor_ore_multiplier", "neighbor_crops_multiplier", "neighbor_animals_multiplier", "cost")) {
                    if (def.has(key) && !def.get(key).isJsonObject()) {
                        errors.add("Resource '" + name + "' has an invalid '" + key + "' section (expected an object).");
                    }
                }
                for (var key : List.of("priority", "income_total_multiplier", "ore_total_multiplier",
                    "crops_total_multiplier", "animals_total_multiplier", "neighbor_income_total_multiplier",
                    "neighbor_ore_total_multiplier", "neighbor_crops_total_multiplier",
                    "neighbor_animals_total_multiplier", "attacker_time_multiplier", "defender_time_multiplier")) {
                    if (def.has(key) && !finiteNumber(def.get(key))) {
                        errors.add("Resource '" + name + "' has invalid numeric field '" + key + "'.");
                    }
                }
                if (def.has("cost") && def.get("cost").isJsonObject()) {
                    for (var key : List.of("constant", "scale")) {
                        if (def.getAsJsonObject("cost").has(key) &&
                            !finiteNumber(def.getAsJsonObject("cost").get(key))) {
                            errors.add("Resource '" + name + "' has invalid cost field '" + key + "'.");
                        }
                    }
                }
            }
            if (errors.size() >= 30) return;
        }
    }

    private static boolean finiteNumber(JsonElement input) {
        try {
            return input.isJsonPrimitive() && input.getAsJsonPrimitive().isNumber() &&
                Double.isFinite(input.getAsDouble());
        } catch (RuntimeException ex) { return false; }
    }

    private static Set<Long> chunkSet(JsonArray chunks) {
        var result = new HashSet<Long>();
        for (int i = 0; i + 1 < chunks.size(); i += 2) result.add(key(chunks.get(i), chunks.get(i + 1)));
        return result;
    }

    private static long key(JsonElement x, JsonElement z) {
        if (!x.isJsonPrimitive() || !x.getAsJsonPrimitive().isNumber() ||
            !z.isJsonPrimitive() || !z.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Chunk coordinates must be integers");
        int xInt = x.getAsBigDecimal().intValueExact();
        int zInt = z.getAsBigDecimal().intValueExact();
        if (Math.abs((long) xInt) > 1_875_000 || Math.abs((long) zInt) > 1_875_000) {
            throw new IllegalArgumentException("Chunk is outside the Minecraft world bounds");
        }
        return ((long) xInt << 32) | (zInt & 0xffffffffL);
    }

    private static String error(String message) {
        return JSON.toJson(Map.of("ok", false, "applied", false, "errors", List.of(message), "warnings", List.of()));
    }

    private static JsonObject object(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonObject() ? o.getAsJsonObject(name) : new JsonObject();
    }

    private static JsonArray array(JsonObject o, String name) {
        return o.has(name) && o.get(name).isJsonArray() ? o.getAsJsonArray(name) : new JsonArray();
    }

    private static String string(JsonObject o, String name, String fallback) {
        return o.has(name) && o.get(name).isJsonPrimitive() ? o.get(name).getAsString() : fallback;
    }

    private static String color(JsonObject object) {
        var color = array(object, "color");
        if (color.size() != 3) return "#aab3bb";
        try {
            return String.format("#%02x%02x%02x", channel(color.get(0)), channel(color.get(1)), channel(color.get(2)));
        } catch (RuntimeException e) {
            return "#aab3bb";
        }
    }

    private static int channel(JsonElement value) {
        return Math.clamp(value.getAsInt(), 0, 255);
    }

    static Map<String,Object> viewerWorld(JsonObject raw) {
        var territories = new ArrayList<Map<String,Object>>();
        for (var entry : object(raw, "territories").entrySet()) {
            if (!entry.getValue().isJsonObject() || !entry.getKey().matches("0|[1-9][0-9]{0,8}")) continue;
            var r = entry.getValue().getAsJsonObject();
            var chunks = array(r, "chunks");
            var core = array(r, "coreChunk");
            if (chunks.size() < 2 || chunks.size() % 2 != 0 || core.size() != 2) continue;
            territories.add(Map.of("id", Integer.parseInt(entry.getKey()), "name", string(r, "name", ""),
                "chunks", chunks, "core", core, "color", r.has("color") ? r.get("color") : JsonNull.INSTANCE,
                "resources", array(r, "nodes"), "neighbors", array(r, "neighbors")));
        }
        return Map.of("territories", territories, "resources", object(raw, "nodes"));
    }

    static Map<String,Object> viewerState(JsonObject raw) {
        var towns = new ArrayList<Map<String,Object>>();
        var nations = new ArrayList<Map<String,Object>>();
        var membership = new HashMap<String,String>();
        for (var entry : object(raw, "nations").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            var r = entry.getValue().getAsJsonObject();
            for (var town : array(r, "towns")) if (town.isJsonPrimitive()) membership.put(town.getAsString(), entry.getKey());
            nations.add(Map.of("name", entry.getKey(), "color", color(r), "capital", string(r, "capital", ""),
                "allies", array(r, "allies"), "enemies", array(r, "enemies")));
        }
        var residents = object(raw, "residents");
        for (var entry : object(raw, "towns").entrySet()) {
            if (!entry.getValue().isJsonObject()) continue;
            var r = entry.getValue().getAsJsonObject();
            var names = new ArrayList<String>();
            for (var id : array(r, "residents")) {
                if (!id.isJsonPrimitive()) continue;
                var member = object(residents, id.getAsString());
                var name = string(member, "name", "");
                if (!name.isEmpty()) names.add(name);
            }
            towns.add(Map.of("name", entry.getKey(), "nation", membership.getOrDefault(entry.getKey(), ""),
                "residents", names, "home", r.has("home") ? r.get("home") : JsonNull.INSTANCE, "color", color(r),
                "allies", array(r, "allies"), "enemies", array(r, "enemies"), "territories", array(r, "territories"),
                "captured", array(r, "captured")));
        }
        return Map.of("towns", towns, "nations", nations);
    }
}
