package com.yunfang.blockcapture.capture;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

/**
 * One captured quad: four vertices in block space, the texture it samples, its
 * capture order and — for block models — the facing the model declared.
 *
 * <p>Both capture paths produce exactly this shape. A block model's
 * {@code BakedQuad} already stores UVs mapped into its render type's texture
 * ({@code FaceBakery} writes {@code sprite.getU(u / 16)}), and a
 * {@code BlockEntityRenderer} reaches us through {@code Material.buffer(...)},
 * which wraps our consumer in {@code SpriteCoordinateExpander} and does the same
 * mapping. So the UVs are always "normalised across {@code texture}" and one
 * sampler serves both.
 *
 * @param texture   the texture the quad's render type binds: a stitched atlas
 *                  ({@code textures/atlas/blocks.png}, {@code chest.png}, …)
 *                  for most, or a standalone PNG (skull skins) for a few
 * @param layer     capture order; a later quad outranks an earlier one, which is
 *                  how a coplanar overlay that vanilla draws last wins the voxel
 * @param direction the block model's own facing, or {@code null} for general
 *                  quads and for every quad that came from a renderer
 */
public record Quad(
		Vertex v0,
		Vertex v1,
		Vertex v2,
		Vertex v3,
		ResourceLocation texture,
		int layer,
		Direction direction) {
}
