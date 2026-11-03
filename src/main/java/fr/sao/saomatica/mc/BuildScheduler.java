package fr.sao.saomatica.mc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Place les blocs par paquets à chaque tick pour ne pas figer le serveur, et garde de quoi annuler. */
public final class BuildScheduler {
    /** Blocs posés par tick. */
    public static final int BLOCKS_PER_TICK = 6000;
    /** Évite les mises à jour de voisins (qui recalculeraient la forme des escaliers) et les drops. */
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    public interface Job {
        /** @return true quand terminé. Appelé sur le thread serveur. */
        boolean step();
    }

    /** Anciens états pour pouvoir annuler. */
    public static final class Undo {
        final ServerWorld world;
        final List<BlockPos> positions = new ArrayList<>();
        final List<BlockState> states = new ArrayList<>();
        Undo(ServerWorld world) { this.world = world; }
        public int size() { return positions.size(); }
    }

    private static final ArrayDeque<Job> JOBS = new ArrayDeque<>();

    private BuildScheduler() {}

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Job j = JOBS.peekFirst();
            if (j != null && j.step()) JOBS.pollFirst();
        });
    }

    /** À appeler depuis le thread serveur. */
    public static void add(Job j) { JOBS.addLast(j); }

    public static int queued() { return JOBS.size(); }

    public static Undo newUndo(ServerWorld world) { return new Undo(world); }

    /** Job de placement d'une grille. */
    public static Job place(ServerWorld world, fr.sao.saomatica.core.BlockGrid grid, BlockPos origin, Undo undo, Runnable done) {
        // pré-parse de la palette sur le thread serveur
        List<String> pal = grid.palette();
        BlockState[] states = new BlockState[pal.size()];
        for (int i = 1; i < pal.size(); i++) states[i] = BlockStates.parse(pal.get(i));
        return new Job() {
            int cursor = 0;
            final BlockPos.Mutable pos = new BlockPos.Mutable();

            @Override
            public boolean step() {
                int budget = BLOCKS_PER_TICK;
                int total = grid.data.length;
                int minY = world.getBottomY(), maxY = world.getTopY();
                while (cursor < total && budget > 0) {
                    int c = cursor++;
                    int idx = grid.data[c];
                    if (idx == 0 || states[idx] == null) continue;
                    int x = c % grid.sx;
                    int z = (c / grid.sx) % grid.sz;
                    int y = c / (grid.sx * grid.sz);
                    int wy = origin.getY() + y;
                    if (wy < minY || wy >= maxY) continue;
                    pos.set(origin.getX() + x, wy, origin.getZ() + z);
                    BlockState old = world.getBlockState(pos);
                    if (old == states[idx]) continue;
                    undo.positions.add(pos.toImmutable());
                    undo.states.add(old);
                    world.setBlockState(pos, states[idx], FLAGS);
                    budget--;
                }
                if (cursor >= total) { done.run(); return true; }
                return false;
            }
        };
    }

    /** Job d'annulation (restaure les anciens états en ordre inverse). */
    public static Job restore(Undo undo, Runnable done) {
        return new Job() {
            int cursor = undo.positions.size() - 1;

            @Override
            public boolean step() {
                int budget = BLOCKS_PER_TICK;
                while (cursor >= 0 && budget-- > 0) {
                    undo.world.setBlockState(undo.positions.get(cursor), undo.states.get(cursor), FLAGS);
                    cursor--;
                }
                if (cursor < 0) { done.run(); return true; }
                return false;
            }
        };
    }
}
