package cc.serverlist.featured.mixin;

import cc.serverlist.featured.FeaturedClient;
import com.mojang.realmsclient.gui.screens.RealmsNotificationsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Realms draws its notification icons (news, invites, the trial gem) over the right end of the
 * Realms button, at a fixed spot: where that end is when the button is full width. When the
 * Discovery button takes the right half of Realms' row, that spot is on Discovery. Move the icons
 * to the right end of Realms' own half, which is where they belong.
 */
@Mixin(RealmsNotificationsScreen.class)
abstract class RealmsNotificationsScreenMixin {
    //? if >=26.1 {
    @ModifyVariable(method = "extractIcons", at = @At("STORE"), name = "buttonRight")
    //?} else {
    /*// Obfuscated 1.21.x keeps no local names: buttonRight is the method's 4th int local there.
    @ModifyVariable(method = "drawIcons", at = @At("STORE"), ordinal = 3)
    *///?}
    private int serverlist$besideDiscovery(int buttonRight) {
        return FeaturedClient.discoveryBesideRealms ? buttonRight - FeaturedClient.DISCOVERY_HALF : buttonRight;
    }
}
