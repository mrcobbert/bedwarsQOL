package com.bedwarsqol.gui.render;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResourceManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * A texture loaded straight off the mod's classpath (jar), bypassing Minecraft's resource manager.
 * Needed under Weave: unlike Forge, Weave does not register the mod's {@code assets/} domain, so
 * {@code ResourceLocation("bedwarsqol", ...)} lookups through the resource manager fail. We instead
 * register an instance of this with the {@code TextureManager} under that same ResourceLocation, so the
 * existing {@code bindTexture(...)} calls in {@link BedwarsQolFont} and {@link Icons} resolve to it.
 *
 * <p>Decoding/upload uses the exact same {@link TextureUtil} path as vanilla {@code SimpleTexture}, so
 * it is safe wherever vanilla textures load (including macOS).
 */
public class ClasspathTexture extends AbstractTexture {

    private final String path;

    /** @param path absolute classpath resource path, e.g. {@code /assets/bedwarsqol/icons/nav.png} */
    public ClasspathTexture(String path) {
        this.path = path;
    }

    @Override
    public void loadTexture(IResourceManager resourceManager) throws IOException {
        deleteGlTexture();
        try (InputStream in = ClasspathTexture.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing classpath texture: " + path);
            java.awt.image.BufferedImage image = TextureUtil.readBufferedImage(in);
            TextureUtil.uploadTextureImageAllocate(getGlTextureId(), image, false, false);
        }
    }
}
