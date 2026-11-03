package fr.sao.saomatica.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Moteur de placement de formes SAO (v28) : dalles, escaliers (8 orientations + coins), murs, trappes, murets.
 * Port fidèle de litematic_exporter.ts — chaque candidat légal reçoit un score = dot(normale du voxel, normale idéale).
 * Les noms de blocs sont renvoyés sous forme « minecraft:xxx[prop=val,...] ».
 */
public final class ShapeEngine {
    private static Map<String, Map<String, String>> maps;

    private final Map<String, String> slab, stair, wall, trap, fence;
    private final VoxelMesh vm;

    // réglages
    private final boolean enableSlabs, enableStairs, enableWalls, enableTrapdoors, enableFences, noFullBlocks;
    private final double slabFlat, stairVMax, stairHMin, wallVMax;
    private static final double STAIR_V_MIN = 0.20;
    private static final int MIN_SOLID_NEIGHBOURS = 1;
    private static final int NORMAL_RADIUS = 2;

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Map<String, String>> loadMaps() {
        if (maps != null) return maps;
        try (InputStream in = ShapeEngine.class.getResourceAsStream("/saomatica/shapes.json")) {
            Map<String, Object> raw = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            Map<String, Map<String, String>> m = new HashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                Map<String, String> sub = new HashMap<>();
                for (Map.Entry<String, Object> s : Json.obj(e.getValue()).entrySet()) sub.put(s.getKey(), (String) s.getValue());
                m.put(e.getKey(), sub);
            }
            maps = m;
            return m;
        } catch (IOException e) {
            throw new IllegalStateException("shapes.json illisible", e);
        }
    }

    /** @param exists teste si un bloc (ex. minecraft:oak_slab) est disponible ; les variantes absentes sont ignorées. */
    public ShapeEngine(VoxelMesh vm, Options o, Predicate<String> exists) {
        Map<String, Map<String, String>> all = loadMaps();
        this.vm = vm;
        this.slab = filter(all.get("slab"), exists);
        this.stair = filter(all.get("stair"), exists);
        this.wall = filter(all.get("wall"), exists);
        this.trap = filter(all.get("trapdoor"), exists);
        this.fence = filter(all.get("fence"), exists);
        enableSlabs = o.slabs; enableStairs = o.stairs; enableWalls = o.walls;
        enableTrapdoors = o.trapdoors; enableFences = o.fences; noFullBlocks = o.noFullBlocks;
        slabFlat = (100.0 - o.slabT) / 100.0;
        stairVMax = o.stairT / 100.0;
        stairHMin = o.horizMin / 100.0;
        wallVMax = o.wallT / 100.0;
    }

    private static Map<String, String> filter(Map<String, String> src, Predicate<String> exists) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : src.entrySet()) if (exists.test(e.getValue())) out.put(e.getKey(), e.getValue());
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private boolean has(int x, int y, int z) { return vm.has(x, y, z); }

    private static final class Faces {
        boolean up, down, north, south, east, west;
        int exposed, solidCount;
    }

    private Faces faces(int x, int y, int z) {
        Faces f = new Faces();
        f.up = has(x, y + 1, z);
        f.down = has(x, y - 1, z);
        f.north = has(x, y, z - 1);
        f.south = has(x, y, z + 1);
        f.east = has(x + 1, y, z);
        f.west = has(x - 1, y, z);
        f.solidCount = (f.up ? 1 : 0) + (f.down ? 1 : 0) + (f.north ? 1 : 0) + (f.south ? 1 : 0) + (f.east ? 1 : 0) + (f.west ? 1 : 0);
        f.exposed = 6 - f.solidCount;
        return f;
    }

    /** Normale sortante : celle du voxel, sinon gradient lissé de l'espace vide. */
    private double[] normalOf(VoxelMesh.Voxel v) {
        if (v.normalSamples > 0) {
            double m = Math.sqrt(v.normal[0] * v.normal[0] + v.normal[1] * v.normal[1] + v.normal[2] * v.normal[2]);
            if (m >= 1e-6) return new double[] {v.normal[0] / m, v.normal[1] / m, v.normal[2] / m};
        }
        double nx = 0, ny = 0, nz = 0;
        int R = NORMAL_RADIUS;
        for (int dx = -R; dx <= R; dx++) for (int dy = -R; dy <= R; dy++) for (int dz = -R; dz <= R; dz++) {
            if (dx == 0 && dy == 0 && dz == 0) continue;
            if (!has(v.x + dx, v.y + dy, v.z + dz)) {
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double w = 1 / (dist * dist);
                nx += dx * w; ny += dy * w; nz += dz * w;
            }
        }
        double mag = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (mag < 1e-6) return new double[] {0, 1, 0};
        return new double[] {nx / mag, ny / mag, nz / mag};
    }

    // perpendiculaires gauche/droite + avant par orientation : {ldx,ldz,rdx,rdz,fdx,fdz}
    private static int[] perp(String facing) {
        switch (facing) {
            case "east": return new int[] {0, -1, 0, 1, 1, 0};
            case "west": return new int[] {0, 1, 0, -1, -1, 0};
            case "south": return new int[] {1, 0, -1, 0, 0, 1};
            case "north": return new int[] {-1, 0, 1, 0, 0, -1};
            default: return null;
        }
    }

    private String stairShape(String facing, int x, int y, int z) {
        int[] p = perp(facing);
        if (p == null) return "straight";
        boolean leftOpen = !has(x + p[0], y, z + p[1]);
        boolean rightOpen = !has(x + p[2], y, z + p[3]);
        boolean frontDiagL = has(x + p[4] + p[0], y, z + p[5] + p[1]);
        boolean frontDiagR = has(x + p[4] + p[2], y, z + p[5] + p[3]);
        if (leftOpen && !frontDiagL) return "outer_left";
        if (rightOpen && !frontDiagR) return "outer_right";
        if (!leftOpen && frontDiagL) return "inner_left";
        if (!rightOpen && frontDiagR) return "inner_right";
        return "straight";
    }

    // ── API ─────────────────────────────────────────────────────────────────
    public String shape(String base, VoxelMesh.Voxel v) {
        String r = inner(base, v);
        if (!noFullBlocks) return r;
        if (!r.equals(base)) return r;
        return forceShaped(base, v);
    }

    private static String wallState(String wall, Faces f) {
        String e = f.east ? "low" : "none", w = f.west ? "low" : "none";
        String n = f.north ? "low" : "none", s = f.south ? "low" : "none";
        String up = !f.up ? "true" : "false";
        return wall + "[east=" + e + ",north=" + n + ",south=" + s + ",up=" + up + ",waterlogged=false,west=" + w + "]";
    }

    private String forceShaped(String base, VoxelMesh.Voxel v) {
        String sl = slab.get(base), wa = wall.get(base), tr = trap.get(base), fe = fence.get(base);
        Faces f = faces(v.x, v.y, v.z);
        if (sl != null) {
            if (!f.up) return sl + "[type=bottom,waterlogged=false]";
            if (!f.down) return sl + "[type=top,waterlogged=false]";
            return sl + "[type=bottom,waterlogged=false]";
        }
        if (wa != null) return wallState(wa, f);
        if (tr != null) return tr + "[facing=north,half=bottom,open=false,powered=false,waterlogged=false]";
        if (fe != null) return fe + "[north=false,south=false,east=false,west=false,waterlogged=false]";
        return base;
    }

    private static final class Cand {
        final double score;
        final Supplier<String> make;
        Cand(double s, Supplier<String> m) { score = s; make = m; }
    }

    private String inner(String base, VoxelMesh.Voxel v) {
        final String sl = slab.get(base), st = stair.get(base), wa = wall.get(base), tr = trap.get(base), fe = fence.get(base);
        if (sl == null && st == null && wa == null && tr == null && fe == null) return base;

        final int x = v.x, y = v.y, z = v.z;
        final Faces f = faces(x, y, z);
        if (f.exposed == 0) return base;
        if (f.solidCount < MIN_SOLID_NEIGHBOURS) return base;

        // CLÔTURE : poteau 1x1 vertical
        if (enableFences && fe != null && !f.north && !f.south && !f.east && !f.west && (f.up || f.down)) {
            return fe + "[north=false,south=false,east=false,west=false,waterlogged=false]";
        }

        // TRAPPE : feuille fine stricte
        if (enableTrapdoors && tr != null) {
            if (!f.up && !f.down && f.north && f.south && f.east && f.west)
                return tr + "[facing=north,half=bottom,open=false,powered=false,waterlogged=false]";
            if (!f.east && !f.west && f.up && f.down && f.north && f.south)
                return tr + "[facing=east,half=bottom,open=true,powered=false,waterlogged=false]";
            if (!f.north && !f.south && f.up && f.down && f.east && f.west)
                return tr + "[facing=north,half=bottom,open=true,powered=false,waterlogged=false]";
        }

        double[] n = normalOf(v);
        double nx = n[0], ny = n[1], nz = n[2];
        double absY = Math.abs(ny);
        double hMag = Math.sqrt(nx * nx + nz * nz);
        final double H = 0.70710678;
        List<Cand> cands = new ArrayList<>();

        // DALLES
        final double SLAB_BOOST = 1.03;
        if (enableSlabs && sl != null && absY >= slabFlat) {
            if (ny > 0 && !f.up) cands.add(new Cand(ny * SLAB_BOOST, () -> sl + "[type=bottom,waterlogged=false]"));
            if (ny < 0 && !f.down && f.up) cands.add(new Cand(-ny * SLAB_BOOST, () -> sl + "[type=top,waterlogged=false]"));
        }

        // ESCALIERS : 4 orientations x 2 moitiés, filtrés par topologie
        if (enableStairs && st != null && absY >= STAIR_V_MIN && absY <= stairVMax && hMag >= stairHMin) {
            Object[][] dirs = {
                {"east", nx, !f.east, f.west},
                {"west", -nx, !f.west, f.east},
                {"south", nz, !f.south, f.north},
                {"north", -nz, !f.north, f.south},
            };
            for (Object[] d : dirs) {
                final String facing = (String) d[0];
                double hdot = (Double) d[1];
                boolean open = (Boolean) d[2], back = (Boolean) d[3];
                if (!open || hdot <= 0) continue;
                if (!f.up && (f.down || back)) {
                    double sc = hdot * H + ny * H;
                    if (sc > 0) cands.add(new Cand(sc, () ->
                        st + "[facing=" + facing + ",half=bottom,shape=" + stairShape(facing, x, y, z) + ",waterlogged=false]"));
                }
                if (!f.down && (f.up || back)) {
                    double sc = hdot * H - ny * H;
                    if (sc > 0) cands.add(new Cand(sc, () ->
                        st + "[facing=" + facing + ",half=top,shape=" + stairShape(facing, x, y, z) + ",waterlogged=false]"));
                }
            }
        }

        // MUR : surface quasi verticale, ancrée verticalement
        if (enableWalls && wa != null && absY < wallVMax && (f.up || f.down)) {
            cands.add(new Cand(hMag * 0.90, () -> wallState(wa, f)));
        }

        if (!cands.isEmpty()) {
            Cand best = cands.get(0);
            for (int i = 1; i < cands.size(); i++) if (cands.get(i).score > best.score) best = cands.get(i);
            return best.make.get();
        }

        // Repli topologique pour les arêtes fines
        List<String> openH = new ArrayList<>();
        if (!f.north) openH.add("north");
        if (!f.south) openH.add("south");
        if (!f.east) openH.add("east");
        if (!f.west) openH.add("west");

        if (enableStairs && st != null && !f.up && openH.size() == 1 && f.down) {
            String facing = openH.get(0);
            return st + "[facing=" + facing + ",half=bottom,shape=" + stairShape(facing, x, y, z) + ",waterlogged=false]";
        }
        if (enableSlabs && sl != null && !f.up) return sl + "[type=bottom,waterlogged=false]";
        if (enableSlabs && sl != null && !f.down && f.up) return sl + "[type=top,waterlogged=false]";
        return base;
    }
}
