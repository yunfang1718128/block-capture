package com.yunfang.blockcapture.capture;

import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link VertexConsumer} that records quads. Minecraft's block models and
 * block-entity renderers both always emit quads (four {@code addVertex} calls,
 * each followed by colour/UV/light/normal), so we buffer four vertices at a time
 * and hand the finished quad to a sink.
 *
 * <p>Whatever layer this consumer belongs to has already mapped its UVs into the
 * layer's texture by the time they arrive: block models bake atlas coordinates
 * in {@code FaceBakery}, and material-based renderers wrap us in
 * {@code SpriteCoordinateExpander}, which does the same.
 */
public final class CapturingVertexConsumer implements VertexConsumer {
	/** Vanilla's quads always carry a facing; renderer quads do not. */
	private final ResourceLocation texture;
	private final int layer;
	private final Consumer<Quad> sink;

	private final Vertex[] quad = new Vertex[] { new Vertex(), new Vertex(), new Vertex(), new Vertex() };
	private int count;
	private Vertex current;

	public CapturingVertexConsumer(ResourceLocation texture, int layer, Consumer<Quad> sink) {
		this.texture = texture;
		this.layer = layer;
		this.sink = sink;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		if (this.count == 4) {
			this.emit();
		}
		this.current = this.quad[this.count++];
		this.current.x = x;
		this.current.y = y;
		this.current.z = z;
		this.current.u = 0.0F;
		this.current.v = 0.0F;
		this.current.setPackedTint(0xFFFFFF);
		return this;
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha) {
		if (this.current != null) {
			this.current.r = red / 255.0F;
			this.current.g = green / 255.0F;
			this.current.b = blue / 255.0F;
			this.current.a = alpha / 255.0F;
		}
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		if (this.current != null) {
			this.current.u = u;
			this.current.v = v;
		}
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		return this;
	}

	/** Emits the final quad if one is complete (four vertices buffered). */
	public void flush() {
		if (this.count == 4) {
			this.emit();
		}
	}

	private void emit() {
		this.sink.accept(new Quad(
				new Vertex(this.quad[0]),
				new Vertex(this.quad[1]),
				new Vertex(this.quad[2]),
				new Vertex(this.quad[3]),
				this.texture,
				this.layer,
				null));
		this.count = 0;
		this.current = null;
	}
}
