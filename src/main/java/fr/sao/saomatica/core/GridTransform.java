package fr.sao.saomatica.core;

import java.util.List;

/** Rotation 90° (sens horaire vu du dessus) et miroirs d'une grille, y compris les propriétés des blocs. */
public final class GridTransform {
    private GridTransform() {}

    public static BlockGrid rotate90(BlockGrid g) {
        BlockGrid o = new BlockGrid(g.sz, g.sy, g.sx);
        List<String> pal = g.palette();
        String[] mapped = new String[pal.size()];
        for (int i = 1; i < mapped.length; i++) mapped[i] = rotState(pal.get(i));
        for (int y = 0; y < g.sy; y++) for (int z = 0; z < g.sz; z++) for (int x = 0; x < g.sx; x++) {
            int v = g.data[g.idx(x, y, z)];
            if (v != 0) o.set(g.sz - 1 - z, y, x, mapped[v]);
        }
        return o;
    }

    /** axis = 'x' : inverse l'axe X (gauche/droite) ; 'z' : inverse l'axe Z (avant/arrière). */
    public static BlockGrid mirror(BlockGrid g, char axis) {
        BlockGrid o = new BlockGrid(g.sx, g.sy, g.sz);
        List<String> pal = g.palette();
        String[] mapped = new String[pal.size()];
        for (int i = 1; i < mapped.length; i++) mapped[i] = mirState(pal.get(i), axis);
        for (int y = 0; y < g.sy; y++) for (int z = 0; z < g.sz; z++) for (int x = 0; x < g.sx; x++) {
            int v = g.data[g.idx(x, y, z)];
            if (v == 0) continue;
            o.set(axis == 'x' ? g.sx - 1 - x : x, y, axis == 'z' ? g.sz - 1 - z : z, mapped[v]);
        }
        return o;
    }

    // ── états ──
    private static String rotDir(String d) {
        return switch (d) { case "north" -> "east"; case "east" -> "south"; case "south" -> "west"; case "west" -> "north"; default -> d; };
    }

    private static String mirDir(String d, char axis) {
        if (axis == 'x') return d.equals("east") ? "west" : d.equals("west") ? "east" : d;
        return d.equals("north") ? "south" : d.equals("south") ? "north" : d;
    }

    private static String rotState(String s) {
        return mapProps(s, (k, v) -> {
            if (k.equals("facing")) return new String[]{k, rotDir(v)};
            if (k.equals("axis")) return new String[]{k, v.equals("x") ? "z" : v.equals("z") ? "x" : v};
            if (k.equals("north") || k.equals("east") || k.equals("south") || k.equals("west")) return new String[]{rotDir(k), v};
            return new String[]{k, v};
        });
    }

    private static String mirState(String s, char axis) {
        return mapProps(s, (k, v) -> {
            if (k.equals("facing")) return new String[]{k, mirDir(v, axis)};
            if (k.equals("north") || k.equals("east") || k.equals("south") || k.equals("west")) return new String[]{mirDir(k, axis), v};
            if (k.equals("shape") && (v.endsWith("_left") || v.endsWith("_right")))
                return new String[]{k, v.endsWith("_left") ? v.replace("_left", "_right") : v.replace("_right", "_left")};
            if (k.equals("hinge")) return new String[]{k, v.equals("left") ? "right" : "left"};
            return new String[]{k, v};
        });
    }

    private interface Fn { String[] apply(String k, String v); }

    private static String mapProps(String s, Fn fn) {
        int b = s.indexOf('[');
        if (b < 0) return s;
        String name = s.substring(0, b);
        String body = s.substring(b + 1, s.length() - 1);
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String[] kv = fn.apply(part.substring(0, eq), part.substring(eq + 1));
            if (!first) sb.append(',');
            first = false;
            sb.append(kv[0]).append('=').append(kv[1]);
        }
        return sb.append(']').toString();
    }
}
