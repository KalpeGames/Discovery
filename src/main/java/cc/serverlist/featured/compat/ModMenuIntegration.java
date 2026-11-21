package cc.serverlist.featured.compat;

import cc.serverlist.featured.ui.SettingsScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu's config button opens Discovery's settings. Only Mod Menu loads this class (it is the
 * {@code modmenu} entrypoint), so the mod runs the same without Mod Menu installed.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
