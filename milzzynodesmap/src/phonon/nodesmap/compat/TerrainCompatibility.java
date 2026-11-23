package phonon.nodesmap.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Material;
import org.bukkit.World;
import phonon.nodesmap.render.BlockColors;
import phonon.nodesmap.render.Nbt;

public final class TerrainCompatibility {
    private TerrainCompatibility() {}

    public static Path regionFolder(World world) {
        var folder = world.getWorldFolder().toPath();
        var dimension = world.getKey();
        var nested = folder.resolve("dimensions").resolve(dimension.getNamespace())
            .resolve(dimension.getKey()).resolve("region");
        if (Files.isDirectory(nested)) return nested;
        // On older Paper each dimension has its own Bukkit world folder.
        var direct = folder.resolve("region");
        var legacy = switch (world.getEnvironment()) {
            case NETHER -> folder.resolve("DIM-1").resolve("region");
            case THE_END -> folder.resolve("DIM1").resolve("region");
            default -> direct;
        };
        return Files.isDirectory(legacy) || !Files.isDirectory(direct) ? legacy : direct;
    }

    public static Material[] readPalette(Nbt nbt) {
        int type = nbt.readUnsignedByte();
        int count = nbt.readInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("Invalid block palette length: " + count);
        if (type != Nbt.STRING && type != Nbt.COMPOUND) {
            for (int i = 0; i < count; i++) nbt.skip(type);
            return new Material[0];
        }
        var palette = new Material[count];
        for (int i = 0; i < count; i++) {
            // Recent Minecraft saves plain block states as strings. States with properties
            // and older saves use compound entries containing the block name.
            palette[i] = type == Nbt.STRING ? BlockColors.INSTANCE.byName(nbt.readUTF()) : readBlockState(nbt);
        }
        return palette;
    }

    private static Material readBlockState(Nbt nbt) {
        Material material = Material.AIR;
        int tag;
        while ((tag = nbt.readUnsignedByte()) != Nbt.END) {
            String key = nbt.readUTF();
            if (tag == Nbt.STRING && (key.equals("Name") || key.isEmpty())) {
                material = BlockColors.INSTANCE.byName(nbt.readUTF());
            } else {
                nbt.skip(tag);
            }
        }
        return material;
    }
}
