package fr.sao.saomatica.mc;

import fr.sao.saomatica.core.BlockGrid;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Lit une zone du monde et la transforme en BlockGrid (pour l'export .litematic / .schem). */
public final class WorldCapture {
    /** Limite de volume lue d'un coup (thread serveur). */
    public static final long MAX_VOLUME = 4_000_000L;

    private WorldCapture() {}

    public static long volume(BlockPos a, BlockPos b) {
        return (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
    }

    public static BlockGrid capture(ServerWorld world, BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.max(world.getBottomY(), Math.min(a.getY(), b.getY()));
        int y1 = Math.min(world.getTopY() - 1, Math.max(a.getY(), b.getY()));
        int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
        BlockGrid g = new BlockGrid(x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1);
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int y = y0; y <= y1; y++)
            for (int z = z0; z <= z1; z++)
                for (int x = x0; x <= x1; x++) {
                    var st = world.getBlockState(p.set(x, y, z));
                    if (st.isAir()) continue;
                    g.set(x - x0, y - y0, z - z0, BlockStates.format(st));
                }
        return g;
    }
}
