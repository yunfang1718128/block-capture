package com.yunfang.blockcapture.capture;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import com.yunfang.blockcapture.BlockCapture;
import com.yunfang.blockcapture.mixin.CompositeRenderTypeAccessor;
import com.yunfang.blockcapture.mixin.CompositeStateAccessor;
import com.yunfang.blockcapture.mixin.EmptyTextureStateShardAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Recovers the texture bound to a {@link RenderType} by walking its composite
 * state. That texture is what a captured quad's UVs are normalised against, so
 * it is what the sampler has to read.
 *
 * <p>Vanilla has no public accessor for this, hence the three narrow accessor
 * mixins. Block-entity renderers reach their texture through
 * {@code Material.renderType(...)}, which binds the material's <em>atlas</em>
 * ({@code textures/atlas/chest.png} and friends) rather than the individual
 * sprite, while a few renderers (skull skins) bind a standalone PNG.
 */
public final class RenderTypeTextures {
	/** Render types we already know how to resolve, so repeated misses stay quiet. */
	private static final Set<String> REPORTED = new LinkedHashSet<>();

	private RenderTypeTextures() {
	}

	public static Optional<ResourceLocation> resolve(RenderType type) {
		if (!(type instanceof CompositeRenderTypeAccessor accessor)) {
			report(type, "not a CompositeRenderType");
			return Optional.empty();
		}

		RenderType.CompositeState state = accessor.getState();
		if (state == null) {
			report(type, "composite state is null");
			return Optional.empty();
		}

		RenderStateShard.EmptyTextureStateShard textureState =
				((CompositeStateAccessor) (Object) state).getTextureState();
		if (textureState == null) {
			report(type, "no texture state");
			return Optional.empty();
		}

		Optional<ResourceLocation> texture =
				((EmptyTextureStateShardAccessor) (Object) textureState).invokeCutoutTexture();
		if (texture.isEmpty()) {
			report(type, "texture state has no cutout texture");
		}
		return texture;
	}

	private static void report(RenderType type, String reason) {
		if (REPORTED.add(type.getClass().getName() + " => " + type)) {
			BlockCapture.LOGGER.warn("Could not resolve a texture for render type {} ({}); its quads will be colourless",
					type, reason);
		}
	}
}
