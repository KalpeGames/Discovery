package cc.serverlist.featured.compat;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.AbstractTexture;
import java.net.URI;

/**
 * The few calls whose API differs between game drops. Each Minecraft version has its own copy of
 * this class under src/mc&lt;version&gt;/java; gradle picks the one matching minecraft_version.
 * This is the 26.3 one.
 */
public final class Compat {
    private Compat() {
    }

    /** Opens a screen. 26.2 moved this from Minecraft to its Gui. */
    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }

    public static net.minecraft.client.gui.components.toasts.ToastManager toasts(Minecraft minecraft) {
        return minecraft.gui.toastManager();
    }

    /** The toast slot a friend's arrival uses. */
    public static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId FRIEND_TOAST =
            net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.FRIEND_SYSTEM_NOTIFICATION;

    /** Pings a server for the card. 1.21.11 added the event loop argument. */
    public static void pingServer(Minecraft minecraft, net.minecraft.client.multiplayer.ServerStatusPinger pinger,
                                  net.minecraft.client.multiplayer.ServerData data, Runnable onPersist, Runnable onDone)
            throws java.net.UnknownHostException {
        pinger.pingServer(data, onPersist, onDone, net.minecraft.server.network.EventLoopGroupHolder.remote(minecraft.options.useNativeTransport()));
    }

    public static java.util.UUID profileId(com.mojang.authlib.GameProfile p) {
        return p.id();
    }

    public static String profileName(com.mojang.authlib.GameProfile p) {
        return p.name();
    }

    public static com.mojang.authlib.minecraft.SessionService sessionService(Minecraft minecraft) {
        return minecraft.services().sessionService();
    }

    public static boolean shiftDown(Minecraft minecraft) {
        return InputConstants.isKeyDown(InputConstants.KEY_LSHIFT)
                || InputConstants.isKeyDown(InputConstants.KEY_RSHIFT);
    }

    /** Vanilla's link confirmation; returns to {@code back} either way. */
    public static void confirmLink(Minecraft minecraft, Screen back, String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            return;
        }
        setScreen(minecraft, new ConfirmLinkScreen(ok -> {
            if (ok) {
                Blaze3D.openUri(uri);
            }
            setScreen(minecraft, back);
        }, uri, true));
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
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(smooth ? FilterMode.LINEAR : FilterMode.NEAREST);
            textureView = device.createTextureView(texture);
            device.createCommandEncoder().writeToTexture(texture, image);
        }
    }
}
