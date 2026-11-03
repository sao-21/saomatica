package fr.sao.saomatica.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;

/** Associe chaque voxel à un bloc Minecraft (port de block_mesh.ts + block_assigner.ts). */
public final class BlockAssigner {
    private static final double ALPHA_BIAS = 1.0;

    private static final Set<String> FALLABLE = Set.of(
        "minecraft:anvil", "minecraft:lime_concrete_powder", "minecraft:orange_concrete_powder",
        "minecraft:black_concrete_powder", "minecraft:brown_concrete_powder", "minecraft:cyan_concrete_powder",
        "minecraft:light_gray_concrete_powder", "minecraft:purple_concrete_powder", "minecraft:magenta_concrete_powder",
        "minecraft:light_blue_concrete_powder", "minecraft:yellow_concrete_powder", "minecraft:white_concrete_powder",
        "minecraft:blue_concrete_powder", "minecraft:red_concrete_powder", "minecraft:gray_concrete_powder",
        "minecraft:pink_concrete_powder", "minecraft:green_concrete_powder", "minecraft:dragon_egg",
        "minecraft:gravel", "minecraft:pointed_dripstone", "minecraft:red_sand", "minecraft:sand", "minecraft:scaffolding");

    private static final Set<String> GRASS_LIKE = Set.of(
        "minecraft:grass_block", "minecraft:grass_path", "minecraft:dirt_path", "minecraft:podzol",
        "minecraft:crimson_nylium", "minecraft:warped_nylium", "minecraft:mycelium", "minecraft:farmland");

    private static final Set<String> TRANSPARENT = Set.of(
        "minecraft:frosted_ice", "minecraft:glass", "minecraft:white_stained_glass", "minecraft:orange_stained_glass",
        "minecraft:magenta_stained_glass", "minecraft:light_blue_stained_glass", "minecraft:yellow_stained_glass",
        "minecraft:lime_stained_glass", "minecraft:pink_stained_glass", "minecraft:gray_stained_glass",
        "minecraft:light_gray_stained_glass", "minecraft:cyan_stained_glass", "minecraft:purple_stained_glass",
        "minecraft:blue_stained_glass", "minecraft:brown_stained_glass", "minecraft:green_stained_glass",
        "minecraft:red_stained_glass", "minecraft:black_stained_glass", "minecraft:ice", "minecraft:oak_leaves",
        "minecraft:spruce_leaves", "minecraft:birch_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves",
        "minecraft:dark_oak_leaves", "minecraft:mangrove_leaves", "minecraft:azalea_leaves",
        "minecraft:flowering_azalea_leaves", "minecraft:slime_block", "minecraft:honey_block");

    private static final double[] BAYER = {
        0, 16, 2, 18, 48, 32, 50, 34, 6, 22, 4, 20, 54, 38, 52, 36,
        24, 40, 26, 42, 8, 56, 10, 58, 30, 46, 28, 44, 14, 62, 12, 60,
        3, 19, 5, 21, 51, 35, 53, 37, 1, 17, 7, 23, 49, 33, 55, 39,
        27, 43, 29, 45, 11, 59, 13, 61, 25, 41, 31, 47, 9, 57, 15, 63};

    /** Un bloc assigné à un voxel. */
    public static final class Assigned {
        public final VoxelMesh.Voxel voxel;
        public String name;
        Assigned(VoxelMesh.Voxel v, String n) { voxel = v; name = n; }
    }

    private static final class Collection {
        final List<Atlas.Block> blocks = new ArrayList<>();
        final Map<Long, Atlas.Block> cache = new HashMap<>();
    }

    private BlockAssigner() {}

    private static Collection collection(List<String> palette, Predicate<String> exists, Set<String> exclude) {
        Collection c = new Collection();
        Atlas atlas = Atlas.get();
        Set<String> seen = new HashSet<>();
        for (String name : palette) {
            if (exclude != null && exclude.contains(name)) continue;
            Atlas.Block b = atlas.blocks.get(name);
            if (b == null || !seen.add(name) || !exists.test(name)) continue;
            c.blocks.add(b);
        }
        if (c.blocks.isEmpty()) throw new IllegalStateException("Palette vide : aucun bloc utilisable");
        return c;
    }

    public static List<Assigned> assign(VoxelMesh vm, Options opt, Predicate<String> exists, DoubleConsumer progress) {
        List<String> pal = Palettes.get(opt.palette);
        Collection all = collection(pal, exists, null);
        Collection nonFall = collection(pal, exists, FALLABLE);
        double resolution = Math.pow(2, opt.colourAccuracy);
        double errW = opt.errorWeight / 10.0;

        List<VoxelMesh.Voxel> voxels = vm.getVoxels();
        Map<Long, Assigned> byPos = new HashMap<>();
        List<Assigned> out = new ArrayList<>(voxels.size());
        List<Object[]> grass = new ArrayList<>();

        for (int i = 0; i < voxels.size(); i++) {
            if ((i & 4095) == 0 && progress != null) progress.accept((double) i / voxels.size());
            VoxelMesh.Voxel v = voxels.get(i);
            double[] col = finalColour(v, opt, resolution);
            int vis = opt.contextualAveraging ? vm.faceVisibility(v.x, v.y, v.z) : VoxelMesh.F_ALL;
            double[] mod = applyModifier(col, v, vm, opt.aoWeight / 100.0, opt.normalWeight / 100.0);
            Atlas.Block blk = best(mod, all, vis, errW);

            boolean fallable = FALLABLE.contains(blk.name);
            boolean supported = vm.has(v.x, v.y - 1, v.z);
            boolean replace = (opt.fallable.equals("replace-fallable") && fallable)
                || (opt.fallable.equals("replace-falling") && fallable && !supported);
            if (replace) blk = best(mod, nonFall, vis, errW);
            Assigned a = new Assigned(v, blk.name);
            if (GRASS_LIKE.contains(blk.name)) grass.add(new Object[] {a, col, vis});
            byPos.put(VoxelMesh.key(v.x, v.y, v.z), a);
            out.add(a);
        }
        if (!grass.isEmpty()) {
            Set<String> exc = new HashSet<>(GRASS_LIKE);
            Collection nonGrass = collection(pal, exists, exc);
            for (Object[] g : grass) {
                Assigned a = (Assigned) g[0];
                Assigned top = byPos.get(VoxelMesh.key(a.voxel.x, a.voxel.y + 1, a.voxel.z));
                if (top != null && !TRANSPARENT.contains(top.name)) {
                    a.name = best((double[]) g[1], nonGrass, (Integer) g[2], errW).name;
                }
            }
        }
        return out;
    }

    private static double[] finalColour(VoxelMesh.Voxel v, Options opt, double res) {
        double[] c = new double[4];
        for (int i = 0; i < 3; i++) c[i] = Math.floor(Math.floor(v.colour[i] * res) * (255 / res));
        c[3] = Math.floor(Math.ceil(v.colour[3] * res) * (255 / res));
        switch (opt.dithering) {
            case "random": {
                double off = (ThreadLocalRandom.current().nextDouble() - 0.5) * opt.ditheringMagnitude;
                c[0] += off; c[1] += off; c[2] += off;
                break;
            }
            case "ordered": {
                int x = Math.abs(v.x % 4), y = Math.abs(v.y % 4), z = Math.abs(v.z % 4);
                double off = (BAYER[x + 4 * y + 16 * z] / 64.0 - 0.5) * opt.ditheringMagnitude;
                c[0] += off; c[1] += off; c[2] += off;
                break;
            }
            default: break;
        }
        return c;
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static double[] applyModifier(double[] c, VoxelMesh.Voxel v, VoxelMesh vm, double ao, double nw) {
        int solid = 0;
        for (int[] f : FACES) if (vm.has(v.x + f[0], v.y + f[1], v.z + f[2])) solid++;
        double aoFactor = 1.0 - (solid / 6.0) * ao;
        double nf = 1.0;
        if (v.normalSamples > 0) {
            double mag = Math.sqrt(v.normal[0] * v.normal[0] + v.normal[1] * v.normal[1] + v.normal[2] * v.normal[2]);
            double ny = mag > 1e-6 ? v.normal[1] / mag : 0.0;
            nf = 1.0 - nw * (0.5 - (ny + 1.0) / 2.0);
        }
        double minB = (ao == 0 && nw == 0) ? 1.0 : 0.65;
        double mod = Math.max(minB, Math.min(1.0, aoFactor * nf));
        return new double[] {
            Math.max(0, Math.min(255, Math.round(c[0] * mod))),
            Math.max(0, Math.min(255, Math.round(c[1] * mod))),
            Math.max(0, Math.min(255, Math.round(c[2] * mod))),
            c[3]};
    }

    private static Atlas.Block best(double[] col, Collection coll, int vis, double errW) {
        long key = ((((long) ((int) col[0] + 512) & 0x3FF) << 20) | (((long) ((int) col[1] + 512) & 0x3FF) << 10)
            | ((long) ((int) col[2] + 512) & 0x3FF)) << 6 | vis;
        Atlas.Block cached = coll.cache.get(key);
        if (cached != null) return cached;
        double r = col[0] / 255, g = col[1] / 255, b = col[2] / 255, a = col[3] / 255;
        double min = Double.POSITIVE_INFINITY;
        Atlas.Block choice = null;
        for (Atlas.Block blk : coll.blocks) {
            double[] bc = blk.colour;
            double std = 0.0;
            if (vis != 0) {
                double[] avg = new double[4];
                int count = 0;
                for (int f = 0; f < 6; f++) {
                    if ((vis & faceBit(f)) == 0) continue;
                    double[] fc = blk.faceColour[f];
                    for (int k = 0; k < 4; k++) avg[k] += fc[k];
                    std += blk.faceStd[f];
                    count++;
                }
                for (int k = 0; k < 4; k++) avg[k] /= count;
                std /= count;
                bc = avg;
            }
            double rgb = (r - bc[0]) * (r - bc[0]) + (g - bc[1]) * (g - bc[1]) + (b - bc[2]) * (b - bc[2])
                + (a - bc[3]) * (a - bc[3]) * ALPHA_BIAS;
            double total = (1 - errW) * rgb + errW * std;
            if (total < min) { min = total; choice = blk; }
        }
        coll.cache.put(key, choice);
        return choice;
    }

    private static int faceBit(int atlasFace) {
        switch (atlasFace) {
            case Atlas.UP: return VoxelMesh.F_UP;
            case Atlas.DOWN: return VoxelMesh.F_DOWN;
            case Atlas.NORTH: return VoxelMesh.F_NORTH;
            case Atlas.EAST: return VoxelMesh.F_EAST;
            case Atlas.SOUTH: return VoxelMesh.F_SOUTH;
            default: return VoxelMesh.F_WEST;
        }
    }
}
