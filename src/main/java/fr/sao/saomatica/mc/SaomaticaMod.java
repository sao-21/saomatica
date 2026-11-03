package fr.sao.saomatica.mc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SaomaticaMod implements ModInitializer {
    public static final String MOD_ID = "saomatica";
    public static final Logger LOG = LoggerFactory.getLogger("SAOmatica");

    public static Path modelsDir() { return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("models"); }
    public static Path exportsDir() { return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("exports"); }

    @Override
    public void onInitialize() {
        try {
            Files.createDirectories(modelsDir());
            Files.createDirectories(exportsDir());
        } catch (IOException e) {
            LOG.error("Impossible de créer les dossiers SAOmatica", e);
        }
        BuildScheduler.init();
        PreviewNet.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> SaoCommands.register(dispatcher));
        LOG.info("SAOmatica prêt. Modèles : {}", modelsDir());
    }
}
