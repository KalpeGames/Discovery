package cc.serverlist.featured.mixin;

import cc.serverlist.featured.telemetry.Telemetry;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A kick, a timeout or a dropped connection during play (or configuration). */
@Mixin(ClientCommonPacketListenerImpl.class)
abstract class ClientCommonPacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void serverlist$disconnected(DisconnectionDetails details, CallbackInfo ci) {
        Telemetry.disconnected(details.reason());
    }
}
