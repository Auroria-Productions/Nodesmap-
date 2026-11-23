import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

public final class Build {
    private static final String MAP = "phonon/nodesmap/NodesMapPlugin";
    private static final String EDITOR = "phonon/nodesmap/editor/EditorApi";
    private static final String LINK = "phonon/nodesmap/compat/NodesLink";
    private static final String SCHEDULER_CLASS = "phonon/nodesmap/render/RenderScheduler";
    private static final String SCHEDULER = "L" + SCHEDULER_CLASS + ";";
    private static final String REGION_FOLDER = "(Lorg/bukkit/World;)Ljava/nio/file/Path;";
    private static final String REGION_READER = "phonon/nodesmap/render/RegionReader";
    private static final String READ_PALETTE = "(Lphonon/nodesmap/render/Nbt;)[Lorg/bukkit/Material;";
    private static final String TERRAIN = "phonon/nodesmap/compat/TerrainCompatibility";
    private static final String WEB = "Lphonon/nodesmap/web/WebServer;";

    public static void main(String[] args) throws Exception {
        var classes = Path.of(args[1]);
        var patches = Path.of(args[2]);
        try (var base = new JarFile(args[0]); var out = new JarOutputStream(Files.newOutputStream(Path.of(args[3])))) {
            for (var entry : Collections.list(base.entries())) {
                var name = entry.getName();
                if (entry.isDirectory()) continue;
                byte[] bytes;
                try (var input = base.getInputStream(entry)) { bytes = input.readAllBytes(); }
                if (name.equals(MAP + ".class") || name.equals(EDITOR + ".class")
                    || name.equals(SCHEDULER_CLASS + ".class") || name.equals(REGION_READER + ".class")) {
                    bytes = patch(bytes);
                } else if (name.equals("plugin.yml")) {
                    bytes = new String(bytes, StandardCharsets.UTF_8).replace("version: 0.3.0", "version: 0.3.9-square")
                        .replace("name: NodesMap\n", "name: milzzynodesmap\n")
                        .replace("softdepend: [kotlin, nodes]", "depend: [milzzynodes]\nsoftdepend: [kotlin]\nprovides: [NodesMap]")
                        .getBytes(StandardCharsets.UTF_8);
                } else if (name.equals("web/editor.js") || name.equals("web/editor.html")
                    || name.equals("web/app.js") || name.equals("web/index.html")) {
                    bytes = Files.readAllBytes(patches.resolve(Path.of(name).getFileName()));
                } else if (name.equals("config.yml")) {
                    bytes = (new String(bytes, StandardCharsets.UTF_8).replace("  show: true\n  update-ticks:", "  show: false\n  update-ticks:")
                        .replace("render:\n  # Re-render", "render:\n  # Starter terrain radius in chunks when the map focus has no terrain. Use 0 to disable.\n"
                            + "  bootstrap-radius-chunks: 24\n  # Re-render")
                        + "\n# Limit per Nodes JSON save file. Increase only if your server has enough memory.\nnodes-sync:\n  max-snapshot-mib: 128\n")
                        .getBytes(StandardCharsets.UTF_8);
                }
                write(out, name, bytes);
            }
            // This viewer-only module is new in 0.3.4 and is not in the base JAR.
            write(out, "web/war-systems.js", Files.readAllBytes(patches.resolve("war-systems.js")));
            write(out, "web/square-map.js", Files.readAllBytes(patches.resolve("square-map.js")));
                        try (var files = Files.walk(classes)) {
                for (var path : files.filter(Files::isRegularFile).sorted().toList()) {
                    write(out, classes.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
                }
            }
        }
    }

    private static byte[] patch(byte[] raw) {
        var reader = new ClassReader(raw);
        var writer = new ClassWriter(0);
        var type = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
                var method = super.visitMethod(access, name, desc, sig, exceptions);
                boolean regionFolder = type.equals(SCHEDULER_CLASS) && name.equals("regionFolder") && desc.equals(REGION_FOLDER);
                boolean palette = type.equals(REGION_READER) && name.equals("readPalette") && desc.equals(READ_PALETTE);
                if (regionFolder || palette) {
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, TERRAIN, name, desc, false);
                    method.visitInsn(Opcodes.ARETURN);
                    method.visitMaxs(1, 2);
                    method.visitEnd();
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                        if (type.equals(EDITOR) && name.equals("bridge")
                            && owner.equals("org/bukkit/plugin/PluginManager") && method.equals("getPlugin")
                            && descriptor.equals("(Ljava/lang/String;)Lorg/bukkit/plugin/Plugin;")) {
                            // Original EditorApi looks up "nodes". Resolve the renamed core
                            // without changing the legacy handler's signature or stack shape.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, LINK, "findNodesPlugin",
                                "(Lorg/bukkit/plugin/PluginManager;Ljava/lang/String;)Lorg/bukkit/plugin/Plugin;", false);
                            return;
                        }
                        if (type.equals(EDITOR) && name.equals("bridge")
                            && owner.equals("java/lang/Class") && method.equals("forName")) {
                            super.visitMethodInsn(opcode, LINK, "editorClass", descriptor, false);
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                    }

                    @Override public void visitInsn(int opcode) {
                        if (type.equals(MAP) && opcode == Opcodes.RETURN) {
                            if (name.equals("onEnable")) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, MAP, "web", WEB);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD, MAP, "scheduler", SCHEDULER);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, LINK, "start",
                                    "(Lorg/bukkit/plugin/java/JavaPlugin;" + WEB + SCHEDULER + ")V", false);
                            } else if (name.equals("onDisable")) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, LINK, "stop", "()V", false);
                            }
                        }
                        super.visitInsn(opcode);
                    }

                    @Override public void visitMaxs(int stack, int locals) {
                        super.visitMaxs(Math.max(stack, type.equals(MAP) && name.equals("onEnable") ? 3 : 0), locals);
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    private static void write(JarOutputStream output, String name, byte[] content) throws IOException {
        var entry = new JarEntry(name);
        entry.setTime(0);
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }
}
