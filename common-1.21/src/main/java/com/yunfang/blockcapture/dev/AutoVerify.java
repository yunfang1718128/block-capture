package com.yunfang.blockcapture.dev;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.InflaterInputStream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yunfang.blockcapture.BlockCapture;
import com.yunfang.blockcapture.capture.BlockCaptureService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * DEV-ONLY capture harness. Never runs unless the JVM property
 * {@code blockcapture.verify} lists block ids:
 *
 * <pre>
 * JAVA_TOOL_OPTIONS="-Dblockcapture.verify=minecraft:chest,minecraft:oak_sign"
 * .\gradlew.bat :neoforge-1.21:runClient
 * </pre>
 *
 * <p>Once the client reaches the title screen it captures <em>every</em> block
 * state of every listed block, reads each written file back to check the
 * container, writes a report to {@code <run>/block-capture-verify.txt} and
 * quits. It exists so the capture paths can be exercised without a human at the
 * keyboard: it found the "a renderer-only block is invisible" bug that a
 * keybind sweep cannot reach reliably. Without the property it is a no-op, so
 * it stays in the jar as a regression hook rather than a debug leftover.
 */
public final class AutoVerify {
	private static final String PROPERTY = "blockcapture.verify";
	/** Read once: the property is set on the JVM command line, never at runtime. */
	private static final String SPEC = System.getProperty(PROPERTY);
	private static final Gson GSON = new Gson();
	private static final int WARMUP_TICKS = 40;
	private static final int FLAG_COMPRESSED = 1;
	private static final int HEADER_BYTES = 12;

	private static boolean started;
	private static int ticks;

	private AutoVerify() {
	}

	public static void tick(Minecraft client) {
		if (started || SPEC == null) {
			return;
		}
		if (!(client.screen instanceof TitleScreen)) {
			return;
		}
		if (++ticks < WARMUP_TICKS) {
			return;
		}
		started = true;
		try {
			run(client, SPEC);
		} catch (Throwable throwable) {
			BlockCapture.LOGGER.error("auto-verify crashed", throwable);
		}
		client.stop();
	}

	private static void run(Minecraft client, String spec) throws IOException {
		List<String> lines = new ArrayList<>();
		int captured = 0;
		int failed = 0;
		int states = 0;

		for (String entry : spec.split(",")) {
			String id = entry.trim();
			if (id.isEmpty()) {
				continue;
			}
			if (id.endsWith("/*")) {
				id = id.substring(0, id.length() - 2);
			}
			ResourceLocation key = ResourceLocation.tryParse(id);
			Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
			if (block == null) {
				lines.add("MISSING " + id);
				failed++;
				continue;
			}

			for (BlockState state : block.getStateDefinition().getPossibleStates()) {
				states++;
				String label = state.toString();
				try {
					BlockCaptureService.Result result = BlockCaptureService.capture(state);
					String check = readBack(result.path());
					lines.add(String.format(Locale.ROOT,
							"OK  %-70s mode=%-15s quads=%-4d grid=%dx%dx%d voxels=%-6d colored=%-6d tex=%-3d %5.1fms %s",
							label, result.captureMode(), result.quads(),
							result.sizeX(), result.sizeY(), result.sizeZ(),
							result.voxels(), result.coloredVoxels(), result.textures(), result.millis(), check));
					captured++;
				} catch (Throwable throwable) {
					lines.add("FAIL " + label + " -> " + throwable);
					BlockCapture.LOGGER.warn("verify: capture of {} failed", label, throwable);
					failed++;
				}
			}
		}

		lines.add(0, "==== block-capture auto-verify: states=" + states + " captured=" + captured + " failed=" + failed);
		Path report = new File(client.gameDirectory, "block-capture-verify.txt").toPath();
		Files.write(report, lines);
		for (String line : lines) {
			BlockCapture.LOGGER.info("[verify] {}", line);
		}
		BlockCapture.LOGGER.info("[verify] report written to {}", report);
	}

	/**
	 * Read a written capture back and check the container, so the report covers
	 * the file format as well as the capture: magic, version, header JSON,
	 * payload length, occupancy population and colour coverage.
	 */
	private static String readBack(Path path) throws IOException {
		byte[] file = Files.readAllBytes(path);
		if (file.length < HEADER_BYTES || file[0] != 0x4D || file[1] != 0x43 || file[2] != 0x56 || file[3] != 0x58) {
			return "[READBACK magic/length BAD]";
		}
		if (file[4] != 1) {
			return "[READBACK formatVersion=" + file[4] + " BAD]";
		}
		int flags = file[5] & 0xFF;
		int headerLength = (file[8] & 0xFF) | ((file[9] & 0xFF) << 8) | ((file[10] & 0xFF) << 16) | ((file[11] & 0xFF) << 24);
		String json = new String(file, HEADER_BYTES, headerLength, StandardCharsets.UTF_8);
		JsonObject header = GSON.fromJson(json, JsonObject.class);
		byte[] payload = new byte[file.length - HEADER_BYTES - headerLength];
		System.arraycopy(file, HEADER_BYTES + headerLength, payload, 0, payload.length);
		if ((flags & FLAG_COMPRESSED) != 0) {
			try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(payload))) {
				payload = inflater.readAllBytes();
			}
		}

		JsonArray dimensions = header.getAsJsonArray("dimensions");
		int n = dimensions.get(0).getAsInt() * dimensions.get(1).getAsInt() * dimensions.get(2).getAsInt();
		int expected = (n + 7) / 8 + n * 4;
		if (payload.length != expected) {
			return "[READBACK payload=" + payload.length + " expected=" + expected + " BAD]";
		}

		int occupied = 0;
		for (int i = 0; i < n; i++) {
			if ((payload[i >> 3] & (1 << (i & 7))) != 0) {
				occupied++;
			}
		}
		long r = 0;
		long g = 0;
		long b = 0;
		int colored = 0;
		for (int i = 0; i < n; i++) {
			int offset = (n + 7) / 8 + i * 4;
			int alpha = payload[offset + 3] & 0xFF;
			if (alpha != 0) {
				colored++;
				r += payload[offset] & 0xFF;
				g += payload[offset + 1] & 0xFF;
				b += payload[offset + 2] & 0xFF;
			}
		}
		String mean = colored == 0
				? "mean=------"
				: String.format(Locale.ROOT, "mean=#%02x%02x%02x", r / colored, g / colored, b / colored);
		return String.format(Locale.ROOT, "[readback occ=%d payload=%d %s subject=%s name=%s]",
				occupied, payload.length, mean, header.get("subject"), header.get("entityName"));
	}
}
