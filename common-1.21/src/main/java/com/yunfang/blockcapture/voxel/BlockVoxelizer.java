package com.yunfang.blockcapture.voxel;

import java.util.List;

import com.yunfang.blockcapture.capture.Quad;
import com.yunfang.blockcapture.capture.Vertex;
import com.yunfang.blockcapture.texture.SpriteSampler;
import net.minecraft.core.Direction;

/**
 * Rasterises a block model's quads into a fixed-size voxel grid covering
 * exactly one block cell.
 *
 * <p>Unlike the entity capture's voxeliser this one has no pose, scale or
 * symmetry repair to do — block models are static and already expressed in
 * block space — so it is deliberately small:
 *
 * <ul>
 *   <li>The grid is always {@code unitsPerBlock * factor} on every axis, with
 *       its origin at the block's own {@code (0,0,0)}. A torch therefore sits
 *       in a full 16&sup3; cell rather than collapsing to its own 2&times;10&times;2
 *       bounds, so every block capture keeps the same footprint and the same
 *       "one block = 16 model pixels" scale as the studio's built-in blocks.
 *       Geometry outside the cell is clipped.</li>
 *   <li>Each quad is split into two triangles; for every grid cell in the
 *       triangle's projected bounding box we test barycentric containment,
 *       resolve the missing axis via the barycentric weights, and pull the
 *       surface colour from the sprite.</li>
 *   <li>A face plane is mapped to the voxel on its <em>interior</em> side (half
 *       a voxel along the inward normal), so a face sitting exactly on
 *       {@code x = 0} or {@code x = 1} lands inside the cell instead of being
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
	/** Largest integer supersample applied when a texture is finer than the grid. */
	private static final int MAX_SUPERSAMPLE = 4;

	private BlockVoxelizer() {
	}

	/**
	 * Rasterise every quad into a {@code unitsPerBlock * factor}-cubed grid,
	 * where {@code factor} adapts the grid to the finest sprite used.
	 */
	public static VoxelGrid voxelize(List<Quad> quads, SpriteSampler sampler, int unitsPerBlock) {
		if (quads.isEmpty()) {
			return new VoxelGrid(unitsPerBlock, unitsPerBlock, unitsPerBlock);
		}

		int factor = supersampleFactor(quads, unitsPerBlock);
		int size = unitsPerBlock * factor;
		// One block spans the whole grid, so scale is simply the grid size and
		// the origin is the block's own corner.
		double scale = size;

		VoxelGrid grid = new VoxelGrid(size, size, size);
		RasterContext context = new RasterContext(grid, sampler, scale, size);

		for (Quad quad : quads) {
			rasterize(context, quad, quad.v0(), quad.v1(), quad.v2());
			rasterize(context, quad, quad.v0(), quad.v2(), quad.v3());
		}
		return grid;
	}

	/**
	 * Match the voxel pitch to the texture pitch so one texel owns one voxel:
	 * a 16&times;16 sprite on one block face gives a 16&sup3; grid, a 32&times;32
	 * resource-pack texture gives 32&sup3; so its detail survives, capped at
	 * {@value #MAX_SUPERSAMPLE} (64&sup3;).
	 */
	private static int supersampleFactor(List<Quad> quads, int unitsPerBlock) {
		int highest = 0;
		for (Quad quad : quads) {
			highest = Math.max(highest, quad.sprite().contents().width());
			highest = Math.max(highest, quad.sprite().contents().height());
		}
		if (highest <= unitsPerBlock) {
			return 1;
		}
		int factor = (int) Math.ceil(highest / (double) unitsPerBlock);
		return Math.max(1, Math.min(MAX_SUPERSAMPLE, factor));
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
		// derive from the cross product.
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
		int size = context.size();
		int startA = (int) Math.floor(minA * scale);
		int endA = (int) Math.ceil(maxA * scale);
		int startB = (int) Math.floor(minB * scale);
		int endB = (int) Math.ceil(maxB * scale);

		startA = Math.max(startA, 0);
		startB = Math.max(startB, 0);
		endA = Math.min(endA, size - 1);
		endB = Math.min(endB, size - 1);

		for (int ia = startA; ia <= endA; ia++) {
			double pa = (ia + 0.5) / scale;
			for (int ib = startB; ib <= endB; ib++) {
				double pb = (ib + 0.5) / scale;

				double wA = ((by - cy) * (pa - cx) + (cx - bx) * (pb - cy)) / denominator;
				double wB = ((cy - ay) * (pa - cx) + (ax - cx) * (pb - cy)) / denominator;
				double wC = 1.0 - wA - wB;

				if (wA < -EPSILON || wB < -EPSILON || wC < -EPSILON) {
					continue;
				}

				double pc = wA * component(a, dominant) + wB * component(b, dominant) + wC * component(c, dominant);
				double off = pc * scale;
				double nearest = Math.floor(off + 0.5D);
				if (Math.abs(off - nearest) < PLANE_SNAP) {
					off = nearest;
				}
				int ic = (int) Math.floor(off + inward + 1.0e-6);
				if (ic < 0) {
					ic = 0;
				} else if (ic >= size) {
					ic = size - 1;
				}

				int x = axisValue(dominant, axisA, axisB, 0, ic, ia, ib);
				int y = axisValue(dominant, axisA, axisB, 1, ic, ia, ib);
				int z = axisValue(dominant, axisA, axisB, 2, ic, ia, ib);

				float u = (float) (wA * a.u + wB * b.u + wC * c.u);
				float v = (float) (wA * a.v + wB * b.v + wC * c.v);
				int argb = context.sampler().sample(quad.sprite(), u, v);
				int alpha = (argb >>> 24) & 0xFF;
				if (alpha / 255.0F < ALPHA_CUTOFF) {
					continue;
				}

				int tint = quad.tint();
				int red = clampByte((((argb >> 16) & 0xFF) * ((tint >> 16) & 0xFF)) / 255);
				int green = clampByte((((argb >> 8) & 0xFF) * ((tint >> 8) & 0xFF)) / 255);
				int blue = clampByte(((argb & 0xFF) * (tint & 0xFF)) / 255);

				context.grid().set(x, y, z, red, green, blue, 255, facePriority, quad.layer());
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

	private record RasterContext(VoxelGrid grid, SpriteSampler sampler, double scale, int size) {
	}
}
