package com.yunfang.blockcapture.capture;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * A {@link MultiBufferSource} that records quads instead of drawing them. One
 * {@link CapturingVertexConsumer} is created per {@link RenderType}, so the
 * quads keep the texture that layer binds — the block atlas for a model, a
 * dedicated atlas or a standalone PNG for a renderer.
 */
public final class CapturingMultiBufferSource implements MultiBufferSource {
	private final Map<RenderType, CapturingVertexConsumer> buffers = new IdentityHashMap<>();
	private final List<Quad> quads = new ArrayList<>();
	private int nextLayer;

	@Override
	public VertexConsumer getBuffer(RenderType renderType) {
		return this.buffers.computeIfAbsent(renderType, type -> {
			ResourceLocation texture = RenderTypeTextures.resolve(type).orElse(null);
			return new CapturingVertexConsumer(texture, this.nextLayer++, this.quads::add);
		});
	}

	/** Flushes any complete-but-unemitted quads from every layer. */
	public void flush() {
		for (CapturingVertexConsumer consumer : this.buffers.values()) {
			consumer.flush();
		}
	}

	public List<Quad> quads() {
		return this.quads;
	}

	/** Number of distinct render layers that produced quads. */
	public int layerCount() {
		return this.buffers.size();
	}
}
