package com.yunfang.blockcapture.mcvox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import com.google.gson.Gson;
import com.yunfang.blockcapture.voxel.VoxelGrid;

/**
 * Serialises a {@link VoxelGrid} into the `.mcvox` v1 container — byte-for-byte
 * the same format entity-capture writes, so MC Block Studio needs no changes to
 * read a block capture:
 *
 * <pre>
 * [0..3]   magic "MCVX"
 * [4]      uint8  formatVersion
 * [5]      uint8  flags (bit0 = zlib compressed payload)
 * [6..7]   uint16 reserved
 * [8..11]  uint32 headerLength (LE)
 * [12..]   header JSON (UTF-8)
 * then     occupancy: ceil(n / 8) bytes (LSB first)
 * then     colors:    n * 4 bytes RGBA
 * </pre>
 */
public final class McvoxWriter {
	public static final int FORMAT_VERSION = 1;
	private static final byte[] MAGIC = { 0x4D, 0x43, 0x56, 0x58 };
	private static final int FLAG_COMPRESSED = 1;
	private static final int HEADER_BYTES = 12;
	private static final Gson GSON = new Gson();

	private McvoxWriter() {
	}

	public static Path write(Path directory, McvoxHeader header, VoxelGrid grid, boolean compress) throws IOException {
		byte[] headerJson = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);

		byte[] occupancy = grid.occupancy();
		byte[] colors = grid.colors();
		byte[] payload = new byte[occupancy.length + colors.length];
		System.arraycopy(occupancy, 0, payload, 0, occupancy.length);
		System.arraycopy(colors, 0, payload, occupancy.length, colors.length);

		int flags = 0;
		if (compress) {
			payload = deflate(payload);
			flags |= FLAG_COMPRESSED;
		}

		ByteArrayOutputStream out = new ByteArrayOutputStream(HEADER_BYTES + headerJson.length + payload.length);
		out.write(MAGIC);
		out.write(FORMAT_VERSION);
		out.write(flags);
		out.write(0);
		out.write(0);
		writeUint32LE(out, headerJson.length);
		out.write(headerJson);
		out.write(payload);

		Files.createDirectories(directory);
		Path target = directory.resolve(fileName(header));
		Files.write(target, out.toByteArray());
		return target;
	}

	private static String fileName(McvoxHeader header) {
		String id = header.entityId == null ? "block" : header.entityId.replace(':', '_').replace('/', '_');
		String stamp = header.generatedAt == null ? Long.toString(System.currentTimeMillis()) : header.generatedAt;
		String safeStamp = stamp.replace(':', '-').replace('.', '-');
		return id + "_" + safeStamp + ".mcvox";
	}

	private static void writeUint32LE(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >>> 8) & 0xFF);
		out.write((value >>> 16) & 0xFF);
		out.write((value >>> 24) & 0xFF);
	}

	private static byte[] deflate(byte[] data) {
		Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
		try {
			deflater.setInput(data);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
			byte[] buffer = new byte[8192];
			while (!deflater.finished()) {
				int written = deflater.deflate(buffer);
				out.write(buffer, 0, written);
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}
}
