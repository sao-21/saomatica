package fr.sao.saomatica;

import fr.sao.saomatica.core.*;
import java.nio.file.*;
import java.util.*;

/** Test manuel hors Minecraft : java -cp ... fr.sao.saomatica.CoreSmoke model.obj [taille] [preset] */
public class CoreSmoke {
    public static void main(String[] a) throws Exception {
        Options o = new Options();
        o.size = a.length > 1 ? Integer.parseInt(a[1]) : 40;
        if (a.length > 2) o.applyPreset(a[2]);
        long t0 = System.currentTimeMillis();
        BlockGrid g = Converter.convert(Paths.get(a[0]), o, n -> true, null);
        long t1 = System.currentTimeMillis();
        System.out.println("grille " + g.sx + "x" + g.sy + "x" + g.sz + " blocs=" + g.solidBlocks + " palette=" + g.palette().size() + " en " + (t1 - t0) + " ms");
        Map<String, Integer> kinds = new TreeMap<>();
        for (int x = 0; x < g.sx; x++) for (int y = 0; y < g.sy; y++) for (int z = 0; z < g.sz; z++) {
            String s = g.get(x, y, z);
            if (s.equals("minecraft:air")) continue;
            String k = s.contains("_slab") ? "dalle" : s.contains("_stairs") ? "escalier" : s.contains("_wall") ? "mur" : s.contains("trapdoor") ? "trappe" : s.contains("_fence") ? "cloture" : "plein";
            kinds.merge(k, 1, Integer::sum);
        }
        System.out.println(kinds);
        Files.write(Paths.get("/tmp/test.litematic"), Exporters.litematic(g, 3955, "Sao", "skull"));
        Files.write(Paths.get("/tmp/test.schem"), Exporters.schem(g, 3955));
        System.out.println("ecrit /tmp/test.litematic /tmp/test.schem");
    }
}
