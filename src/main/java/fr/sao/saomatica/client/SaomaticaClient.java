package fr.sao.saomatica.client;

import fr.sao.saomatica.mc.PreviewNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public final class SaomaticaClient implements ClientModInitializer {
    private static KeyBinding openKey, confirmKey, cancelKey, hereKey, xp, xm, zp, zm, yp, ym;

    private static KeyBinding key(String id, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.saomatica." + id, InputUtil.Type.KEYSYM, code, "key.categories.saomatica"));
    }

    @Override
    public void onInitializeClient() {
        openKey = key("open", GLFW.GLFW_KEY_K);
        confirmKey = key("confirm", GLFW.GLFW_KEY_ENTER);
        cancelKey = key("cancel", GLFW.GLFW_KEY_BACKSPACE);
        hereKey = key("here", GLFW.GLFW_KEY_H);
        xp = key("xp", GLFW.GLFW_KEY_RIGHT);
        xm = key("xm", GLFW.GLFW_KEY_LEFT);
        zp = key("zp", GLFW.GLFW_KEY_DOWN);
        zm = key("zm", GLFW.GLFW_KEY_UP);
        yp = key("yp", GLFW.GLFW_KEY_PAGE_UP);
        ym = key("ym", GLFW.GLFW_KEY_PAGE_DOWN);

        ClientPlayNetworking.registerGlobalReceiver(PreviewNet.Chunk.ID, (payload, ctx) -> Ghost.onChunk(payload));
        ClientPlayNetworking.registerGlobalReceiver(PreviewNet.Ctl.ID, (payload, ctx) -> {
            if (payload.action() == 0) Ghost.clear();
            else Ghost.setOrigin(new BlockPos(payload.x(), payload.y(), payload.z()));
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> Ghost.clear());

        WorldRenderEvents.AFTER_TRANSLUCENT.register(ctx -> Ghost.render(ctx.matrixStack(), ctx.camera().getPos()));

        HudRenderCallback.EVENT.register((g, tick) -> {
            if (!Ghost.active()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            int cx = mc.getWindow().getScaledWidth() / 2;
            g.drawCenteredTextWithShadow(mc.textRenderer, Text.literal("§bAperçu SAOmatica §7" + Ghost.info()), cx, 8, 0xFFFFFF);
            g.drawCenteredTextWithShadow(mc.textRenderer, Text.literal("§aEntrée§7 = poser   §cRetour arrière§7 = annuler   §eH§7 = sur moi   §f←↑↓→ PgUp PgDn§7 = déplacer (Maj = x10)"), cx, 20, 0xFFFFFF);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.player != null && client.currentScreen == null) client.setScreen(new SaoScreen());
            }
            if (client.player == null || client.currentScreen != null || !Ghost.active()) return;
            int step = Screen.hasShiftDown() ? 10 : 1;
            while (confirmKey.wasPressed()) client.player.networkHandler.sendChatCommand("saomatica confirm");
            while (cancelKey.wasPressed()) client.player.networkHandler.sendChatCommand("saomatica cancel");
            while (hereKey.wasPressed()) client.player.networkHandler.sendChatCommand("saomatica here");
            while (xp.wasPressed()) move(client, step, 0, 0);
            while (xm.wasPressed()) move(client, -step, 0, 0);
            while (zp.wasPressed()) move(client, 0, 0, step);
            while (zm.wasPressed()) move(client, 0, 0, -step);
            while (yp.wasPressed()) move(client, 0, step, 0);
            while (ym.wasPressed()) move(client, 0, -step, 0);
        });
    }

    private static void move(MinecraftClient c, int dx, int dy, int dz) {
        c.player.networkHandler.sendChatCommand("saomatica move " + dx + " " + dy + " " + dz);
    }
}
