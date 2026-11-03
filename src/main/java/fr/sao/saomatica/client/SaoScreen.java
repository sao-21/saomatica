package fr.sao.saomatica.client;

import fr.sao.saomatica.core.Palettes;
import fr.sao.saomatica.mc.SaomaticaMod;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/** Menu SAOmatica (touche K) : envoie simplement les commandes /saomatica. */
public final class SaoScreen extends Screen {
    private static final int W = 380, H = 236, ROWS = 8;
    private static final String[] PRESETS = {"organic", "architectural", "terrain", "vegetation"};
    private static final String[] DITHER = {"ordered", "random", "off"};
    private static final String[] BOOL_KEYS = {"slabs", "stairs", "walls", "trapdoors", "fences", "fill", "nofull"};
    private static final String[] BOOL_NAMES = {"Dalles", "Escaliers", "Murs", "Trappes", "Murets", "Remplir", "Sans plein"};

    // état conservé entre deux ouvertures
    private static int size = 80;
    private static String palette = "all";
    private static int dither = 0;
    private static final boolean[] BOOLS = {true, true, true, true, true, true, false};
    private static String selected = null;
    private static int scroll = 0;
    private static BlockPos pos1, pos2;
    private static String exportName = "export";

    private List<String> models = new ArrayList<>();
    private int x0, y0;
    private ButtonWidget importBtn;

    public SaoScreen() { super(Text.literal("SAOmatica")); }

    @Override public boolean shouldPause() { return false; }

    private static void cmd(String c) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.networkHandler.sendChatCommand(c);
    }

    private void loadModels() {
        models.clear();
        try (Stream<Path> st = Files.list(SaomaticaMod.modelsDir())) {
            st.map(p -> p.getFileName().toString()).filter(n -> {
                String l = n.toLowerCase();
                return l.endsWith(".obj") || l.endsWith(".glb");
            }).sorted().forEach(models::add);
        } catch (IOException ignored) { }
        if (selected != null && !models.contains(selected)) selected = null;
    }

    @Override
    protected void init() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        loadModels();
        clearChildren();

        // ── colonne gauche : modèles ──
        int lx = x0 + 8, ly = y0 + 34, lw = 150;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, models.size() - ROWS)));
        for (int i = 0; i < ROWS; i++) {
            int idx = scroll + i;
            if (idx >= models.size()) break;
            String name = models.get(idx);
            String label = (name.equals(selected) ? "§a> " : "") + trim(name, 22);
            addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> { selected = name; clearAndInit(); })
                .dimensions(lx, ly + i * 20, lw - 22, 18).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> { scroll--; clearAndInit(); }).dimensions(lx + lw - 20, ly, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> { scroll++; clearAndInit(); }).dimensions(lx + lw - 20, ly + 20, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("↻"), b -> clearAndInit()).dimensions(lx + lw - 20, ly + 40, 20, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("§aConstruire ici"), b -> {
            if (selected != null) { cmd("saomatica import \"" + selected + "\""); close(); }
        }).dimensions(lx, ly + ROWS * 20 + 4, lw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Annuler la dernière"), b -> cmd("saomatica undo"))
            .dimensions(lx, ly + ROWS * 20 + 26, lw, 20).build());

        // ── colonne droite : réglages ──
        int rx = x0 + 170, rw = W - 178, ry = y0 + 34;
        addDrawableChild(new SizeSlider(rx, ry, rw, 18));
        addDrawableChild(ButtonWidget.builder(Text.literal("Palette : " + palette), b -> {
            List<String> n = new ArrayList<>(Palettes.names());
            palette = n.get((Math.max(0, n.indexOf(palette)) + 1) % n.size());
            cmd("saomatica config palette " + palette);
            clearAndInit();
        }).dimensions(rx, ry + 22, rw / 2 - 2, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Dither : " + DITHER[dither]), b -> {
            dither = (dither + 1) % DITHER.length;
            cmd("saomatica config dithering " + DITHER[dither]);
            clearAndInit();
        }).dimensions(rx + rw / 2 + 2, ry + 22, rw / 2 - 2, 18).build());

        for (int i = 0; i < BOOL_KEYS.length; i++) {
            final int k = i;
            int cx = rx + (i % 2) * (rw / 2 + 2);
            int cy = ry + 44 + (i / 2) * 20;
            addDrawableChild(ButtonWidget.builder(Text.literal(BOOL_NAMES[i] + " : " + (BOOLS[i] ? "§aON" : "§cOFF")), b -> {
                BOOLS[k] = !BOOLS[k];
                cmd("saomatica config " + BOOL_KEYS[k] + " " + BOOLS[k]);
                clearAndInit();
            }).dimensions(cx, cy, rw / 2 - 2, 18).build());
        }

        int py = ry + 44 + 4 * 20 + 4;
        for (int i = 0; i < PRESETS.length; i++) {
            final String p = PRESETS[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(trim(p, 9)), b -> cmd("saomatica preset " + p))
                .dimensions(rx + i * (rw / 4), py, rw / 4 - 2, 18).build());
        }

        // ── export ──
        int ey = py + 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Coin 1"), b -> { pos1 = playerPos(); clearAndInit(); })
            .dimensions(rx, ey, rw / 3 - 2, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Coin 2"), b -> { pos2 = playerPos(); clearAndInit(); })
            .dimensions(rx + rw / 3, ey, rw / 3 - 2, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("§bExporter"), b -> export("litematic"))
            .dimensions(rx + 2 * rw / 3, ey, rw / 3, 18).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Exporter .schem"), b -> export("schem"))
            .dimensions(rx, ey + 20, rw, 18).build());
    }

    private static BlockPos playerPos() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null ? mc.player.getBlockPos() : null;
    }

    private void export(String fmt) {
        if (pos1 == null || pos2 == null) return;
        cmd("saomatica export " + pos1.getX() + " " + pos1.getY() + " " + pos1.getZ() + " "
            + pos2.getX() + " " + pos2.getY() + " " + pos2.getZ() + " " + exportName + " " + fmt);
        close();
    }

    private static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (mx < x0 + 164) { scroll -= (int) Math.signum(v); clearAndInit(); return true; }
        return super.mouseScrolled(mx, my, h, v);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        ctx.fill(x0, y0, x0 + W, y0 + H, 0xE0101018);
        ctx.fill(x0, y0, x0 + W, y0 + 1, 0xFF4FC3F7);
        ctx.fill(x0, y0 + H - 1, x0 + W, y0 + H, 0xFF4FC3F7);
        ctx.fill(x0 + 164, y0 + 30, x0 + 165, y0 + H - 8, 0x60FFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§bSAOmatica"), x0 + 8, y0 + 8, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Modèles 3D <-> blocs"), x0 + 8, y0 + 19, 0xFFFFFF);
        if (models.isEmpty()) {
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7Aucun modèle. Dépose des"), x0 + 12, y0 + 40, 0xFFFFFF);
            ctx.drawTextWithShadow(textRenderer, Text.literal("§7.obj/.glb dans /saomatica folder"), x0 + 12, y0 + 52, 0xFFFFFF);
        }
        String c1 = pos1 == null ? "—" : pos1.toShortString();
        String c2 = pos2 == null ? "—" : pos2.toShortString();
        ctx.drawTextWithShadow(textRenderer, Text.literal("§7Taille / réglages  |  Export : " + c1 + " → " + c2), x0 + 170, y0 + 19, 0xFFFFFF);
        super.render(ctx, mx, my, delta);
    }

    private static final class SizeSlider extends SliderWidget {
        SizeSlider(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty(), (size - 3) / 377.0);
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Text.literal("Taille : " + size + " blocs")); }
        @Override protected void applyValue() { size = 3 + (int) Math.round(value * 377); }
        @Override public void onRelease(double mx, double my) {
            super.onRelease(mx, my);
            cmd("saomatica config size " + size);
        }
    }
}
