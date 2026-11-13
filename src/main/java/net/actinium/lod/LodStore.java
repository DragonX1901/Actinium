package net.actinium.lod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

final class LodStore {
    private static final int VERSION = 1;

    private final Path directory;

    LodStore(Path directory) {
        this.directory = directory;
    }

    private Path file(LodTile tile) {
        return this.directory.resolve(tile.x + "." + tile.z + ".lod");
    }

    void load(LodTile tile) {
        Path file = this.file(tile);
        if (Files.isRegularFile(file)) {
            try (DataInputStream in = new DataInputStream(new InflaterInputStream(Files.newInputStream(file)))) {
                if (in.readInt() == VERSION) {
                    int count = LodTile.CELLS * LodTile.CELLS;
                    short[] heights = new short[count];
                    int[] colors = new int[count];
                    for (int i = 0; i < count; i++) {
                        heights[i] = in.readShort();
                    }

                    for (int i = 0; i < count; i++) {
                        colors[i] = in.readInt();
                    }

                    for (int i = 0; i < count; i++) {
                        if (tile.heights[i] == LodTile.EMPTY && heights[i] != LodTile.EMPTY) {
                            tile.colors[i] = colors[i];
                            tile.heights[i] = heights[i];
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        tile.loaded = true;
    }

    void save(LodTile tile) {
        try {
            Files.createDirectories(this.directory);
            Path file = this.file(tile);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(VERSION);
                for (short height : tile.heights) {
                    out.writeShort(height);
                }

                for (int color : tile.colors) {
                    out.writeInt(color);
                }
            }

            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }
}
