package cc.serverlist.featured.mixin;

import cc.serverlist.featured.telemetry.Telemetry;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The login packet is the moment a join has worked. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "handleLogin", at = @At("HEAD"))
    private void serverlist$loggedIn(ClientboundLoginPacket packet, CallbackInfo ci) {
        Telemetry.loginSucceeded(SharedConstants.getProtocolVersion());
    }
}
