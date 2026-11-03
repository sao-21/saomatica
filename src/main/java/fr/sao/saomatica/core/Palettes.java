package fr.sao.saomatica.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Palettes de blocs (all, colourful, greyscale, schematic-friendly, modded). */
public final class Palettes {
    private static Map<String, List<String>> cache;

    private Palettes() {}

    private static synchronized Map<String, List<String>> load() {
        if (cache != null) return cache;
        try (InputStream in = Palettes.class.getResourceAsStream("/saomatica/palettes.json")) {
            Map<String, Object> raw = Json.obj(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            Map<String, List<String>> m = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                String key = e.getKey().substring(0, e.getKey().indexOf(':'));
                List<String> names = new java.util.ArrayList<>();
                for (Object o : Json.arr(e.getValue())) names.add((String) o);
                m.put(key, names);
            }
            cache = m;
            return m;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Palettes illisibles", e);
        }
    }

    public static Set<String> names() { return new LinkedHashSet<>(load().keySet()); }

    public static List<String> get(String name) {
        List<String> l = load().get(name);
        return l != null ? l : load().get("all");
    }
}
