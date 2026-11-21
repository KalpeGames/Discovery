package cc.serverlist.featured.mixin;

import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.ui.FeaturedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every multiplayer join starts here, whichever screen it came from. */
@Mixin(ConnectScreen.class)
abstract class ConnectScreenMixin {
    @Inject(method = "startConnecting", at = @At("HEAD"))
    private static void serverlist$joinStarting(Screen parent, Minecraft minecraft, ServerAddress address, ServerData data,
                                                 boolean quickPlay, TransferState transfer, CallbackInfo ci) {
        if (transfer != null) {
            return; // a server-initiated transfer; the session continues under the new address
        }
        FeaturedScreen.joinedFromPage = parent instanceof FeaturedScreen;
        if (data != null && data.type() != ServerData.Type.OTHER) {
            return; // Realms and LAN worlds are nobody's business
        }
        String via = parent instanceof FeaturedScreen ? "page"
                : parent instanceof JoinMultiplayerScreen ? (Telemetry.directJoin ? "direct" : "list")
                : "other";
        Telemetry.joinStarting(data != null ? data.ip : address.getHost() + ":" + address.getPort(),
                data != null ? data.name : null, via);
    }
}
