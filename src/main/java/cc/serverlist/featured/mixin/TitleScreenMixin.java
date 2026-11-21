package cc.serverlist.featured.mixin;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.FeaturedClient;
import cc.serverlist.featured.api.ServerListApi;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.ui.Artwork;
import cc.serverlist.featured.ui.DiscoveryButton;
import cc.serverlist.featured.ui.FeaturedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts a Discovery button on the title screen without moving anything.
 *
 * <p>A full-width button is shrunk to a half and Discovery takes the other half, the same trick
 * Mod Menu uses for its own button. Vanilla's Realms button is paired with first, then Mod
 * Menu's "Mods", then Multiplayer; whichever is tried, the spot is checked against every
 * other widget, because a button that lands under somebody else's never gets the click.
 *
 * <p>The placing happens on the first tick after {@code init}, not in it: mods that lay the
 * title screen out through Fabric's after-init screen event (Mod Menu is one) run after every
 * mixin on {@code init} whatever its priority, and a button placed before them ends up shoved
 * under theirs. A tick later the layout is final. Failing every anchor, the title screen is
 * left alone — the Multiplayer-screen button remains.
 */
@Mixin(value = TitleScreen.class, priority = 2000)
public abstract class TitleScreenMixin extends Screen {
    @Unique
    private boolean serverlist$placed;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void serverlist$onInit(CallbackInfo ci) {
        // Warm the list while the player is still looking at the splash text.
        // Then its first screen of art, so the page opens with its banners already on the GPU.
        ServerListApi.featured(false).thenAccept(page -> Minecraft.getInstance().execute(() -> Artwork.warm(page)));
        serverlist$placed = false;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void serverlist$addFeaturedButton(CallbackInfo ci) {
        if (serverlist$placed) {
            return;
        }
        serverlist$placed = true;

        // Where the player asked for it (Discovery settings): beside one button, in the corner,
        // or nowhere. A named spot that isn't there or has no room falls back to the corner.
        String where = Telemetry.config() != null ? Telemetry.config().titleButton : "auto";
        String[] anchors = switch (where) {
            case "multiplayer" -> new String[]{"menu.multiplayer"};
            case "realms" -> new String[]{"menu.online"};
            case "mods" -> new String[]{"modmenu.title"};
            case "corner", "hidden" -> new String[0];
            default -> new String[]{"menu.online", "modmenu.title", "menu.multiplayer"};
        };
        boolean placed = "hidden".equals(where);
        FeaturedClient.discoveryBesideRealms = false;
        for (String key : anchors) {
            Button anchor = serverlist$find(key);
            if (anchor == null || anchor.getWidth() != 200) {
                continue;
            }
            int x = anchor.getX() + 102;
            int y = anchor.getY();
            if (serverlist$overlaps(anchor, x, y, 98, 20)) {
                continue;
            }
            anchor.setWidth(98);
            serverlist$addButton(x, y);
            FeaturedClient.discoveryBesideRealms = "menu.online".equals(key);
            placed = true;
            break;
        }
        // Automatic keeps its old manners and leaves a custom menu alone; a spot the player
        // chose themselves gets the corner rather than nothing.
        if (!placed && !"auto".equals(where)) {
            int[][] corners = {{this.width - 8 - 98, 6}, {8, 6}};
            for (int[] c : corners) {
                if (!serverlist$overlaps(null, c[0], c[1], 98, 20)) {
                    serverlist$addButton(c[0], c[1]);
                    break;
                }
            }
        }

        // Development aid: `-Dserverlist.autoOpen=true` (or the SERVERLIST_AUTO_OPEN env var)
        // opens the page straight away, so a headless test run can screenshot it without input.
        if (!FeaturedClient.autoOpened && (Boolean.getBoolean("serverlist.autoOpen") || System.getenv("SERVERLIST_AUTO_OPEN") != null)) {
            FeaturedClient.autoOpened = true;
            // Testing aid: `-Dserverlist.mixinAudit=true`, or a file config/serverlist-mixin-audit,
            // loads every mixin target now, so a hook that doesn't fit this Minecraft version fails
            // here rather than mid-game.
            if (Boolean.getBoolean("serverlist.mixinAudit") || java.nio.file.Files.exists(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("serverlist-mixin-audit"))) {
                org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                FeaturedClient.LOGGER.info("Mixin audit finished");
            }
            Compat.setScreen(this.minecraft, new FeaturedScreen(this));
        }
    }

    @Unique
    private void serverlist$addButton(int x, int y) {
        Screen self = this;
        this.addRenderableWidget(new DiscoveryButton(x, y, 98, 20, Component.literal("Discovery"),
                        b -> Compat.setScreen(this.minecraft, new FeaturedScreen(self))));
    }

    /** Whether any widget other than {@code except} sits in the rectangle. */
    private boolean serverlist$overlaps(GuiEventListener except, int x, int y, int w, int h) {
        for (GuiEventListener child : this.children()) {
            if (child == except || !(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            if (widget.getX() < x + w && widget.getX() + widget.getWidth() > x
                    && widget.getY() < y + h && widget.getY() + widget.getHeight() > y) {
                return true;
            }
        }
        return false;
    }

    private Button serverlist$find(String translationKey) {
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button
                    && button.getMessage().getContents() instanceof TranslatableContents contents
                    && translationKey.equals(contents.getKey())) {
                return button;
            }
        }
        return null;
    }
}
