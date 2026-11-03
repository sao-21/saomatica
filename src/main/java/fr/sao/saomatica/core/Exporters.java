package fr.sao.saomatica.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Écrivains de fichiers : .litematic (Litematica) et .schem (Sponge v2 : WorldEdit/FAWE). */
public final class Exporters {
    private Exporters() {}

    private static Map<String, Object> vec(int x, int y, int z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x); m.put("y", y); m.put("z", z);
        return m;
    }

    private static Map<String, Object> blockStateEntry(String state) {
        int b = state.indexOf('[');
        Map<String, Object> e = new LinkedHashMap<>();
        if (b < 0) { e.put("Name", state); return e; }
        e.put("Name", state.substring(0, b));
        Map<String, Object> props = new LinkedHashMap<>();
        String body = state.substring(b + 1, state.length() - 1);
        if (!body.isEmpty()) {
            for (String part : body.split(",")) {
                int eq = part.indexOf('=');
                props.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        if (!props.isEmpty()) e.put("Properties", props);
        return e;
    }

    /** Remplit un LongArray Litematica : entrées de `bits` bits packées en continu (peuvent chevaucher deux longs). */
    private static long[] packBits(int[] data, int bits) {
        long total = (long) data.length * bits;
        long[] arr = new long[(int) ((total + 63) / 64)];
        for (int i = 0; i < data.length; i++) {
            long start = (long) i * bits;
            int word = (int) (start >> 6);
            int shift = (int) (start & 63);
            long v = data[i] & ((1L << bits) - 1);
            arr[word] |= v << shift;
            if (shift + bits > 64) arr[word + 1] |= v >>> (64 - shift);
        }
        return arr;
    }

    public static byte[] litematic(BlockGrid g, int dataVersion, String author, String name) throws IOException {
        List<String> pal = g.palette();
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, pal.size() - 1)));
        List<Object> palList = new ArrayList<>();
        for (String s : pal) palList.add(blockStateEntry(s));
        palList.set(0, blockStateEntry("minecraft:air"));

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("BlockStates", packBits(g.data, bits));
        region.put("PendingBlockTicks", new Nbt.NList(Nbt.COMPOUND, List.of()));
        region.put("Position", vec(0, 0, 0));
        region.put("BlockStatePalette", new Nbt.NList(Nbt.COMPOUND, palList));
        region.put("Size", vec(g.sx, g.sy, g.sz));
        region.put("PendingFluidTicks", new Nbt.NList(Nbt.COMPOUND, List.of()));
        region.put("TileEntities", new Nbt.NList(Nbt.COMPOUND, List.of()));
        region.put("Entities", new Nbt.NList(Nbt.COMPOUND, List.of()));

        long now = System.currentTimeMillis();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Author", author);
        meta.put("Description", "SAOmatica");
        meta.put("Size", vec(g.sx, g.sy, g.sz));
        meta.put("Name", name);
        meta.put("RegionCount", 1);
        meta.put("EnclosingSize", vec(g.sx, g.sy, g.sz));
        meta.put("TimeCreated", now);
        meta.put("TimeModified", now);
        meta.put("TotalBlocks", g.solidBlocks);
        meta.put("TotalVolume", g.sx * g.sy * g.sz);

        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put(name.isEmpty() ? "Unnamed" : name, region);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Metadata", meta);
        root.put("Regions", regions);
        root.put("MinecraftDataVersion", dataVersion);
        root.put("Version", 6);
        root.put("SubVersion", 1);
        return Nbt.writeGzip("", root);
    }

    public static byte[] schem(BlockGrid g, int dataVersion) throws IOException {
        Map<String, Object> palette = new LinkedHashMap<>();
        List<String> pal = g.palette();
        for (int i = 0; i < pal.size(); i++) palette.put(pal.get(i), i);

        ByteArrayOutputStream bd = new ByteArrayOutputStream();
        for (int v : g.data) {
            int x = v;
            while ((x & -128) != 0) { bd.write(x & 127 | 128); x >>>= 7; }
            bd.write(x);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("Version", 2);
        s.put("DataVersion", dataVersion);
        s.put("Width", (short) g.sx);
        s.put("Height", (short) g.sy);
        s.put("Length", (short) g.sz);
        s.put("Offset", new int[] {0, 0, 0});
        s.put("PaletteMax", pal.size());
        s.put("Palette", palette);
        s.put("BlockData", bd.toByteArray());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", s);
        return Nbt.writeGzip("Schematic", s);
    }
}
