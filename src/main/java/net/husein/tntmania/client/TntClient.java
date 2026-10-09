package net.husein.tntmania.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.husein.tntmania.FillSettings;
import net.husein.tntmania.TntMania;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's side of TNT Mania: a "TNT Mania" button in the pause menu (Esc) that opens the settings, and two
 * keys the player can choose: one opens the settings, one switches fill mode on and off.
 */
public final class TntClient implements ClientModInitializer {
    /** No key at the start, so nothing clashes with other mods. The player picks them in the settings screen. */
    private static final int NO_KEY = -1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static KeyMapping OPEN;
    public static KeyMapping TOGGLE;
    /**
     * This player's fill settings. The sizes are remembered between games. Fill mode itself always starts
     * switched off, so nobody fills half a world by accident the next day.
     */
    public static FillSettings fill = FillSettings.START;
    private static Path file;

    /** What is written to config/tntmania.json. */
    private static final class Saved {
        int wide = FillSettings.START.wide(), length = FillSettings.START.length(), high = FillSettings.START.high();
    }

    private static void load() {
        Saved saved = null;
        try {
            if (Files.isRegularFile(file)) saved = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Saved.class);
        } catch (Exception e) {
            // a damaged file: the starting sizes are used and written fresh the next time something changes
        }
        if (saved == null) saved = new Saved();
        fill = new FillSettings(false, saved.wide, saved.length, saved.high).clamped();
    }

    private static void save() {
        try {
            Saved saved = new Saved();
            saved.wide = fill.wide();
            saved.length = fill.length();
            saved.high = fill.high();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(saved), StandardCharsets.UTF_8);
        } catch (Exception e) {
            TntMania.LOG.warn("TNT Mania: could not save the settings: {}", e.toString());
        }
    }

    /** True when the world the player is in has the mod, so the TNTs and fill mode work there. */
    public static boolean worldHasMod() {
        try {
            return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(FillSettings.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Tells the world this player's settings. A world without the mod is told nothing. */
    private static void send() {
        try {
            if (worldHasMod()) ClientPlayNetworking.send(fill);
        } catch (Throwable t) {
            TntMania.LOG.warn("TNT Mania: could not send the fill settings: {}", t.toString());
        }
    }

    /** Takes new settings into use: tells the world and writes the sizes down. */
    public static void use(FillSettings settings) {
        fill = settings.clamped();
        send();
        save();
    }

    /** "Fill mode: ON (5 x 5 x 1 = 25 TNT per click)" */
    public static Component summary() {
        Component state = Component.literal(fill.on() ? "ON" : "OFF").withStyle(fill.on() ? ChatFormatting.GREEN : ChatFormatting.RED);
        Component text = Component.literal("Fill mode: ").append(state);
        if (!fill.on()) return text;
        return text.copy().append(Component.literal(" (" + fill.wide() + " x " + fill.length() + " x " + fill.high() + " = " + fill.blocks() + " TNT per click)"));
    }

    @Override
    public void onInitializeClient() {
        file = FabricLoader.getInstance().getConfigDir().resolve("tntmania.json");
        load();
        OPEN = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tntmania.settings", NO_KEY, KeyMapping.Category.MISC));
        TOGGLE = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.tntmania.fill", NO_KEY, KeyMapping.Category.MISC));

        // a world the player joins does not know their settings yet
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(TntClient::send));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                while (OPEN.consumeClick()) {
                    if (client.gui.screen() == null) client.gui.setScreen(new TntScreen(null));
                }
                while (TOGGLE.consumeClick()) {
                    if (client.player == null) continue;
                    if (!worldHasMod()) {
                        client.player.sendOverlayMessage(Component.literal("This world does not have TNT Mania").withStyle(ChatFormatting.YELLOW));
                        continue;
                    }
                    use(fill.withOn(!fill.on()));
                    client.player.sendOverlayMessage(summary());
                }
            } catch (Throwable t) {
                TntMania.LOG.warn("TNT Mania key problem: {}", t.toString());
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof PauseScreen pause) || !pause.showsPauseMenu()) return;
            try {
                Screens.getWidgets(screen).add(Button.builder(Component.literal("TNT Mania"),
                        b -> client.gui.setScreen(new TntScreen(screen))).bounds(4, 124, 100, 20).build());
            } catch (Throwable t) {
                // a missing button must never take the pause menu down with it
                TntMania.LOG.warn("Could not add the TNT Mania button to the pause menu: {}", t.toString());
            }
        });
    }
}
