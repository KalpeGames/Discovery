package cc.serverlist.featured.compat;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.Util;

/**
 * The few calls whose API differs between game drops. Each Minecraft version has its own copy of
 * this class under src/mc&lt;version&gt;/java; gradle picks the one matching minecraft_version.
 * This is the 1.21.8 one (GLFW window handle).
 */
public final class Compat {
    private Compat() {
    }

    /** Opens a screen. 26.2 moved this from Minecraft to its Gui. */
    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static net.minecraft.client.gui.components.toasts.ToastManager toasts(Minecraft minecraft) {
        return minecraft.getToastManager();
    }

    /** The toast slot a friend's arrival uses; FRIEND_SYSTEM_NOTIFICATION only exists from 26.2. */
    public static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId FRIEND_TOAST =
            net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION;

    /** Pings a server for the card. 1.21.11 added the event loop argument. */
    public static void pingServer(Minecraft minecraft, net.minecraft.client.multiplayer.ServerStatusPinger pinger,
                                  net.minecraft.client.multiplayer.ServerData data, Runnable onPersist, Runnable onDone)
            throws java.net.UnknownHostException {
        pinger.pingServer(data, onPersist, onDone);
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile p) {
        return p.getId();
    }

    public static String profileName(com.mojang.authlib.GameProfile p) {
        return p.getName();
    }

    public static com.mojang.authlib.minecraft.MinecraftSessionService sessionService(Minecraft minecraft) {
        return minecraft.getMinecraftSessionService();
    }

    public static boolean shiftDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(minecraft.getWindow().getWindow(), InputConstants.KEY_RSHIFT);
    }

    /** Vanilla's link confirmation; returns to {@code back} either way. */
    public static void confirmLink(Minecraft minecraft, Screen back, String url) {
        setScreen(minecraft, new ConfirmLinkScreen(ok -> {
            if (ok) {
                Util.getPlatform().openUri(url);
            }
            setScreen(minecraft, back);
        }, url, true));
    }

    /**
     * A texture uploaded once from an image, which is not kept. Vanilla's {@code DynamicTexture}
     * holds on to its pixels for later edits and always samples nearest; neither suits a photo.
     */
    public static AbstractTexture uploadTexture(String label, NativeImage image, boolean smooth) {
        return new Upload(label, image, smooth);
    }

    private static final class Upload extends AbstractTexture {
        Upload(String label, NativeImage image, boolean smooth) {
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture(() -> label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    TextureFormat.RGBA8, image.getWidth(), image.getHeight(), 1, 1);
            // No samplers before 1.21.11: the filter and edge mode are the texture's own.
            texture.setTextureFilter(smooth ? FilterMode.LINEAR : FilterMode.NEAREST, false);
            texture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            textureView = device.createTextureView(texture);
            device.createCommandEncoder().writeToTexture(texture, image);
        }
    }
}
