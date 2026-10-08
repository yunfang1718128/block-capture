package com.yunfang.blockcapture.voxel;

/**
 * Native-resolution voxel grid: a bit-packed occupancy map plus an RGBA surface
 * colour layer. Index layout matches the studio's {@code VoxelModel}:
 * {@code x + z * sizeX + y * sizeX * sizeZ}.
 *
 * <p>Every coloured voxel also stores a rank describing which window owns it:
 * the render layer (outer/overlay beats inner) first, then the face orientation
 * (front {@code +Z} beats top, then sides). Higher rank wins a conflict.
 */
public final class VoxelGrid {
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	private final byte[] occupancy;
	private final byte[] colors;
	/** Packed rank of the winning quad: {@code (layer << 6) | facePriority}. */
	private final short[] rank;

	public VoxelGrid(int sizeX, int sizeY, int sizeZ) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		int count = sizeX * sizeY * sizeZ;
		this.occupancy = new byte[(count + 7) / 8];
		this.colors = new byte[count * 4];
		this.rank = new short[count];
	}

	private int index(int x, int y, int z) {
		return x + z * this.sizeX + y * this.sizeX * this.sizeZ;
	}

	/** Packs the layer (outer wins) and face orientation into one comparable value. */
	private static int rank(int layer, int facePriority) {
		return ((layer & 0x3FF) << 6) | (facePriority & 0x3F);
	}

	public boolean inBounds(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0 && x < this.sizeX && y < this.sizeY && z < this.sizeZ;
	}

	/**
	 * Marks a voxel occupied and stores its RGBA surface colour. When several
	 * quads meet at one voxel the higher rank wins (outer layer first, then
	 * front-facing orientation); on a full tie the darker colour wins, which
	 * keeps dark features such as a stair's front edge.
	 */
	public void set(int x, int y, int z, int r, int g, int b, int a, int facePriority, int layer) {
		if (!this.inBounds(x, y, z)) {
			return;
		}
		int index = this.index(x, y, z);
		this.occupancy[index >> 3] |= (byte) (1 << (index & 7));
		int offset = index * 4;
		int incomingRank = rank(layer, facePriority);

		if ((this.colors[offset + 3] & 0xFF) != 0) {
			int existingRank = this.rank[index] & 0xFFFF;
			if (incomingRank < existingRank) {
				return;
			}
			if (incomingRank == existingRank && luminance(this.colors, offset) <= r + g + b) {
				return;
			}
		}

		this.rank[index] = (short) incomingRank;
		this.colors[offset] = (byte) r;
		this.colors[offset + 1] = (byte) g;
		this.colors[offset + 2] = (byte) b;
		this.colors[offset + 3] = (byte) a;
	}

	private static int luminance(byte[] colors, int offset) {
		return (colors[offset] & 0xFF) + (colors[offset + 1] & 0xFF) + (colors[offset + 2] & 0xFF);
	}

	public byte[] occupancy() {
		return this.occupancy;
	}

	public byte[] colors() {
		return this.colors;
	}

	/** Number of voxels that ended up occupied. */
	public int nonEmptyVoxels() {
		int count = 0;
		for (byte value : this.occupancy) {
			count += Integer.bitCount(value & 0xFF);
		}
		return count;
	}

	/** Number of voxels that carry a surface colour. */
	public int coloredVoxels() {
		int count = 0;
		for (int i = 3; i < this.colors.length; i += 4) {
			if ((this.colors[i] & 0xFF) != 0) {
				count++;
			}
		}
		return count;
	}
}
