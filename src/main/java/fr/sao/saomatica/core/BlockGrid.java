package fr.sao.saomatica.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Grille de blocs : palette de block-states (index 0 = air) + indices ordonnés y, z, x. */
public final class BlockGrid {
    public final int sx, sy, sz;
    public final int[] data;
    private final List<String> palette = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();
    public int solidBlocks = 0;

    public BlockGrid(int sx, int sy, int sz) {
        this.sx = sx; this.sy = sy; this.sz = sz;
        this.data = new int[sx * sy * sz];
        palette.add("minecraft:air");
        index.put("minecraft:air", 0);
    }

    public int idx(int x, int y, int z) { return (y * sz + z) * sx + x; }

    public void set(int x, int y, int z, String state) {
        Integer i = index.get(state);
        if (i == null) { i = palette.size(); palette.add(state); index.put(state, i); }
        int at = idx(x, y, z);
        if (data[at] == 0 && i != 0) solidBlocks++;
        data[at] = i;
    }

    public String get(int x, int y, int z) { return palette.get(data[idx(x, y, z)]); }
    public List<String> palette() { return palette; }
}
