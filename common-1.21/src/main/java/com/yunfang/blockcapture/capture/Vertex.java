package com.yunfang.blockcapture.capture;

/**
 * One rasteriser input vertex: a position in block space, a texture coordinate
 * already normalised across the quad's texture (atlas or standalone), and the
 * renderer's tint multiplier.
 *
 * <p>The tint is per-vertex because the two capture paths supply it differently:
 * a block model's {@code BakedQuad} carries a flat white colour and takes its
 * tint from {@code BlockColors}, while a {@code BlockEntityRenderer} bakes its
 * colour straight into the vertices via {@code setColor} (leather, text, banner
 * dye). Interpolating it keeps both paths on one rasteriser.
 */
public final class Vertex {
	public float x;
	public float y;
	public float z;
	public float u;
	public float v;
	public float r = 1.0F;
	public float g = 1.0F;
	public float b = 1.0F;
	public float a = 1.0F;

	public Vertex() {
	}

	public Vertex(Vertex other) {
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
		this.u = other.u;
		this.v = other.v;
		this.r = other.r;
		this.g = other.g;
		this.b = other.b;
		this.a = other.a;
	}

	/** Set from a packed {@code 0xAARRGGBB} value, as {@code VertexConsumer} takes it. */
	public void setPackedColor(int argb) {
		this.a = ((argb >>> 24) & 0xFF) / 255.0F;
		this.r = ((argb >>> 16) & 0xFF) / 255.0F;
		this.g = ((argb >>> 8) & 0xFF) / 255.0F;
		this.b = (argb & 0xFF) / 255.0F;
	}

	/** Set from a packed {@code 0xRRGGBB} tint (alpha stays opaque). */
	public void setPackedTint(int rgb) {
		this.a = 1.0F;
		this.r = ((rgb >>> 16) & 0xFF) / 255.0F;
		this.g = ((rgb >>> 8) & 0xFF) / 255.0F;
		this.b = (rgb & 0xFF) / 255.0F;
	}
}
