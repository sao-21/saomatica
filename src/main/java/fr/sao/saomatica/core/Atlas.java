package fr.sao.saomatica.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Atlas de couleurs de blocs (vanilla.atlas de SAOmatica : couleur + écart-type par face). */
public final class Atlas {
    public static final int UP = 0, DOWN = 1, NORTH = 2, EAST = 3, SOUTH = 4, WEST = 5;

    public static final class Block {
        public String name;
        public double[] colour;       // r,g,b,a [0,1]
        public double[][] faceColour = new double[6][];
        public double[] faceStd = new double[6];
    }

    private static Atlas instance;
    public final Map<String, Block> blocks = new LinkedHashMap<>();

    public static synchronized Atlas get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static double[] rgba(Object o) {
        Map<String, Object> c = Json.obj(o);
        return new double[] {Json.num(c.get("r")), Json.num(c.get("g")), Json.num(c.get("b")), Json.num(c.get("a"))};
    }

    private static Atlas load() {
        try (InputStream in = new GZIPInputStream(Atlas.class.getResourceAsStream("/saomatica/vanilla_atlas.json.gz"))) {
            Map<String, Object> root = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            Map<String, Object> tex = Json.obj(root.get("textures"));
            Atlas atlas = new Atlas();
            String[] faceKeys = {"up", "down", "north", "east", "south", "west"};
            for (Object bo : (List<?>) root.get("blocks")) {
                Map<String, Object> b = Json.obj(bo);
                Block blk = new Block();
                blk.name = (String) b.get("name");
                blk.colour = rgba(b.get("colour"));
                Map<String, Object> faces = Json.obj(b.get("faces"));
                for (int i = 0; i < 6; i++) {
                    Map<String, Object> t = Json.obj(tex.get((String) faces.get(faceKeys[i])));
                    blk.faceColour[i] = rgba(t.get("colour"));
                    blk.faceStd[i] = t.containsKey("std") ? Json.num(t.get("std")) : 0.0;
                }
                atlas.blocks.put(blk.name, blk);
            }
            return atlas;
        } catch (IOException e) {
            throw new IllegalStateException("Atlas illisible", e);
        }
    }
}
