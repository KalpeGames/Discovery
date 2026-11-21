package cc.serverlist.featured.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The "Discovery" button on the title screen, the pause menu and the Multiplayer screen: a
 * vanilla button in every way but its colour, blue so it reads as the way in among the grey
 * ones. Drawn in vanilla's bevel — dark outline, light top edge, a two-pixel shadow along the
 * bottom — with vanilla's white outline under the mouse, and the label vanilla draws.
 */
public final class DiscoveryButton extends Button {
    private static final int OUTLINE = 0x0B1E3F;
    private static final int BODY = 0x3478D6;
    private static final int TOP = 0x74A6EC;
    private static final int BOTTOM = 0x2659A8;
    private static final int BODY_HOVER = 0x4A8FEA;
    private static final int TOP_HOVER = 0x8DBBF5;
    private static final int BOTTOM_HOVER = 0x2F68BF;

    public DiscoveryButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    //? if >=1.21.11 {
    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    //?} else {
    /*@Override
    protected void renderWidget(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
    *///?}
        int x = getX();
        int y = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = active && isHoveredOrFocused();
        // The title screen fades its buttons in; follow it.
        int a = Math.round(Math.clamp(alpha, 0f, 1f) * 255) << 24;
        g.fill(x, y, x + w, y + h, a | (hover ? 0xFFFFFF : OUTLINE));
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, a | (hover ? BODY_HOVER : BODY));
        g.fill(x + 1, y + 1, x + w - 1, y + 2, a | (hover ? TOP_HOVER : TOP));
        g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, a | (hover ? BOTTOM_HOVER : BOTTOM));
        //? if >=1.21.11 {
        extractDefaultLabel(g.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
        //?} else {
        /*renderString(g, net.minecraft.client.Minecraft.getInstance().font, a | 0xFFFFFF);
        *///?}
    }
}
