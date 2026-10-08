package com.yunfang.blockcapture.texture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.yunfang.blockcapture.BlockCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.GL11;

/**
 * Reads raw (unlit) pixels for whatever texture a captured quad samples, so a
 * capture's colour comes from the artist's assets rather than from lighting.
 *
 * <p>One reader covers every case, because a quad's UVs are always normalised
 * across the texture its render type binds:
 *
 * <ul>
 *   <li>block models and most block-entity renderers bind a <em>stitched
 *       atlas</em> — {@code textures/atlas/blocks.png} for blocks,
 *       {@code chest.png} / {@code beds.png} / {@code signs.png} /
 *       {@code shulker_boxes.png} / {@code banner_patterns.png} /
 *       {@code decorated_pot.png} for the block entities. Those exist only on
 *       the GPU, so they are read back from it.</li>
 *   <li>a few renderers bind a standalone PNG (skull skins under
 *       {@code textures/entity/…}), which is read from the resource pack.</li>
 * </ul>
 *
 * <p>Each texture is fetched at most once per capture and cached, and a GPU
 * read-back serves every sprite in an atlas — so a chest capture costs one
 * small download, not one per quad.
 */
public final class TextureSampler {
	/** A decoded texture: pixels packed ARGB, row-major, origin top-left. */
	private record Pixels(int width, int height, int[] pixels) {
	}

	private final Map<ResourceLocation, Pixels> cache = new HashMap<>();
	private final Set<ResourceLocation> unreadable = new HashSet<>();

	/** Packed ARGB at normalised {@code (u, v)}; alpha 0 when unreadable. */
	public int sample(ResourceLocation texture, float u, float v) {
		if (texture == null) {
			return 0;
		}
		Pixels pixels = pixels(texture);
		if (pixels == null || pixels.width <= 0 || pixels.height <= 0) {
			return 0;
		}
		int x = clamp((int) (u * pixels.width), 0, pixels.width - 1);
		int y = clamp((int) (v * pixels.height), 0, pixels.height - 1);
		return pixels.pixels[y * pixels.width + x];
	}

	/** How many distinct textures this capture had to read. */
	public int textureCount() {
		return this.cache.size();
	}

	private Pixels pixels(ResourceLocation texture) {
		if (this.cache.containsKey(texture)) {
			return this.cache.get(texture);
		}
		if (this.unreadable.contains(texture)) {
			return null;
		}
		Pixels loaded = readFromGpu(texture);
		if (loaded == null) {
			loaded = readFromResource(texture);
		}
		if (loaded == null) {
			BlockCapture.LOGGER.warn("Could not read texture {}; quads using it will be colourless", texture);
			this.unreadable.add(texture);
		} else {
			this.cache.put(texture, loaded);
		}
		return loaded;
	}

	/**
	 * Read the bound GPU texture back. This is the path that makes atlases work
	 * — they are stitched at runtime and have no resource-pack file — and it also
	 * picks up the current frame of an animated texture.
	 */
	private Pixels readFromGpu(ResourceLocation texture) {
		try {
			AbstractTexture abstractTexture = Minecraft.getInstance().getTextureManager().getTexture(texture, null);
			if (abstractTexture == null) {
				return null;
			}
			int glId = abstractTexture.getId();
			if (glId == AbstractTexture.NOT_ASSIGNED || glId <= 0) {
				return null;
			}

			GlStateManager._bindTexture(glId);
			int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			if (width <= 0 || height <= 0) {
				return null;
			}

			ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

			int[] pixels = new int[width * height];
			for (int i = 0; i < pixels.length; i++) {
				int r = buffer.get(i * 4) & 0xFF;
				int g = buffer.get(i * 4 + 1) & 0xFF;
				int b = buffer.get(i * 4 + 2) & 0xFF;
				int a = buffer.get(i * 4 + 3) & 0xFF;
				pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
			}
			return new Pixels(width, height, pixels);
		} catch (Throwable throwable) {
			BlockCapture.LOGGER.warn("Could not read {} back from the GPU", texture, throwable);
			return null;
		}
	}

	/** Fall back to the resource-pack PNG, which works for standalone textures. */
	private Pixels readFromResource(ResourceLocation texture) {
		try {
			Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
			if (resource.isEmpty()) {
				return null;
			}
			try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int width = image.getWidth();
				int height = image.getHeight();
				int[] pixels = new int[width * height];
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						pixels[y * width + x] = toArgb(image.getPixelRGBA(x, y));
					}
				}
				return new Pixels(width, height, pixels);
			}
		} catch (Exception exception) {
			return null;
		}
	}

	/** NativeImage stores ABGR; repack to ARGB for our own use. */
	private static int toArgb(int abgr) {
		int r = abgr & 0xFF;
		int g = (abgr >> 8) & 0xFF;
		int b = (abgr >> 16) & 0xFF;
		int a = (abgr >> 24) & 0xFF;
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	private static int clamp(int value, int min, int max) {
		if (value < min) {
			return min;
		}
		return Math.min(value, max);
	}
}
