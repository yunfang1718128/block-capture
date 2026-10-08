package com.yunfang.blockcapture.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.yunfang.blockcapture.BlockCapture;
import com.yunfang.blockcapture.mcvox.McvoxHeader;
import com.yunfang.blockcapture.mcvox.McvoxWriter;
import com.yunfang.blockcapture.texture.SpriteSampler;
import com.yunfang.blockcapture.voxel.BlockVoxelizer;
import com.yunfang.blockcapture.voxel.VoxelGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives one block state through the capture pipeline: collect its baked model's
 * quads, rasterise them into a native-resolution grid and write the `.mcvox`.
 *
 * <p>Nothing is rendered and no mixin is involved. Block models are static and
 * already expressed in block space, so the quads can be read straight off
 * {@link BakedModel#getQuads} — which is what makes this mod markedly simpler
 * than its entity counterpart. Blocks whose appearance comes from a
 * {@code BlockEntityRenderer} (chests, signs, banners) have no baked quads and
 * are rejected with an explanation.
 */
public final class BlockCaptureService {
	public static final int UNITS_PER_BLOCK = 16;
	/** Fixed seed so the same block always produces the same capture. */
	private static final long RANDOM_SEED = 42L;

	/**
	 * {@code DefaultVertexFormat.BLOCK} packs a vertex into 8 ints:
	 * position (3 floats), colour (packed ARGB), UV0 (2 floats), light (2 shorts).
	 */
	private static final int VERTEX_STRIDE = 8;
	private static final int OFFSET_POSITION = 0;
	private static final int OFFSET_UV = 4;

	private BlockCaptureService() {
	}

	/** A finished capture, for the chat summary. */
	public record Result(
			Path path,
			String blockId,
			String blockState,
			int quads,
			int voxels,
			int coloredVoxels,
			int sizeX,
			int sizeY,
			int sizeZ,
			double millis) {
	}

	public static Result capture(BlockState state) throws IOException {
		Minecraft client = Minecraft.getInstance();
		long startedAt = System.nanoTime();

		RenderShape shape = state.getRenderShape();
		if (shape == RenderShape.INVISIBLE) {
			throw new IOException("该方块不可见（没有可捕获的模型）");
		}

		BlockRenderDispatcher dispatcher = client.getBlockRenderer();
		BakedModel model = dispatcher.getBlockModel(state);
		if (model.isCustomRenderer() || shape == RenderShape.ENTITYBLOCK_ANIMATED) {
			throw new IOException("该方块由方块实体渲染（箱子 / 告示牌 / 旗帜等），暂不支持捕获");
		}

		List<Quad> quads = new ArrayList<>();
		SpriteSampler sampler = new SpriteSampler();
		Set<String> unexpectedUvs = new LinkedHashSet<>();
		collectQuads(state, model, sampler, unexpectedUvs, quads);

		if (quads.isEmpty()) {
			throw new IOException("该方块没有可捕获的模型面（可能是空气或纯方块实体方块）");
		}

		VoxelGrid grid = BlockVoxelizer.voxelize(quads, sampler, UNITS_PER_BLOCK);
		int colored = grid.coloredVoxels();
		if (colored == 0) {
			throw new IOException("该方块的贴图无法读取，捕获为空");
		}

		String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		String blockStateString = blockStateString(state);

		McvoxHeader header = new McvoxHeader(
				McvoxWriter.FORMAT_VERSION,
				blockId,
				state.getBlock().getName().getString(),
				BlockCapture.mcVersion(),
				BlockCapture.loader(),
				UNITS_PER_BLOCK,
				new int[] { grid.sizeX, grid.sizeY, grid.sizeZ },
				true,
				0,
				DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
				"block",
				blockStateString);

		File directory = new File(client.gameDirectory, BlockCapture.OUTPUT_DIR);
		Path path = McvoxWriter.write(directory.toPath(), header, grid, true);

		double millis = (System.nanoTime() - startedAt) / 1_000_000.0D;
		logSummary(blockId, blockStateString, quads.size(), unexpectedUvs, grid, path, millis);

		return new Result(
				path,
				blockId,
				blockStateString,
				quads.size(),
				grid.nonEmptyVoxels(),
				colored,
				grid.sizeX,
				grid.sizeY,
				grid.sizeZ,
				millis);
	}

	/**
	 * Collect every quad of the model in vanilla's own draw order (six directions,
	 * then the general bucket), numbering them so a later quad outranks an earlier
	 * one. That numbering decides coplanar conflicts: {@code grass_block}'s green
	 * side cube and {@code oak_leaves}' overlay are drawn after their base, so they
	 * have to win the voxel.
	 *
	 * <p>NeoForge deprecates the three-argument {@code getQuads} in favour of a
	 * five-argument overload on {@code IBakedModelExtension} that also takes
	 * {@code ModelData} and a {@code RenderType}, but Fabric has only the
	 * three-argument form. Suppressing the warning is deliberate: a block capture
	 * wants the plain static model with no model data and no render-type variation,
	 * which is exactly what the deprecated overload returns on both loaders.
	 */
	@SuppressWarnings("deprecation")
	private static void collectQuads(
			BlockState state,
			BakedModel model,
			SpriteSampler sampler,
			Set<String> unexpectedUvs,
			List<Quad> quads) {
		RandomSource random = RandomSource.create(RANDOM_SEED);
		int layer = 0;
		for (Direction direction : collectDirections()) {
			for (BakedQuad quad : model.getQuads(state, direction, random)) {
				Quad converted = toQuad(state, quad, layer++, unexpectedUvs);
				if (converted != null) {
					quads.add(converted);
				}
			}
		}
	}

	/** Six faces plus the null direction that carries general/detail quads. */
	private static List<Direction> collectDirections() {
		List<Direction> directions = new ArrayList<>(7);
		for (Direction direction : Direction.values()) {
			directions.add(direction);
		}
		// Vanilla's general-quad bucket, drawn last.
		directions.add(null);
		return directions;
	}

	private static Quad toQuad(
			BlockState state,
			BakedQuad quad,
			int layer,
			Set<String> unexpectedUvs) {
		int[] vertices = quad.getVertices();
		if (vertices.length < VERTEX_STRIDE * 4) {
			return null;
		}

		float[] u = new float[4];
		float[] v = new float[4];
		Vertex[] out = new Vertex[4];
		for (int i = 0; i < 4; i++) {
			int base = i * VERTEX_STRIDE;
			Vertex vertex = new Vertex();
			vertex.x = Float.intBitsToFloat(vertices[base + OFFSET_POSITION]);
			vertex.y = Float.intBitsToFloat(vertices[base + OFFSET_POSITION + 1]);
			vertex.z = Float.intBitsToFloat(vertices[base + OFFSET_POSITION + 2]);
			u[i] = Float.intBitsToFloat(vertices[base + OFFSET_UV]);
			v[i] = Float.intBitsToFloat(vertices[base + OFFSET_UV + 1]);
			out[i] = vertex;
		}

		var sprite = quad.getSprite();
		if (sprite == null) {
			return null;
		}
		normaliseUvs(sprite, u, v, out, unexpectedUvs);

		return new Quad(out[0], out[1], out[2], out[3], sprite, tintFor(state, quad.getTintIndex()), layer, quad.getDirection());
	}

	/**
	 * Rewrite a quad's four UVs into sprite-local {@code [0,1]} coordinates.
	 *
	 * <p>Model files disagree about which space a quad's UVs are in: some are
	 * already normalised into the sprite's atlas rectangle, some use the vanilla
	 * 0..16 sprite grid, and a few authors write plain 0..1. The whole quad is
	 * classified once (its four corners either all fall inside the sprite's
	 * atlas rectangle or none do), which keeps the mapping affine and therefore
	 * safe to interpolate.
	 */
	private static void normaliseUvs(
			TextureAtlasSprite sprite,
			float[] u,
			float[] v,
			Vertex[] out,
			Set<String> unexpectedUvs) {
		float u0 = sprite.getU0();
		float u1 = sprite.getU1();
		float v0 = sprite.getV0();
		float v1 = sprite.getV1();
		float epsilon = 1.0e-3F;

		boolean atlasSpace = u1 - u0 > 0.0F && v1 - v0 > 0.0F;
		for (int i = 0; i < 4 && atlasSpace; i++) {
			atlasSpace = u[i] >= u0 - epsilon && u[i] <= u1 + epsilon
					&& v[i] >= v0 - epsilon && v[i] <= v1 + epsilon;
		}

		float maxUv = 0.0F;
		for (int i = 0; i < 4; i++) {
			maxUv = Math.max(maxUv, Math.max(Math.abs(u[i]), Math.abs(v[i])));
		}
		if (!atlasSpace && maxUv > 2.0F) {
			// Vanilla FaceBakery writes 0..16 sprite coordinates.
			for (int i = 0; i < 4; i++) {
				u[i] /= 16.0F;
				v[i] /= 16.0F;
			}
		} else if (atlasSpace) {
			float du = u1 - u0;
			float dv = v1 - v0;
			for (int i = 0; i < 4; i++) {
				u[i] = (u[i] - u0) / du;
				v[i] = (v[i] - v0) / dv;
			}
		}

		boolean outOfRange = false;
		for (int i = 0; i < 4; i++) {
			if (u[i] < -epsilon || u[i] > 1.0F + epsilon || v[i] < -epsilon || v[i] > 1.0F + epsilon) {
				outOfRange = true;
			}
			out[i].u = u[i];
			out[i].v = v[i];
		}
		if (outOfRange && unexpectedUvs.add(sprite.contents().name().toString())) {
			BlockCapture.LOGGER.warn("Sprite {} produced UVs outside 0..1 after normalisation; the capture may be skewed", sprite.contents().name());
		}
	}

	/**
	 * The block's biome/block tint, so grass, leaves and vines keep their green
	 * instead of coming out grey. Resolved with a null level, which yields the
	 * registered default colour.
	 */
	private static int tintFor(BlockState state, int tintIndex) {
		if (tintIndex < 0) {
			return 0xFFFFFF;
		}
		try {
			int color = Minecraft.getInstance().getBlockColors().getColor(state, null, null, tintIndex);
			return color == -1 ? 0xFFFFFF : color & 0xFFFFFF;
		} catch (Throwable throwable) {
			// A modded colour resolver may not tolerate a null level/position.
			BlockCapture.LOGGER.warn("Block colour resolver failed for {} tint {}; falling back to white", state, tintIndex);
			return 0xFFFFFF;
		}
	}

	private static String blockStateString(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		if (state.getValues().isEmpty()) {
			return id;
		}
		StringBuilder builder = new StringBuilder(id).append('[');
		boolean first = true;
		for (var entry : state.getValues().entrySet()) {
			if (!first) {
				builder.append(',');
			}
			first = false;
			builder.append(entry.getKey().getName()).append('=').append(entry.getValue().toString().toLowerCase(java.util.Locale.ROOT));
		}
		return builder.append(']').toString();
	}

	private static void logSummary(
			String blockId,
			String blockState,
			int quadCount,
			Set<String> unexpectedUvs,
			VoxelGrid grid,
			Path path,
			double millis) {
		BlockCapture.LOGGER.info("capture {}: state={} quads={} grid={}x{}x{} voxels={} colored={} time={}ms file={}",
				blockId,
				blockState,
				quadCount,
				grid.sizeX, grid.sizeY, grid.sizeZ,
				grid.nonEmptyVoxels(),
				grid.coloredVoxels(),
				String.format("%.1f", millis),
				path.getFileName());
		if (!unexpectedUvs.isEmpty()) {
			BlockCapture.LOGGER.warn("capture {}: sprites with unexpected UVs -> {}", blockId, unexpectedUvs);
		}
	}
}
