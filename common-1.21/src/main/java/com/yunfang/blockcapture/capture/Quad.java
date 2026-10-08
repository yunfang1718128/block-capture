package com.yunfang.blockcapture.capture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

/**
 * One captured model quad: four vertices in block space, the sprite it samples,
 * its packed tint and the model layer it came from.
 *
 * @param sprite    the atlas sprite the quad samples; UVs are sprite-local 0..1
 * @param tint      packed {@code 0xRRGGBB} multiplier for the sampled colour
 *                  (white when the quad carries no tint index)
 * @param layer     collection order, so a later overlay layer outranks an
 *                  earlier base layer when two quads fight over one voxel
 * @param direction the model's own facing, or {@code null} for general quads
 */
public record Quad(
		Vertex v0,
		Vertex v1,
		Vertex v2,
		Vertex v3,
		TextureAtlasSprite sprite,
		int tint,
		int layer,
		Direction direction) {
}
