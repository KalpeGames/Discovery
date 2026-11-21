package cc.serverlist.featured.mixin;

import cc.serverlist.featured.compat.Compat;
import cc.serverlist.featured.telemetry.Telemetry;
import cc.serverlist.featured.ui.DiscoveryButton;
import cc.serverlist.featured.ui.FeaturedScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Featured button in the top-right corner of the Multiplayer screen.
 *
 * <p>This is where a player already is when they are looking for somewhere to play, and it also
 * covers the case where a custom-menu mod has hidden the title-screen button. The corner is
 * outside vanilla's header/footer layout, so nothing else moves.
 */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void serverlist$addFeaturedButton(CallbackInfo ci) {
        Screen self = this;
        this.addRenderableWidget(new DiscoveryButton(this.width - 8 - 70, 6, 70, 20, Component.literal("Discovery"),
                        b -> Compat.setScreen(this.minecraft, new FeaturedScreen(self))));
    }

    /** Direct Connect goes through the same join as the list; this is how the two are told apart. */
    @Inject(method = "directJoinCallback", at = @At("HEAD"))
    private void serverlist$directJoin(boolean confirmed, CallbackInfo ci) {
        if (confirmed) {
            Telemetry.directJoin = true;
        }
    }
}
