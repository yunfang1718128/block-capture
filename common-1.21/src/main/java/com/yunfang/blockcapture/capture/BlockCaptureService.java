package com.yunfang.blockcapture.capture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.yunfang.blockcapture.BlockCapture;
import com.yunfang.blockcapture.mcvox.McvoxHeader;
import com.yunfang.blockcapture.mcvox.McvoxWriter;
import com.yunfang.blockcapture.texture.TextureSampler;
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
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Drives one block state through the capture pipeline and writes the `.mcvox`.
 *
 * <p>Two quad sources feed one voxeliser:
 *
 * <ol>
 *   <li>the baked model, read straight off {@link BakedModel#getQuads} — no
 *       rendering, no mixin, for the vast majority of blocks;</li>
 *   <li>the block entity renderer, for the ~128 vanilla states whose model is a
 *       texture-only stub (chests, signs, banners, beds, shulker boxes, skulls)
 *       and for modded blocks that draw themselves the same way.</li>
 * </ol>
 *
 * <p>Both produce {@link Quad}s whose UVs are normalised across the texture the
 * layer binds, so a single {@link TextureSampler} covers the block atlas, the
 * dedicated block-entity atlases and standalone entity textures.
 */
public final class BlockCaptureService {
	public static final int UNITS_PER_BLOCK = 16;
	/** Fixed seed so the same block always produces the same capture. */
	private static final long RANDOM_SEED = 42L;
	/** How the quads were obtained; recorded in the header for diagnostics. */
	private static final String MODE_MODEL = "model";
	private static final String MODE_RENDERER = "blockEntity";
	private static final String MODE_BOTH = "model+blockEntity";

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
			String captureMode,
			int quads,
			int voxels,
			int coloredVoxels,
			int textures,
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
		if (model.isCustomRenderer()) {
			throw new IOException("该方块要求自定义渲染，暂不支持捕获");
		}

		List<Quad> quads = new ArrayList<>();
		int modelQuads = collectModelQuads(state, model, quads);
		int firstRendererLayer = quads.size();
		BlockEntityCaptureService.Outcome renderer =
				BlockEntityCaptureService.capture(state, quads, firstRendererLayer);

		if (quads.isEmpty()) {
			throw new IOException("该方块没有可捕获的模型面（可能是空气、纯特效方块，或渲染器未输出几何）");
		}

		String captureMode = modelQuads > 0 && renderer.rendered()
				? MODE_BOTH
				: (renderer.rendered() ? MODE_RENDERER : MODE_MODEL);

		TextureSampler sampler = new TextureSampler();
		VoxelGrid grid = BlockVoxelizer.voxelize(quads, sampler, UNITS_PER_BLOCK, supersampleFactor(modelQuads, model, state));
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
				blockStateString,
				captureMode);

		File directory = new File(client.gameDirectory, BlockCapture.OUTPUT_DIR);
		Path path = McvoxWriter.write(directory.toPath(), header, grid, true);

		double millis = (System.nanoTime() - startedAt) / 1_000_000.0D;
		logSummary(blockId, blockStateString, captureMode, quads.size(), modelQuads, renderer, sampler, grid, path, millis);

		return new Result(
				path,
				blockId,
				blockStateString,
				captureMode,
				quads.size(),
				grid.nonEmptyVoxels(),
				colored,
				sampler.textureCount(),
				grid.sizeX,
				grid.sizeY,
				grid.sizeZ,
				millis);
	}

	/**
	 * Collect the baked model's quads in vanilla's own draw order (six
	 * directions, then the general bucket), numbering them so a later quad
	 * outranks an earlier one. That numbering decides coplanar conflicts:
	 * {@code grass_block}'s green side cube and {@code oak_leaves}' overlay are
	 * drawn after their base, so they have to win the voxel.
	 *
	 * <p>NeoForge deprecates the three-argument {@code getQuads} in favour of a
	 * five-argument overload on {@code IBakedModelExtension} that also takes
	 * {@code ModelData} and a {@code RenderType}, but Fabric has only the
	 * three-argument form. Suppressing the warning is deliberate: a block capture
	 * wants the plain static model with no model data and no render-type variation,
	 * which is exactly what the deprecated overload returns on both loaders.
	 *
	 * @return how many quads the model contributed
	 */
	@SuppressWarnings("deprecation")
	private static int collectModelQuads(BlockState state, BakedModel model, List<Quad> into) {
		RandomSource random = RandomSource.create(RANDOM_SEED);
		int before = into.size();
		for (Direction direction : collectDirections()) {
			for (BakedQuad quad : model.getQuads(state, direction, random)) {
				Quad converted = toQuad(state, quad, into.size());
				if (converted != null) {
					into.add(converted);
				}
			}
		}
		return into.size() - before;
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

	/**
	 * Convert a baked quad, leaving its UVs untouched: {@code FaceBakery} already
	 * wrote them mapped into the block atlas, and vanilla's own
	 * {@code putBulkData} passes them straight to {@code setUv}, so there is no
	 * UV space to guess at.
	 */
	private static Quad toQuad(BlockState state, BakedQuad quad, int layer) {
		int[] vertices = quad.getVertices();
		if (vertices.length < VERTEX_STRIDE * 4) {
			return null;
		}
		TextureAtlasSprite sprite = quad.getSprite();
		if (sprite == null) {
			return null;
		}

		int tint = tintFor(state, quad.getTintIndex());
		Vertex[] out = new Vertex[4];
		for (int i = 0; i < 4; i++) {
			int base = i * VERTEX_STRIDE;
			Vertex vertex = new Vertex();
			vertex.x = Float.intBitsToFloat(vertices[base + OFFSET_POSITION]);
			vertex.y = Float.intBitsToFloat(vertices[base + OFFSET_POSITION + 1]);
			vertex.z = Float.intBitsToFloat(vertices[base + OFFSET_POSITION + 2]);
			vertex.u = Float.intBitsToFloat(vertices[base + OFFSET_UV]);
			vertex.v = Float.intBitsToFloat(vertices[base + OFFSET_UV + 1]);
			vertex.setPackedTint(tint);
			out[i] = vertex;
		}
		return new Quad(out[0], out[1], out[2], out[3], InventoryMenu.BLOCK_ATLAS, layer, quad.getDirection());
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

	/**
	 * Match the voxel pitch to the texture pitch so one texel owns one voxel: a
	 * 16&times;16 sprite on one block face gives a 16&sup3; grid, a 32&times;32
	 * resource-pack texture gives 32&sup3; so its detail survives, capped at
	 * {@link BlockVoxelizer#MAX_SUPERSAMPLE}.
	 *
	 * <p>Only applies when the block has a baked model to measure. A
	 * renderer-only block has whole-texture UVs (a chest atlas holds all six
	 * faces), so there is no per-face texel pitch to match and the grid stays at
	 * Minecraft's own model resolution.
	 */
	private static int supersampleFactor(int modelQuads, BakedModel model, BlockState state) {
		if (modelQuads == 0) {
			return 1;
		}
		int highest = 0;
		RandomSource random = RandomSource.create(RANDOM_SEED);
		for (Direction direction : collectDirections()) {
			for (BakedQuad quad : quadsOf(model, state, direction, random)) {
				TextureAtlasSprite sprite = quad.getSprite();
				if (sprite == null) {
					continue;
				}
				highest = Math.max(highest, sprite.contents().width());
				highest = Math.max(highest, sprite.contents().height());
			}
		}
		if (highest <= UNITS_PER_BLOCK) {
			return 1;
		}
		return Math.min(BlockVoxelizer.MAX_SUPERSAMPLE, (int) Math.ceil(highest / (double) UNITS_PER_BLOCK));
	}

	@SuppressWarnings("deprecation")
	private static List<BakedQuad> quadsOf(BakedModel model, BlockState state, Direction direction, RandomSource random) {
		return model.getQuads(state, direction, random);
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
			builder.append(entry.getKey().getName()).append('=')
					.append(entry.getValue().toString().toLowerCase(Locale.ROOT));
		}
		return builder.append(']').toString();
	}

	private static void logSummary(
			String blockId,
			String blockState,
			String captureMode,
			int quadCount,
			int modelQuads,
			BlockEntityCaptureService.Outcome renderer,
			TextureSampler sampler,
			VoxelGrid grid,
			Path path,
			double millis) {
		BlockCapture.LOGGER.info(
				"capture {}: mode={} state={} quads={} (model={}, renderer={}{}) grid={}x{}x{} voxels={} colored={} textures={} time={}ms file={}",
				blockId,
				captureMode,
				blockState,
				quadCount,
				modelQuads,
				renderer.quads(),
				renderer.rendered() ? "" : " (" + renderer.reason() + ")",
				grid.sizeX, grid.sizeY, grid.sizeZ,
				grid.nonEmptyVoxels(),
				grid.coloredVoxels(),
				sampler.textureCount(),
				String.format("%.1f", millis),
				path.getFileName());
	}
}
