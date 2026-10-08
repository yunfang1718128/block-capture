package com.yunfang.blockcapture.voxel;

import java.util.List;

import com.yunfang.blockcapture.capture.Quad;
import com.yunfang.blockcapture.capture.Vertex;
import com.yunfang.blockcapture.texture.TextureSampler;
import net.minecraft.core.Direction;

/**
 * Rasterises captured quads into a voxel grid at Minecraft model resolution
 * (16 units per block).
 *
 * <ul>
 *   <li>The grid spans the union of the block's own cell {@code [0,1]³} and the
 *       geometry's actual bounds. A block model normally sits inside its cell, so
 *       this is just the cell; a model that legitimately hangs outside it — a
 *       banner's cloth, a bed's head half, a modded renderer's overshoot — is
 *       captured whole instead of being clipped.</li>
 *   <li>Each quad is split into two triangles; for every grid cell in the
 *       triangle's projected bounding box we test barycentric containment,
 *       resolve the missing axis via the barycentric weights, and pull the
 *       surface colour from the quad's texture.</li>
 *   <li>A face plane is mapped to the voxel on its <em>interior</em> side (half
 *       a voxel along the inward normal), so a face sitting exactly on
 *       {@code x = 0} or {@code x = 1} lands inside the grid rather than being
 *       clipped away.</li>
 * </ul>
 */
public final class BlockVoxelizer {
	private static final float ALPHA_CUTOFF = 0.1F;
	private static final double EPSILON = 1.0e-9;
	/**
	 * A face plane whose depth lands within this many voxels of an integer is
	 * snapped to it, so a quad authored at, say, 0.4999999 still lands on the
	 * voxel row it visually belongs to.
	 */
	private static final double PLANE_SNAP = 0.15D;
	/** Largest integer supersample the caller may request. */
	public static final int MAX_SUPERSAMPLE = 4;
	/** Hard cap on one axis, so a pathological model cannot allocate the world. */
	private static final int MAX_DIM = 512;
	/** Hard cap on the whole grid; a renderer that draws something huge is refused. */
	private static final long MAX_VOXELS = 8_000_000L;

	private BlockVoxelizer() {
	}

	/**
	 * @param unitsPerBlock model pixels per block; vanilla is 16
	 * @param supersample   integer resolution multiplier; one texel owns one
	 *                      voxel when this matches the texture pitch
	 */
	public static VoxelGrid voxelize(
			List<Quad> quads,
			TextureSampler sampler,
			int unitsPerBlock,
			int supersample) {
		int factor = Math.max(1, Math.min(MAX_SUPERSAMPLE, supersample));
		double scale = (double) unitsPerBlock * factor;

		if (quads.isEmpty()) {
			return new VoxelGrid(unitsPerBlock, unitsPerBlock, unitsPerBlock);
		}

		// Start from the block's own cell so a thin model still owns a full block.
		double[] min = { 0.0D, 0.0D, 0.0D };
		double[] max = { 1.0D, 1.0D, 1.0D };
		for (Quad quad : quads) {
			include(min, max, quad.v0());
			include(min, max, quad.v1());
			include(min, max, quad.v2());
			include(min, max, quad.v3());
		}

		// Snap the grid to whole model pixels, using floor/ceil so fractional
		// extents are not clipped; the epsilon absorbs float noise at exact pixels.
		double[] origin = new double[3];
		int[] size = new int[3];
		for (int axis = 0; axis < 3; axis++) {
			int originPx = (int) Math.floor(min[axis] * scale + 1.0e-3);
			int endPx = (int) Math.ceil(max[axis] * scale - 1.0e-3);
			origin[axis] = originPx / scale;
			size[axis] = clampDim(endPx - originPx);
		}

		long volume = (long) size[0] * size[1] * size[2];
		if (volume > MAX_VOXELS) {
			throw new IllegalStateException("模型过大（" + size[0] + "×" + size[1] + "×" + size[2]
					+ " = " + volume + " 体素），超出上限 " + MAX_VOXELS);
		}

		VoxelGrid grid = new VoxelGrid(size[0], size[1], size[2]);
		RasterContext context = new RasterContext(grid, sampler, scale, origin, size);
		for (Quad quad : quads) {
			rasterize(context, quad, quad.v0(), quad.v1(), quad.v2());
			rasterize(context, quad, quad.v0(), quad.v2(), quad.v3());
		}
		return grid;
	}

	private static void include(double[] min, double[] max, Vertex vertex) {
		min[0] = Math.min(min[0], vertex.x);
		min[1] = Math.min(min[1], vertex.y);
		min[2] = Math.min(min[2], vertex.z);
		max[0] = Math.max(max[0], vertex.x);
		max[1] = Math.max(max[1], vertex.y);
		max[2] = Math.max(max[2], vertex.z);
	}

	private static void rasterize(RasterContext context, Quad quad, Vertex a, Vertex b, Vertex c) {
		double nx = (double) (b.y - a.y) * (c.z - a.z) - (double) (b.z - a.z) * (c.y - a.y);
		double ny = (double) (b.z - a.z) * (c.x - a.x) - (double) (b.x - a.x) * (c.z - a.z);
		double nz = (double) (b.x - a.x) * (c.y - a.y) - (double) (b.y - a.y) * (c.x - a.x);
		double nxAbs = Math.abs(nx);
		double nyAbs = Math.abs(ny);
		double nzAbs = Math.abs(nz);
		if (nxAbs < EPSILON && nyAbs < EPSILON && nzAbs < EPSILON) {
			return;
		}

		int dominant;
		if (nxAbs >= nyAbs && nxAbs >= nzAbs) {
			dominant = 0;
		} else if (nyAbs >= nzAbs) {
			dominant = 1;
		} else {
			dominant = 2;
		}

		// Prefer the model's own facing for the outward direction: `mirror` cubes
		// reverse the vertex winding, which would otherwise flip the sign we
		// derive from the cross product. Renderer quads have no declared facing, so
		// for those the winding is trusted.
		double fnx = nx;
		double fny = ny;
		double fnz = nz;
		Direction direction = quad.direction();
		if (direction != null) {
			fnx = direction.getStepX();
			fny = direction.getStepY();
			fnz = direction.getStepZ();
		}
		double dominantNormal = dominant == 0 ? fnx : (dominant == 1 ? fny : fnz);
		if (Math.abs(dominantNormal) < EPSILON) {
			dominantNormal = dominant == 0 ? nx : (dominant == 1 ? ny : nz);
		}

		// A face at plane P joins the solid on the side opposite its outward
		// normal: a +normal face owns the cell just below P (ceil(P)-1), while a
		// -normal face owns the cell just above P (floor(P)). In voxel units that
		// is a half-voxel shift only for +normal faces.
		double inward = dominantNormal > 0.0 ? -0.5 : 0.0;
		int facePriority = facePriority(fnx, fny, fnz);

		int axisA = (dominant == 0) ? 1 : 0;
		int axisB = (dominant == 2) ? 1 : 2;

		double ax = component(a, axisA);
		double ay = component(a, axisB);
		double bx = component(b, axisA);
		double by = component(b, axisB);
		double cx = component(c, axisA);
		double cy = component(c, axisB);

		double denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
		if (Math.abs(denominator) < EPSILON) {
			return;
		}

		double minA = Math.min(ax, Math.min(bx, cx));
		double maxA = Math.max(ax, Math.max(bx, cx));
		double minB = Math.min(ay, Math.min(by, cy));
		double maxB = Math.max(ay, Math.max(by, cy));

		double scale = context.scale();
		double originA = context.origin()[axisA];
		double originB = context.origin()[axisB];
		int sizeA = context.size()[axisA];
		int sizeB = context.size()[axisB];
		int sizeDominant = context.size()[dominant];

		int startA = (int) Math.floor((minA - originA) * scale);
		int endA = (int) Math.ceil((maxA - originA) * scale);
		int startB = (int) Math.floor((minB - originB) * scale);
		int endB = (int) Math.ceil((maxB - originB) * scale);

		startA = Math.max(startA, -1);
		startB = Math.max(startB, -1);
		endA = Math.min(endA, sizeA);
		endB = Math.min(endB, sizeB);

		for (int ia = startA; ia <= endA; ia++) {
			double pa = originA + (ia + 0.5) / scale;
			for (int ib = startB; ib <= endB; ib++) {
				double pb = originB + (ib + 0.5) / scale;

				double wA = ((by - cy) * (pa - cx) + (cx - bx) * (pb - cy)) / denominator;
				double wB = ((cy - ay) * (pa - cx) + (ax - cx) * (pb - cy)) / denominator;
				double wC = 1.0 - wA - wB;

				if (wA < -EPSILON || wB < -EPSILON || wC < -EPSILON) {
					continue;
				}

				double pc = wA * component(a, dominant) + wB * component(b, dominant) + wC * component(c, dominant);
				double off = (pc - context.origin()[dominant]) * scale;
				double nearest = Math.floor(off + 0.5D);
				if (Math.abs(off - nearest) < PLANE_SNAP) {
					off = nearest;
				}
				int ic = (int) Math.floor(off + inward + 1.0e-6);
				if (ic < 0) {
					ic = 0;
				} else if (ic >= sizeDominant) {
					ic = sizeDominant - 1;
				}

				int x = axisValue(dominant, axisA, axisB, 0, ic, ia, ib);
				int y = axisValue(dominant, axisA, axisB, 1, ic, ia, ib);
				int z = axisValue(dominant, axisA, axisB, 2, ic, ia, ib);
				if (!context.grid().inBounds(x, y, z)) {
					continue;
				}

				float u = (float) (wA * a.u + wB * b.u + wC * c.u);
				float v = (float) (wA * a.v + wB * b.v + wC * c.v);
				int argb = context.sampler().sample(quad.texture(), u, v);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha / 255.0F < ALPHA_CUTOFF) {
					continue;
				}

				float tintR = (float) (wA * a.r + wB * b.r + wC * c.r);
				float tintG = (float) (wA * a.g + wB * b.g + wC * c.g);
				float tintB = (float) (wA * a.b + wB * b.b + wC * c.b);
				float tintA = (float) (wA * a.a + wB * b.a + wC * c.a);

				int red = clampByte((int) (((argb >> 16) & 0xFF) * tintR));
				int green = clampByte((int) (((argb >> 8) & 0xFF) * tintG));
				int blue = clampByte((int) ((argb & 0xFF) * tintB));
				int outAlpha = clampByte((int) (alpha * tintA));

				context.grid().set(x, y, z, red, green, blue, outAlpha, facePriority, quad.layer());
			}
		}
	}

	/**
	 * Ranks a face normal so the one facing the model's front ({@code +Z}) wins
	 * colour conflicts, then the top ({@code +Y}), then the sides ({@code |X|}).
	 */
	private static int facePriority(double nx, double ny, double nz) {
		int pz = nz > 0.5 ? 2 : (nz < -0.5 ? 0 : 1);
		int py = ny > 0.5 ? 1 : 0;
		int px = Math.abs(nx) > 0.5 ? 1 : 0;
		return pz * 4 + py * 2 + px;
	}

	private static int axisValue(int dominant, int axisA, int axisB, int axis, int dominantIndex, int indexA, int indexB) {
		if (axis == dominant) {
			return dominantIndex;
		}
		if (axis == axisA) {
			return indexA;
		}
		return indexB;
	}

	private static double component(Vertex vertex, int axis) {
		switch (axis) {
			case 0:
				return vertex.x;
			case 1:
				return vertex.y;
			default:
				return vertex.z;
		}
	}

	private static int clampByte(int value) {
		if (value < 0) {
			return 0;
		}
		return Math.min(value, 255);
	}

	private static int clampDim(int value) {
		if (value < 1) {
			return 1;
		}
		return Math.min(value, MAX_DIM);
	}

	private record RasterContext(
			VoxelGrid grid,
			TextureSampler sampler,
			double scale,
			double[] origin,
			int[] size) {
	}
}
