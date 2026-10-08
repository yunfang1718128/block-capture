package com.yunfang.blockcapture.capture;

/**
 * One rasteriser input vertex: a position in block space plus a texture
 * coordinate already normalised to 0..1 across the quad's sprite.
 */
public final class Vertex {
	public float x;
	public float y;
	public float z;
	public float u;
	public float v;

	public Vertex() {
	}

	public Vertex(Vertex other) {
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
		this.u = other.u;
		this.v = other.v;
	}
}
