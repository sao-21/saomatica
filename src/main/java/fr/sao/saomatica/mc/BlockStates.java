package fr.sao.saomatica.mc;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/** Conversion « minecraft:oak_stairs[facing=east,...] » <-> BlockState. */
public final class BlockStates {
    private static final Map<String, Boolean> EXISTS = new ConcurrentHashMap<>();

    private BlockStates() {}

    /** Le bloc (sans propriétés) est-il enregistré dans cette instance du jeu ? Thread-safe, mis en cache. */
    public static boolean exists(String blockName) {
        return EXISTS.computeIfAbsent(blockName, n -> {
            Identifier id = Identifier.tryParse(n);
            return id != null && Registries.BLOCK.containsId(id);
        });
    }

    /** @return null si le bloc n'existe pas. À appeler sur le thread serveur. */
    public static BlockState parse(String state) {
        int b = state.indexOf('[');
        String name = b < 0 ? state : state.substring(0, b);
        Identifier id = Identifier.tryParse(name);
        if (id == null || !Registries.BLOCK.containsId(id)) return null;
        Block block = Registries.BLOCK.get(id);
        BlockState s = block.getDefaultState();
        if (b >= 0) {
            String body = state.substring(b + 1, state.length() - 1);
            if (!body.isEmpty()) {
                for (String part : body.split(",")) {
                    int eq = part.indexOf('=');
                    if (eq < 0) continue;
                    Property<?> p = block.getStateManager().getProperty(part.substring(0, eq));
                    if (p != null) s = withProperty(s, p, part.substring(eq + 1));
                }
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState s, Property<T> p, String value) {
        Optional<T> v = p.parse(value);
        return v.isPresent() ? s.with(p, v.get()) : s;
    }

    /** BlockState -> chaîne ; l'air (y compris cave_air / void_air) devient minecraft:air. */
    public static String format(BlockState state) {
        if (state.isAir()) return "minecraft:air";
        StringBuilder sb = new StringBuilder(Registries.BLOCK.getId(state.getBlock()).toString());
        if (!state.getEntries().isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<Property<?>, Comparable<?>> e : state.getEntries().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(e.getKey().getName()).append('=').append(valueName(e.getKey(), e.getValue()));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property<?> p, Comparable<?> v) {
        return ((Property) p).name((Comparable) v);
    }
}
