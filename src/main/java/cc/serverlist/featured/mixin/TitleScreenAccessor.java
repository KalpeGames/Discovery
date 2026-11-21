package cc.serverlist.featured.mixin;

import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the Featured page switch off the title screen's one-time fade-in on the way back. */
@Mixin(TitleScreen.class)
public interface TitleScreenAccessor {
    @Accessor("fading")
    void serverlist$setFading(boolean fading);
}
