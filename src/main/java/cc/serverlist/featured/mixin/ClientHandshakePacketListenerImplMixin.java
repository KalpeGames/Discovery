package cc.serverlist.featured.mixin;

import cc.serverlist.featured.telemetry.Telemetry;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A join refused during login: whitelist, ban, wrong version, the player cancelling. */
@Mixin(ClientHandshakePacketListenerImpl.class)
abstract class ClientHandshakePacketListenerImplMixin {
    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void serverlist$loginFailed(DisconnectionDetails details, CallbackInfo ci) {
        Telemetry.disconnected(details.reason());
    }
}
