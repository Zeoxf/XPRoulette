package id.senzy.util;

import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

public final class LocationUtil {

    /** Kunci blok yang aman dipakai di HashMap (tidak menyimpan referensi World). */
    public record BlockPos(UUID world, int x, int y, int z) {
        public static BlockPos of(Block b) {
            return new BlockPos(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ());
        }

        public static BlockPos of(World w, int x, int y, int z) {
            return new BlockPos(w.getUID(), x, y, z);
        }
    }

    /** Kunci pesan arah mata angin, indeks 0..7 dimulai dari utara searah jarum jam. */
    public static final String[] DIRECTION_KEYS = {
            "north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"
    };

    private LocationUtil() {}

    /** Indeks arah (0=N, 1=NE, ... 7=NW). dx = selisih X (timur +), dz = selisih Z (selatan +). */
    public static int compassIndex(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = utara, 90 = timur
        double norm = (angle % 360 + 360) % 360;
        return (int) Math.round(norm / 45.0) % 8;
    }

    public static double distance2D(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
