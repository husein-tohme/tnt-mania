package net.husein.tntmania.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.husein.tntmania.FillSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * The settings of TNT Mania: fill mode. A switch, three sliders for how big the area is that one placed TNT
 * turns into, and the two keys. Everything is saved by itself when the screen closes.
 */
public class TntScreen extends Screen {
    private static final int GREY = 0xFFA0A0A0, GOLD = 0xFFFFD050, WHITE = 0xFFFFFFFF, YELLOW = 0xFFFFE070;
    private static final int ROW = 24, ESCAPE = 256;
    /** From this many TNT on, the screen warns about lighting them all at once. */
    private static final int A_LOT = 2000;

    private final Screen parent;
    /** The key button that was clicked and is waiting for the player to press a key, or null. */
    private KeyMapping choosing;

    public TntScreen(Screen parent) {
        super(Component.literal("TNT Mania"));
        this.parent = parent;
    }

    private int top() {
        return Math.max(4, height / 2 - 112);
    }

    /** A slider that picks a whole number of blocks from 1 to max. */
    private static final class Size extends AbstractSliderButton {
        private final String what;
        private final int max;
        private final IntConsumer set;

        Size(int x, int y, int width, String what, int now, int max, IntConsumer set) {
            super(x, y, width, 20, Component.empty(), max <= 1 ? 0 : (now - 1) / (double) (max - 1));
            this.what = what;
            this.max = max;
            this.set = set;
            updateMessage();
        }

        private int amount() {
            return Math.clamp(Math.round(1 + value * (max - 1)), 1, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(what + ": " + amount() + (amount() == 1 ? " block" : " blocks")));
        }

        @Override
        protected void applyValue() {
            set.accept(amount());     // told to the world and written to the file when the screen closes
        }
    }

    @Override
    protected void init() {
        int cx = width / 2, y = top() + 20;
        FillSettings f = TntClient.fill;

        Component state = Component.literal(f.on() ? "ON" : "OFF").withStyle(f.on() ? ChatFormatting.GREEN : ChatFormatting.RED);
        Button master = Button.builder(Component.literal("Fill mode: ").append(state), b -> {
            TntClient.fill = TntClient.fill.withOn(!TntClient.fill.on());
            rebuildWidgets();
        }).bounds(cx - 152, y, 304, 20).build();
        master.setTooltip(Tooltip.create(Component.literal("On: when you place one TNT, a whole area of that TNT is placed. Off: one TNT, as always.")));
        addRenderableWidget(master);
        y += ROW;

        Size wide = new Size(cx - 152, y, 304, "Wide (side to side)", f.wide(), FillSettings.MAX_WIDE,
                n -> TntClient.fill = new FillSettings(TntClient.fill.on(), n, TntClient.fill.length(), TntClient.fill.high()));
        wide.setTooltip(Tooltip.create(Component.literal("How many TNT side by side. The block you place is in the middle.")));
        addRenderableWidget(wide);
        y += ROW;

        Size length = new Size(cx - 152, y, 304, "Long (forward)", f.length(), FillSettings.MAX_LENGTH,
                n -> TntClient.fill = new FillSettings(TntClient.fill.on(), TntClient.fill.wide(), n, TntClient.fill.high()));
        length.setTooltip(Tooltip.create(Component.literal("How many TNT forward, starting with the block you place. Forward is the way you are looking.")));
        addRenderableWidget(length);
        y += ROW;

        Size high = new Size(cx - 152, y, 304, "High (upwards)", f.high(), FillSettings.MAX_HIGH,
                n -> TntClient.fill = new FillSettings(TntClient.fill.on(), TntClient.fill.wide(), TntClient.fill.length(), n));
        high.setTooltip(Tooltip.create(Component.literal("How many layers of TNT on top of each other.")));
        addRenderableWidget(high);
        y += ROW + 52;

        addRenderableWidget(keyButton("Open this screen", TntClient.OPEN, cx - 152, y));
        addRenderableWidget(keyButton("Fill mode on/off", TntClient.TOGGLE, cx + 2, y));
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y + ROW + 6, 200, 20).build());
    }

    private Button keyButton(String what, KeyMapping key, int x, int y) {
        Component text = key == null ? Component.literal(what + ": -")
                : choosing == key ? Component.literal("Press a key (Esc = none)").withStyle(ChatFormatting.YELLOW)
                : Component.literal(what + ": ").append(key.isUnbound() ? Component.literal("no key").withStyle(ChatFormatting.GRAY)
                        : key.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.AQUA));
        Button button = Button.builder(text, b -> {
            choosing = choosing == key ? null : key;
            rebuildWidgets();
        }).bounds(x, y, 150, 20).build();
        button.setTooltip(Tooltip.create(Component.literal("Click, then press the key you want. It also shows up in the game's own Controls.")));
        button.active = key != null;
        return button;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (choosing != null) {
            choosing.setKey(event.key() == ESCAPE ? InputConstants.UNKNOWN : InputConstants.getKey(event));
            KeyMapping.resetMapping();
            minecraft.options.save();
            choosing = null;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int cx = width / 2, y = top() + 20 + ROW * 4 + 4;
        FillSettings f = TntClient.fill;
        graphics.centeredText(font, title, cx, top() + 6, GOLD);
        if (minecraft.level != null && !TntClient.worldHasMod()) {
            graphics.centeredText(font, Component.literal("This world does not have TNT Mania, so nothing here works in it."), cx, y, YELLOW);
            graphics.centeredText(font, Component.literal("It works in the worlds you open yourself."), cx, y + 11, GREY);
            return;
        }
        String click = f.on() ? "One click places " + f.blocks() + " TNT (" + f.wide() + " x " + f.length() + " x " + f.high() + ")."
                : "Fill mode is off: one click places one TNT.";
        graphics.centeredText(font, Component.literal(click), cx, y, WHITE);
        graphics.centeredText(font, Component.literal("It fills empty space only, forward from the block you place."), cx, y + 11, GREY);
        graphics.centeredText(font, Component.literal("In survival it uses the TNT from your inventory."), cx, y + 22, GREY);
        if (f.on() && f.blocks() >= A_LOT) {
            graphics.centeredText(font, Component.literal("That is a lot. Lighting it all at once can freeze the game for a while."), cx, y + 33, YELLOW);
        }
    }

    @Override
    public void removed() {
        TntClient.use(TntClient.fill);      // the switch and the sliders only change the settings here; this tells the world
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
