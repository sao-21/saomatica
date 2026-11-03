package fr.sao.saomatica.client;

import fr.sao.saomatica.core.Palettes;
import fr.sao.saomatica.mc.SaomaticaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;

/** Menu SAOmatica (touche K), à onglets. Envoie les commandes /saomatica. */
public final class SaoScreen extends Screen {
    private static final int W = 440, H = 262, ROWS = 9;
    private static final String[] TABS = {"Modèles", "Voxel & couleurs", "Formes", "Export"};
    private static final String[] PRESETS = {"organic", "architectural", "terrain", "vegetation"};
    private static final String[] DITHER = {"ordered", "random", "off"};
    private static final String[] AXES = {"x", "y", "z"};
    private static final String[] BOOL_KEYS = {"slabs", "stairs", "walls", "trapdoors", "fences", "nofull", "fill"};
    private static final String[] BOOL_NAMES = {"Dalles", "Escaliers", "Murs", "Trappes", "Murets", "Sans bloc plein", "Remplir l'intérieur"};
    private static final boolean[] BOOLS = {true, true, true, true, true, false, true};
    // sliders : clé, nom, min, max
    private static final String[] SL_KEY = {"ao", "normal", "slabt", "stairt", "horizmin", "wallt"};
    private static final String[] SL_NAME = {"Poids AO (creux)", "Poids normale", "Seuil dalle", "Seuil escalier", "Horiz. min escalier", "Seuil mur"};
    private static final double[] SL_VAL = {18, 12, 8, 90, 25, 30};

    // état conservé entre deux ouvertures
    private static int tab = 0;
    private static int size = 80;
    private static String palette = "all";
    private static int dither = 0;
    private static int axis = 1;
    private static String selected = null;
    private static int scroll = 0;
    private static BlockPos pos1, pos2;
    private static String exportName = "export";
    private static String importPath = "";
    private static String status = "";

    private final List<String> models = new ArrayList<>();
    private int x0, y0;
    private TextFieldWidget pathField, nameField;

    public SaoScreen() { super(Text.literal("SAOmatica")); }

    @Override public boolean shouldPause() { return false; }

    private static void cmd(String c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.networkHandler.sendChatCommand(c);
    }

    private void loadModels() {
        models.clear();
        try {
            Files.createDirectories(SaomaticaMod.modelsDir());
        } catch (IOException ignored) { }
        try (Stream<Path> st = Files.list(SaomaticaMod.modelsDir())) {
            st.map(p -> p.getFileName().toString()).filter(SaoScreen::isModel).sorted().forEach(models::add);
        } catch (IOException ignored) { }
        if (selected != null && !models.contains(selected)) selected = null;
    }

    private static boolean isModel(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        return l.endsWith(".obj") || l.endsWith(".glb");
    }

    /** Copie un modèle (+ .mtl et textures voisines) dans le dossier des modèles. */
    private static String importFile(Path src) {
        try {
            if (!Files.isRegularFile(src)) return "§cFichier introuvable : " + src;
            if (!isModel(src.getFileName().toString())) return "§cFormat non géré (.obj ou .glb seulement)";
            Files.createDirectories(SaomaticaMod.modelsDir());
            Files.copy(src, SaomaticaMod.modelsDir().resolve(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            Path dir = src.toAbsolutePath().getParent();
            if (dir != null && src.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obj")) {
                try (Stream<Path> st = Files.list(dir)) {
                    for (Path p : (Iterable<Path>) st::iterator) {
                        String l = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        if ((l.endsWith(".mtl") || l.endsWith(".png") || l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".tga"))
                            && Files.isRegularFile(p) && Files.size(p) < 30_000_000L) {
                            Files.copy(p, SaomaticaMod.modelsDir().resolve(p.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
            selected = src.getFileName().toString();
            return "§aImporté : " + selected;
        } catch (IOException | RuntimeException e) {
            return "§cÉchec : " + e.getMessage();
        }
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        String last = "";
        for (Path p : paths) last = importFile(p);
        status = last;
        tab = 0;
        clearAndInit();
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        loadModels();
        clearChildren();
        pathField = null;
        nameField = null;

        // onglets
        int tw = (W - 16) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int t = i;
            String label = (i == tab ? "§b§n" : "") + TABS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { tab = t; clearAndInit(); })
                .dimensions(x0 + 8 + i * tw, y0 + 24, tw - 2, 18).build());
        }
        switch (tab) {
            case 0 -> initModels();
            case 1 -> initVoxel();
            case 2 -> initShapes();
            default -> initExport();
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Fermer"), b -> close())
            .dimensions(x0 + W - 68, y0 + H - 24, 60, 18).build());
    }

    // ── onglet 0 : modèles ──
    private void initModels() {
        int lx = x0 + 8, ly = y0 + 50, lw = 190;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, models.size() - ROWS)));
        for (int i = 0; i < ROWS; i++) {
            int idx = scroll + i;
            if (idx >= models.size()) break;
            String name = models.get(idx);
            addDrawableChild(ButtonWidget.builder(Text.literal((name.equals(selected) ? "§a> " : "") + trim(name, 26)), b -> { selected = name; clearAndInit(); })
                .dimensions(lx, ly + i * 20, lw - 22, 18).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> { scroll--; clearAndInit(); }).dimensions(lx + lw - 20, ly, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> { scroll++; clearAndInit(); }).dimensions(lx + lw - 20, ly + 20, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> { status = "§7Liste rafraîchie"; clearAndInit(); }).dimensions(lx + lw - 20, ly + 40, 20, 18).build());

        int rx = x0 + 212, rw = W - 220;
        addDrawableChild(ButtonWidget.builder(Text.literal("§aConstruire ici (sur moi)"), b -> {
            if (selected != null) { cmd("saomatica import \"" + selected + "\""); close(); }
            else { status = "§cChoisis d'abord un modèle dans la liste"; }
        }).dimensions(rx, y0 + 50, rw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Annuler la dernière construction"), b -> cmd("saomatica undo"))
            .dimensions(rx, y0 + 72, rw, 18).build());

        pathField = new TextFieldWidget(textRenderer, rx, y0 + 124, rw, 18, Text.literal("Chemin"));
        pathField.setMaxLength(1024);
        pathField.setPlaceholder(Text.literal("§7C:\\Users\\moi\\Downloads\\skull.obj"));
        pathField.setText(importPath);
        pathField.setChangedListener(s -> importPath = s);
        addDrawableChild(pathField);
        addDrawableChild(ButtonWidget.builder(Text.literal("§bImporter ce fichier"), b -> {
            String p = importPath.trim();
            if (p.length() > 1 && p.startsWith("\"") && p.endsWith("\"")) p = p.substring(1, p.length() - 1);
            status = p.isEmpty() ? "§cColle d'abord le chemin du fichier" : importFile(Path.of(p));
            clearAndInit();
        }).dimensions(rx, y0 + 144, rw, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Ouvrir le dossier des modèles"), b -> {
            try { Files.createDirectories(SaomaticaMod.modelsDir()); } catch (IOException ignored) { }
            Util.getOperatingSystem().open(SaomaticaMod.modelsDir().toFile());
        }).dimensions(rx, y0 + 168, rw, 18).build());
    }

    // ── onglet 1 : voxel & couleurs ──
    private void initVoxel() {
        int lx = x0 + 8, w = (W - 24) / 2, y = y0 + 52;
        addDrawableChild(new Slide(lx, y, w * 2 + 8, 18, "size", "Taille (plus grande dimension)", 3, 380, size, true, v -> size = (int) Math.round(v)));
        addDrawableChild(ButtonWidget.builder(Text.literal("Axe de taille : " + AXES[axis].toUpperCase()), b -> {
            axis = (axis + 1) % 3; cmd("saomatica config axis " + AXES[axis]); clearAndInit();
        }).dimensions(lx, y + 24, w, 18).build());
        addDrawableChild(toggle(lx + w + 8, y + 24, w, 6));
        addDrawableChild(ButtonWidget.builder(Text.literal("Palette : " + palette), b -> {
            List<String> n = new ArrayList<>(Palettes.names());
            palette = n.get((Math.max(0, n.indexOf(palette)) + 1) % n.size());
            cmd("saomatica config palette " + palette);
            clearAndInit();
        }).dimensions(lx, y + 48, w, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Dithering : " + DITHER[dither]), b -> {
            dither = (dither + 1) % DITHER.length;
            cmd("saomatica config dithering " + DITHER[dither]);
            clearAndInit();
        }).dimensions(lx + w + 8, y + 48, w, 18).build());
        addDrawableChild(slider(0, lx, y + 76, w));
        addDrawableChild(slider(1, lx + w + 8, y + 76, w));
        addDrawableChild(ButtonWidget.builder(Text.literal("Tout remettre par défaut"), b -> {
            cmd("saomatica reset");
            size = 80; palette = "all"; dither = 0; axis = 1;
            boolean[] d = {true, true, true, true, true, false, true};
            System.arraycopy(d, 0, BOOLS, 0, d.length);
            double[] v = {18, 12, 8, 90, 25, 30};
            System.arraycopy(v, 0, SL_VAL, 0, v.length);
            clearAndInit();
        }).dimensions(lx, y + 104, w, 18).build());
    }

    // ── onglet 2 : formes ──
    private void initShapes() {
        int lx = x0 + 8, w = (W - 24) / 2, y = y0 + 52;
        for (int i = 0; i < 6; i++) addDrawableChild(toggle(lx + (i % 2) * (w + 8), y + (i / 2) * 22, w, i));
        int sy = y + 70;
        for (int i = 2; i < 6; i++) addDrawableChild(slider(i, lx + ((i - 2) % 2) * (w + 8), sy + ((i - 2) / 2) * 22, w));
        int py = sy + 50;
        for (int i = 0; i < PRESETS.length; i++) {
            final String p = PRESETS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(p), b -> { cmd("saomatica preset " + p); status = "§aPreset " + p + " appliqué"; })
                .dimensions(lx + i * ((W - 16) / 4), py, (W - 16) / 4 - 2, 18).build());
        }
    }

    // ── onglet 3 : export ──
    private void initExport() {
        int lx = x0 + 8, w = W - 16, y = y0 + 52;
        addDrawableChild(ButtonWidget.builder(Text.literal("Coin 1 = ma position"), b -> { pos1 = playerPos(); clearAndInit(); })
            .dimensions(lx, y, w / 2 - 2, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Coin 2 = ma position"), b -> { pos2 = playerPos(); clearAndInit(); })
            .dimensions(lx + w / 2 + 2, y, w / 2 - 2, 20).build());
        nameField = new TextFieldWidget(textRenderer, lx, y + 56, w, 18, Text.literal("Nom"));
        nameField.setMaxLength(48);
        nameField.setText(exportName);
        nameField.setChangedListener(s -> exportName = s.replaceAll("[^A-Za-z0-9_.-]", "_"));
        addDrawableChild(nameField);
        addDrawableChild(ButtonWidget.builder(Text.literal("§bExporter en .litematic (Litematica)"), b -> export("litematic"))
            .dimensions(lx, y + 80, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("§bExporter en .schem (WorldEdit)"), b -> export("schem"))
            .dimensions(lx, y + 102, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Ouvrir le dossier des exports"), b -> {
            try { Files.createDirectories(SaomaticaMod.exportsDir()); } catch (IOException ignored) { }
            Util.getOperatingSystem().open(SaomaticaMod.exportsDir().toFile());
        }).dimensions(lx, y + 128, w, 18).build());
    }

    private ButtonWidget toggle(int x, int y, int w, int i) {
        return ButtonWidget.builder(Text.literal(BOOL_NAMES[i] + " : " + (BOOLS[i] ? "§aON" : "§cOFF")), b -> {
            BOOLS[i] = !BOOLS[i];
            cmd("saomatica config " + BOOL_KEYS[i] + " " + BOOLS[i]);
            clearAndInit();
        }).dimensions(x, y, w, 18).build();
    }

    private Slide slider(int i, int x, int y, int w) {
        return new Slide(x, y, w, 18, SL_KEY[i], SL_NAME[i], 0, 100, SL_VAL[i], true, v -> SL_VAL[i] = v);
    }

    private static BlockPos playerPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null ? mc.player.getBlockPos() : null;
    }

    private void export(String fmt) {
        if (pos1 == null || pos2 == null) { status = "§cDéfinis d'abord le coin 1 et le coin 2"; clearAndInit(); return; }
        String n = exportName.isBlank() ? "export" : exportName;
        cmd("saomatica export " + pos1.getX() + " " + pos1.getY() + " " + pos1.getZ() + " "
            + pos2.getX() + " " + pos2.getY() + " " + pos2.getZ() + " " + n + " " + fmt);
        close();
    }

    private static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (tab == 0 && mx < x0 + 200) { scroll -= (int) Math.signum(v); clearAndInit(); return true; }
        return super.mouseScrolled(mx, my, h, v);
    }

    /** Dessine le panneau en même temps que le fond (sinon le flou passe par-dessus). */
    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        super.renderBackground(ctx, mx, my, delta);
        ctx.fill(x0, y0, x0 + W, y0 + H, 0xF0101018);
        ctx.fill(x0, y0, x0 + W, y0 + 1, 0xFF4FC3F7);
        ctx.fill(x0, y0 + H - 1, x0 + W, y0 + H, 0xFF4FC3F7);
        ctx.fill(x0, y0, x0 + 1, y0 + H, 0xFF4FC3F7);
        ctx.fill(x0 + W - 1, y0, x0 + W, y0 + H, 0xFF4FC3F7);
        ctx.fill(x0 + 4, y0 + 44, x0 + W - 4, y0 + 45, 0x60FFFFFF);
        if (tab == 0) ctx.fill(x0 + 204, y0 + 48, x0 + 205, y0 + H - 30, 0x40FFFFFF);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§b§lSAOmatica §r§7— modèles 3D <-> blocs  (K)"), x0 + 8, y0 + 8, 0xFFFFFF);
        int rx = x0 + 212;
        switch (tab) {
            case 0 -> {
                if (models.isEmpty()) {
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7Aucun modèle pour l'instant."), x0 + 12, y0 + 56, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7Glisse un .obj / .glb dans cette"), x0 + 12, y0 + 72, 0xFFFFFF);
                    ctx.drawTextWithShadow(textRenderer, Text.literal("§7fenêtre, ou colle son chemin →"), x0 + 12, y0 + 84, 0xFFFFFF);
                }
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Sélection : §f" + (selected == null ? "aucune" : trim(selected, 28))), rx, y0 + 96, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§eImporter un modèle depuis ton PC :"), rx, y0 + 110, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Ou glisse-dépose le fichier ici."), rx, y0 + 190, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7(.mtl et textures copiés aussi)"), rx, y0 + 202, 0xFFFFFF);
            }
            case 1 -> {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Réglages envoyés au jeu à chaque clic."), x0 + 8, y0 + 182, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Poids AO/normale : nuance les couleurs selon creux et orientation."), x0 + 8, y0 + 194, 0xFFFFFF);
            }
            case 2 -> {
                ctx.drawTextWithShadow(textRenderer, Text.literal("§eSeuils de placement des formes :"), x0 + 8, y0 + 122, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§ePresets :"), x0 + 8, y0 + 172, 0xFFFFFF);
            }
            default -> {
                String c1 = pos1 == null ? "non défini" : pos1.toShortString();
                String c2 = pos2 == null ? "non défini" : pos2.toShortString();
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Coin 1 : §f" + c1 + "   §7Coin 2 : §f" + c2), x0 + 8, y0 + 78, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Nom du fichier :"), x0 + 8, y0 + 96, 0xFFFFFF);
                ctx.drawTextWithShadow(textRenderer, Text.literal("§7Fichiers dans .minecraft/config/saomatica/exports"), x0 + 8, y0 + 206, 0xFFFFFF);
            }
        }
        if (!status.isEmpty()) ctx.drawTextWithShadow(textRenderer, Text.literal(status), x0 + 8, y0 + H - 20, 0xFFFFFF);
    }

    private interface Setter { void set(double v); }

    /** Curseur générique ; envoie /saomatica config <clé> <valeur> au relâchement. */
    private static final class Slide extends SliderWidget {
        private final String key, label;
        private final double min, max;
        private final boolean whole;
        private final Setter setter;
        private double cur;

        Slide(int x, int y, int w, int h, String key, String label, double min, double max, double val, boolean whole, Setter setter) {
            super(x, y, w, h, Text.empty(), (val - min) / (max - min));
            this.key = key; this.label = label; this.min = min; this.max = max; this.whole = whole; this.setter = setter;
            this.cur = val;
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Text.literal(label + " : " + (whole ? String.valueOf(Math.round(cur)) : String.format(Locale.ROOT, "%.1f", cur)))); }
        @Override protected void applyValue() {
            cur = min + value * (max - min);
            if (whole) cur = Math.round(cur);
            setter.set(cur);
        }
        @Override public void onRelease(double mx, double my) {
            super.onRelease(mx, my);
            cmd("saomatica config " + key + " " + (whole ? String.valueOf((long) cur) : String.valueOf(cur)));
        }
    }
}
