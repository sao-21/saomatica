package fr.sao.saomatica.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class SaomaticaClient implements ClientModInitializer {
    private static KeyBinding openKey;

    @Override
    public void onInitializeClient() {
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.saomatica.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.saomatica"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                if (client.player != null && client.currentScreen == null) client.setScreen(new SaoScreen());
            }
        });
    }
}
