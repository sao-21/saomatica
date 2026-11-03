package fr.sao.saomatica.mc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import fr.sao.saomatica.core.BlockGrid;
import fr.sao.saomatica.core.Converter;
import fr.sao.saomatica.core.Exporters;
import fr.sao.saomatica.core.Options;
import fr.sao.saomatica.core.Palettes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** /saomatica ... */
public final class SaoCommands {
    /** Version de données de Minecraft 1.21.1 (utilisée dans les fichiers exportés). */
    private static final int DATA_VERSION = 3955;
    private static final UUID NIL = new UUID(0, 0);

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SAOmatica-Worker");
        t.setDaemon(true);
        return t;
    });

    private static final Map<UUID, Options> OPTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, BuildScheduler.Undo> UNDO = new ConcurrentHashMap<>();

    private static final SuggestionProvider<ServerCommandSource> MODELS = (ctx, b) ->
        CommandSource.suggestMatching(listModels().stream().map(n -> n.contains(" ") ? "\"" + n + "\"" : n), b);
    private static final SuggestionProvider<ServerCommandSource> PRESETS = (ctx, b) ->
        CommandSource.suggestMatching(List.of(Options.PRESET_NAMES), b);
    private static final SuggestionProvider<ServerCommandSource> KEYS = (ctx, b) ->
        CommandSource.suggestMatching(new Options().describe().keySet(), b);

    private SaoCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> d) {
        d.register(CommandManager.literal("saomatica")
            .requires(s -> s.hasPermissionLevel(2))
            .executes(c -> help(c))
            .then(CommandManager.literal("help").executes(c -> help(c)))
            .then(CommandManager.literal("models").executes(c -> models(c)))
            .then(CommandManager.literal("folder").executes(c -> folder(c)))
            .then(CommandManager.literal("config")
                .executes(c -> showConfig(c))
                .then(CommandManager.argument("key", StringArgumentType.word()).suggests(KEYS)
                    .then(CommandManager.argument("value", StringArgumentType.word()).executes(c -> setConfig(c)))))
            .then(CommandManager.literal("preset")
                .then(CommandManager.argument("name", StringArgumentType.word()).suggests(PRESETS).executes(c -> preset(c))))
            .then(CommandManager.literal("reset").executes(c -> reset(c)))
            .then(CommandManager.literal("import")
                .then(CommandManager.argument("file", StringArgumentType.string()).suggests(MODELS)
                    .executes(c -> doImport(c, null))
                    .then(CommandManager.argument("corner", BlockPosArgumentType.blockPos())
                        .executes(c -> doImport(c, BlockPosArgumentType.getBlockPos(c, "corner"))))))
            .then(CommandManager.literal("export")
                .then(CommandManager.argument("from", BlockPosArgumentType.blockPos())
                    .then(CommandManager.argument("to", BlockPosArgumentType.blockPos())
                        .then(CommandManager.argument("name", StringArgumentType.word())
                            .executes(c -> doExport(c, "litematic"))
                            .then(CommandManager.literal("litematic").executes(c -> doExport(c, "litematic")))
                            .then(CommandManager.literal("schem").executes(c -> doExport(c, "schem")))))))
            .then(CommandManager.literal("undo").executes(c -> undo(c))));
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private static UUID who(ServerCommandSource s) { return s.getEntity() != null ? s.getEntity().getUuid() : NIL; }

    private static Options optionsOf(ServerCommandSource s) { return OPTIONS.computeIfAbsent(who(s), k -> new Options()); }

    private static void info(ServerCommandSource s, String msg) { s.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GRAY), false); }

    private static void ok(ServerCommandSource s, String msg) { s.sendFeedback(() -> Text.literal(msg).formatted(Formatting.GREEN), false); }

    private static List<String> listModels() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> st = Files.list(SaomaticaMod.modelsDir())) {
            st.map(p -> p.getFileName().toString()).filter(n -> {
                String l = n.toLowerCase();
                return l.endsWith(".obj") || l.endsWith(".glb");
            }).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // dossier absent : liste vide
        }
        return out;
    }

    private static int help(CommandContext<ServerCommandSource> c) {
        ServerCommandSource s = c.getSource();
        ok(s, "SAOmatica — modèle 3D <-> constructions Minecraft");
        info(s, "/saomatica models : liste les modèles (.obj/.glb) du dossier");
        info(s, "/saomatica import <fichier> [coin] : construit le modèle (centré sur toi par défaut)");
        info(s, "/saomatica export <de> <à> <nom> [litematic|schem] : exporte une zone du monde");
        info(s, "/saomatica preset <organic|architectural|terrain|vegetation>");
        info(s, "/saomatica config [clé valeur] : taille, palette, dalles, escaliers, murs...");
        info(s, "/saomatica undo : annule la dernière construction");
        info(s, "/saomatica folder : chemin des dossiers modèles / exports");
        return 1;
    }

    private static int models(CommandContext<ServerCommandSource> c) {
        List<String> m = listModels();
        if (m.isEmpty()) info(c.getSource(), "Aucun modèle. Dépose des .obj/.glb dans : " + SaomaticaMod.modelsDir());
        else info(c.getSource(), m.size() + " modèle(s) : " + String.join(", ", m));
        return m.size();
    }

    private static int folder(CommandContext<ServerCommandSource> c) {
        info(c.getSource(), "Modèles : " + SaomaticaMod.modelsDir());
        info(c.getSource(), "Exports : " + SaomaticaMod.exportsDir());
        return 1;
    }

    private static int showConfig(CommandContext<ServerCommandSource> c) {
        for (Map.Entry<String, String> e : optionsOf(c.getSource()).describe().entrySet()) info(c.getSource(), e.getKey() + " = " + e.getValue());
        info(c.getSource(), "palettes : " + Palettes.names());
        return 1;
    }

    private static int setConfig(CommandContext<ServerCommandSource> c) {
        String key = StringArgumentType.getString(c, "key").toLowerCase();
        String err = optionsOf(c.getSource()).setByKey(key, StringArgumentType.getString(c, "value"));
        if (err != null) { c.getSource().sendError(Text.literal(err)); return 0; }
        ok(c.getSource(), key + " mis à jour");
        return 1;
    }

    private static int preset(CommandContext<ServerCommandSource> c) {
        String name = StringArgumentType.getString(c, "name").toLowerCase();
        if (!optionsOf(c.getSource()).applyPreset(name)) {
            c.getSource().sendError(Text.literal("Preset inconnu : " + name));
            return 0;
        }
        ok(c.getSource(), "Preset « " + name + " » appliqué");
        return 1;
    }

    private static int reset(CommandContext<ServerCommandSource> c) {
        OPTIONS.put(who(c.getSource()), new Options());
        ok(c.getSource(), "Réglages remis par défaut");
        return 1;
    }

    // ── import ──────────────────────────────────────────────────────────────
    private static int doImport(CommandContext<ServerCommandSource> c, BlockPos corner) {
        ServerCommandSource src = c.getSource();
        String name = StringArgumentType.getString(c, "file");
        Path dir = SaomaticaMod.modelsDir().toAbsolutePath().normalize();
        Path file = dir.resolve(name).normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            src.sendError(Text.literal("Modèle introuvable dans " + dir + " : " + name));
            return 0;
        }
        Options opt = optionsOf(src).copy();
        MinecraftServer server = src.getServer();
        ServerWorld world = src.getWorld();
        Vec3d at = src.getPosition();
        UUID who = who(src);
        info(src, "Conversion de " + name + " (taille " + opt.size + ", palette " + opt.palette + ")… peut prendre un moment.");

        CompletableFuture.supplyAsync(() -> {
            try {
                return Converter.convert(file, opt, BlockStates::exists, null);
            } catch (IOException | RuntimeException e) {
                throw new RuntimeException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
            }
        }, WORKER).whenComplete((grid, err) -> server.execute(() -> {
            if (err != null) {
                Throwable t = err.getCause() != null ? err.getCause() : err;
                SaomaticaMod.LOG.error("Échec de la conversion", err);
                src.sendError(Text.literal("Échec : " + t.getMessage()));
                return;
            }
            BlockPos origin = corner != null ? corner
                : new BlockPos((int) Math.floor(at.x) - grid.sx / 2, (int) Math.floor(at.y), (int) Math.floor(at.z) - grid.sz / 2);
            info(src, "Conversion OK : " + grid.sx + "x" + grid.sy + "x" + grid.sz + ", " + grid.solidBlocks + " blocs. Placement en cours…");
            BuildScheduler.Undo undo = BuildScheduler.newUndo(world);
            BuildScheduler.add(BuildScheduler.place(world, grid, origin, undo, () -> {
                UNDO.put(who, undo);
                ok(src, "Terminé : " + undo.size() + " blocs posés en " + origin.toShortString() + ". /saomatica undo pour annuler.");
            }));
        }));
        return 1;
    }

    // ── export ──────────────────────────────────────────────────────────────
    private static int doExport(CommandContext<ServerCommandSource> c, String format) {
        ServerCommandSource src = c.getSource();
        BlockPos a = BlockPosArgumentType.getBlockPos(c, "from");
        BlockPos b = BlockPosArgumentType.getBlockPos(c, "to");
        String name = StringArgumentType.getString(c, "name").replaceAll("[^A-Za-z0-9_.-]", "_");
        long vol = WorldCapture.volume(a, b);
        if (vol > WorldCapture.MAX_VOLUME) {
            src.sendError(Text.literal("Zone trop grande : " + vol + " blocs (max " + WorldCapture.MAX_VOLUME + ")."));
            return 0;
        }
        BlockGrid grid = WorldCapture.capture(src.getWorld(), a, b);
        if (grid.solidBlocks == 0) {
            src.sendError(Text.literal("La zone ne contient que de l'air."));
            return 0;
        }
        Path out = SaomaticaMod.exportsDir().resolve(name + "." + format);
        info(src, "Écriture de " + out.getFileName() + " (" + grid.solidBlocks + " blocs)…");
        CompletableFuture.runAsync(() -> {
            try {
                byte[] data = format.equals("schem") ? Exporters.schem(grid, DATA_VERSION) : Exporters.litematic(grid, DATA_VERSION, "SAOmatica", name);
                Files.createDirectories(out.getParent());
                Files.write(out, data);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, WORKER).whenComplete((v, err) -> src.getServer().execute(() -> {
            if (err != null) src.sendError(Text.literal("Échec de l'export : " + err.getMessage()));
            else ok(src, "Exporté : " + out);
        }));
        return 1;
    }

    // ── undo ────────────────────────────────────────────────────────────────
    private static int undo(CommandContext<ServerCommandSource> c) {
        ServerCommandSource src = c.getSource();
        BuildScheduler.Undo u = UNDO.remove(who(src));
        if (u == null || u.size() == 0) {
            src.sendError(Text.literal("Rien à annuler."));
            return 0;
        }
        info(src, "Annulation de " + u.size() + " blocs…");
        BuildScheduler.add(BuildScheduler.restore(u, () -> ok(src, "Construction annulée.")));
        return 1;
    }
}
