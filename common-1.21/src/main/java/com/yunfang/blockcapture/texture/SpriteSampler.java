package com.yunfang.blockcapture.texture;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yunfang.blockcapture.BlockCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.inventory.InventoryMenu;
import org.lwjgl.opengl.GL11;

/**
 * Reads raw (unlit) sprite pixels for block faces, so a capture's colour comes
 * from the artist's texture rather than from baked-in lighting.
 *
 * <p>Pixels come from the stitched block atlas, read back from the GPU once per
 * capture. That is the only source that works identically on Fabric and
 * NeoForge: the sprite's own decoded image is reachable through
 * {@code SpriteContents#getOriginalImage()} on NeoForge, but that accessor is a
 * NeoForge patch and does not exist in vanilla (so Fabric builds cannot use
 * it). The stitched atlas keeps a resource pack's native resolution — a
 * 32&times;32 texture occupies 32&times;32 texels of the atlas — and it also
 * covers animated sprites (frame 0) and textures a mod registers at runtime.
 *
 * <p>UVs are expected to be sprite-local in {@code [0,1]}; the caller normalises
 * them (see {@code BlockCaptureService}) so this class never has to guess which
 * UV space a model used. Sampling then goes through
 * {@link TextureAtlasSprite#getU}/{@link TextureAtlasSprite#getV}, i.e.
 * Minecraft's own sprite&rarr;atlas mapping, so mipmap shrink and stitching
 * offsets are handled by the game rather than by us.
 */
public final class SpriteSampler {
	private int[] atlasPixels;
	private int atlasWidth;
	private int atlasHeight;
	private boolean atlasFailed;

	/** Packed ARGB for sprite-local {@code (u, v)}; alpha 0 when unreadable. */
	public int sample(TextureAtlasSprite sprite, float u, float v) {
		if (!readAtlas()) {
			return 0;
		}
		float atlasU = sprite.getU(clamp01(u));
		float atlasV = sprite.getV(clamp01(v));
		int x = clamp((int) (atlasU * this.atlasWidth), 0, this.atlasWidth - 1);
		int y = clamp((int) (atlasV * this.atlasHeight), 0, this.atlasHeight - 1);
		return this.atlasPixels[y * this.atlasWidth + x];
	}

	/** Reads the block atlas back from the GPU, at most once per sampler. */
	private boolean readAtlas() {
		if (this.atlasPixels != null) {
			return true;
		}
		if (this.atlasFailed) {
			return false;
		}

		try {
			AbstractTexture texture = Minecraft.getInstance().getTextureManager()
					.getTexture(InventoryMenu.BLOCK_ATLAS, null);
			if (texture == null) {
				this.atlasFailed = true;
				return false;
			}
			int glId = texture.getId();
			if (glId == AbstractTexture.NOT_ASSIGNED || glId <= 0) {
				this.atlasFailed = true;
				return false;
			}

			GlStateManager._bindTexture(glId);
			int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
			int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
			if (width <= 0 || height <= 0) {
				this.atlasFailed = true;
				return false;
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

			this.atlasPixels = pixels;
			this.atlasWidth = width;
			this.atlasHeight = height;
			BlockCapture.LOGGER.info("Read the block atlas back from the GPU ({}x{}) for sprite colours", width, height);
			return true;
		} catch (Throwable throwable) {
			BlockCapture.LOGGER.warn("Could not read the block atlas back from the GPU; captured faces will be colourless", throwable);
			this.atlasFailed = true;
			return false;
		}
	}

	private static float clamp01(float value) {
		if (value < 0.0F) {
			return 0.0F;
		}
		return Math.min(value, 1.0F);
	}

	private static int clamp(int value, int min, int max) {
		if (value < min) {
			return min;
		}
		return Math.min(value, max);
	}
}
