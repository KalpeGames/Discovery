package cc.serverlist.featured.mixin;

import cc.serverlist.featured.friends.Friends;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.ui.FeaturedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaving a world — the player's own choice, or the game tearing it down after a kick. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    /** Friends: who's in the tab list, presence, and the opt-in toast. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void serverlist$tick(CallbackInfo ci) {
        Friends.clientTick((Minecraft) (Object) this);
    }

    //? if >=1.21.11 {
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void serverlist$leftWorld(Screen screen, boolean keepResourcePacks, boolean transferring, CallbackInfo ci) {
        if (!transferring) {
            Telemetry.leftWorld();
        }
    }
    //?} else {
    /*// Before 1.21.11 there is no transferring flag: every disconnect is a leave.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    private void serverlist$leftWorld(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        Telemetry.leftWorld();
    }
    *///?}

    /**
     * Disconnecting from a server (the pause menu's button) lands on the multiplayer list. When
     * the server was joined from Discovery, land back on Discovery instead, with the title screen
     * behind it as the multiplayer list would have had. Kicks don't come through here: their
     * screen's Back already returns to the page the join started from.
     */
    //? if >=26.2 {
    @ModifyArg(method = "disconnectFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private Screen serverlist$backToDiscovery(Screen next) {
        return FeaturedScreen.afterDisconnect(next);
    }
    //?} else if >=1.21.9 {
    /*@ModifyArg(method = "disconnectFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"), index = 0)
    private Screen serverlist$backToDiscovery(Screen next) {
        return FeaturedScreen.afterDisconnect(next);
    }
    *///?}
    // 1.21.8 does this in PauseScreen instead; see PauseScreenMixin.
}
