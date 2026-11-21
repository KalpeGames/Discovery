package cc.serverlist.featured.mixin;

import cc.serverlist.featured.telemetry.Telemetry;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Failures that never had a connection to be told about — an unknown host, a refused port, a
 * timeout while connecting — only ever surface as this screen.
 */
@Mixin(DisconnectedScreen.class)
abstract class DisconnectedScreenMixin {
    @Inject(method = "<init>(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/DisconnectionDetails;Lnet/minecraft/network/chat/Component;)V",
            at = @At("RETURN"))
    private void serverlist$shown(Screen parent, Component title, DisconnectionDetails details, Component button, CallbackInfo ci) {
        Telemetry.disconnected(details.reason());
    }
}
