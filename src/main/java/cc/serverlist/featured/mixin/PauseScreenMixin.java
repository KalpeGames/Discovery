package cc.serverlist.featured.mixin;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.ui.DiscoveryButton;
import cc.serverlist.featured.ui.FeaturedScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Discovery button on the pause menu, under Disconnect, so a player can move to another
 * server without going back to the title screen first. Joining from there works as it does
 * from anywhere: ConnectScreen leaves the current world before it connects.
 *
 * <p>Placed on the first tick after {@code init}, for the same reason as on the title screen:
 * mods that add their own buttons through Fabric's after-init event (Mod Menu's game-menu
 * button) run after every mixin on {@code init}, and the spot has to be checked against the
 * final layout. Under Disconnect first; the top-right corner if something is already there.
 */
@Mixin(value = PauseScreen.class, priority = 2000)
public abstract class PauseScreenMixin extends Screen {
    @Shadow
    private Button disconnectButton;

    @Unique
    private boolean serverlist$placed;

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void serverlist$onInit(CallbackInfo ci) {
        serverlist$placed = false;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void serverlist$addDiscoveryButton(CallbackInfo ci) {
        if (serverlist$placed) {
            return;
        }
        serverlist$placed = true;
        if (Telemetry.config() != null && !Telemetry.config().pauseButton) {
            return; // turned off in Discovery's settings
        }

        Screen self = this;
        Button under = this.disconnectButton;
        int[][] spots = under != null
                ? new int[][]{
                        {under.getX(), under.getY() + under.getHeight() + 4, under.getWidth()},
                        {this.width - 8 - 98, 6, 98}}
                : new int[][]{{this.width - 8 - 98, 6, 98}};
        for (int[] spot : spots) {
            if (serverlist$overlaps(spot[0], spot[1], spot[2], 20) || spot[1] + 20 > this.height) {
                continue;
            }
            this.addRenderableWidget(new DiscoveryButton(spot[0], spot[1], spot[2], 20, Component.literal("Discovery"),
                        b -> Compat.setScreen(this.minecraft, new FeaturedScreen(self))));
            return;
        }
    }

    //? if <1.21.9 {
    /*// 1.21.8 leaves a server from here, not from Minecraft.disconnectFromWorld (MinecraftMixin).
    @ModifyArg(method = "disconnectFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private static Screen serverlist$backToDiscovery(Screen next) {
        return FeaturedScreen.afterDisconnect(next);
    }
    *///?}

    @Unique
    private boolean serverlist$overlaps(int x, int y, int w, int h) {
        for (GuiEventListener child : this.children()) {
            if (!(child instanceof AbstractWidget widget) || !widget.visible) {
                continue;
            }
            if (widget.getX() < x + w && widget.getX() + widget.getWidth() > x
                    && widget.getY() < y + h && widget.getY() + widget.getHeight() > y) {
                return true;
            }
        }
        return false;
    }
}
