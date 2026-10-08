package com.yunfang.blockcapture.capture;

import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yunfang.blockcapture.BlockCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Captures the quads a {@code BlockEntityRenderer} draws for a block.
 *
 * <p>Many blocks have no baked geometry at all — a chest, a sign, a banner, a
 * bed, a shulker box, a skull — their blockstate model is a texture-only stub
 * and the whole appearance comes from the renderer. Roughly 128 vanilla block
 * states can only be captured this way.
 *
 * <p>The block entity is fabricated from the block state
 * ({@code EntityBlock.newBlockEntity}) and therefore carries no NBT, so the
 * capture is the block's <em>default</em> appearance: a closed chest, a plain
 * banner with no pattern, a sign with no text, a pot with no sherds. That is
 * deliberate — it is the reproducible form, and the alternative (reading a live
 * block entity out of the world) would make the same block capture differently
 * every time.
 */
public final class BlockEntityCaptureService {
	/**
	 * Vanilla renderers whose output is an animation or an effect rather than a
	 * shape, so voxelising them produces noise: the beacon's beam, a campfire's
	 * flame, the book on a lectern or enchanting table, a spawner's spinning mob
	 * (which would drag entity capture in), the animated vault and trial spawner,
	 * a structure block's wireframe, a brushable block's dust, the transient
	 * moving piston, and the end portal/gateway starfields.
	 *
	 * <p>Blocks in this list keep their baked model (their static part is still
	 * captured); only the renderer pass is skipped. Anything not listed —
	 * including modded block entities — is captured, which is the point of the
	 * mod.
	 */
	private static final Set<BlockEntityType<?>> ANIMATED_RENDERERS = Set.of(
			BlockEntityType.BEACON,
			BlockEntityType.CAMPFIRE,
			BlockEntityType.ENCHANTING_TABLE,
			BlockEntityType.LECTERN,
			BlockEntityType.MOB_SPAWNER,
			BlockEntityType.TRIAL_SPAWNER,
			BlockEntityType.VAULT,
			BlockEntityType.STRUCTURE_BLOCK,
			BlockEntityType.BRUSHABLE_BLOCK,
			BlockEntityType.PISTON,
			BlockEntityType.END_PORTAL,
			BlockEntityType.END_GATEWAY);

	private BlockEntityCaptureService() {
	}

	/** A renderer pass result, for the log line. */
	public record Outcome(boolean rendered, String reason, int quads) {
		static Outcome skipped(String reason) {
			return new Outcome(false, reason, 0);
		}
	}

	/**
	 * Render {@code state}'s block entity into {@code into}, advancing the layer
	 * counter so renderer quads outrank the baked model they sit on top of.
	 */
	public static Outcome capture(BlockState state, List<Quad> into, int firstLayer) {
		if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
			return Outcome.skipped("block has no block entity");
		}

		BlockEntity blockEntity;
		try {
			blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, state);
		} catch (Throwable throwable) {
			BlockCapture.LOGGER.warn("Could not create a block entity for {}", state, throwable);
			return Outcome.skipped("block entity construction failed");
		}
		if (blockEntity == null) {
			return Outcome.skipped("block entity is null");
		}

		BlockEntityType<?> type = blockEntity.getType();
		if (ANIMATED_RENDERERS.contains(type)) {
			return Outcome.skipped("renderer draws an animation or effect");
		}

		Minecraft client = Minecraft.getInstance();
		BlockEntityRenderDispatcher dispatcher = client.getBlockEntityRenderDispatcher();
		BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(blockEntity);
		if (renderer == null) {
			return Outcome.skipped("no block entity renderer registered");
		}

		CapturingMultiBufferSource buffers = new CapturingMultiBufferSource();
		// `BlockEntityRenderDispatcher#render` refuses a block entity with no level
		// (`hasLevel()`) and culls by camera distance, so drive the renderer
		// directly. Its own `setupAndRender` uses FULL_BRIGHT for a null level too.
		try {
			renderer.render(blockEntity, 0.0F, new PoseStack(), buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
		} catch (Throwable throwable) {
			BlockCapture.LOGGER.warn("Renderer for {} threw; capturing the baked model only",
					BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type), throwable);
			return Outcome.skipped("renderer threw");
		}
		buffers.flush();

		// Re-number onto the caller's layer sequence so the renderer's passes land
		// after the baked model's.
		List<Quad> rendered = buffers.quads();
		for (int i = 0; i < rendered.size(); i++) {
			Quad quad = rendered.get(i);
			into.add(new Quad(quad.v0(), quad.v1(), quad.v2(), quad.v3(), quad.texture(), firstLayer + i, null));
		}
		return new Outcome(true, "ok", rendered.size());
	}
}
