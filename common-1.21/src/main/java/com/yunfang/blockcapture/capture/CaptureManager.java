package com.yunfang.blockcapture.capture;

import java.io.IOException;

import com.yunfang.blockcapture.BlockCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * A tiny one-slot request queue. Keybinds and commands run on whatever thread
 * fires them, so they only record what to capture; the client tick drains the
 * request on the render thread, where the model manager and the block atlas are
 * safe to touch.
 */
public final class CaptureManager {
	private static volatile BlockState pendingState;
	private static volatile boolean pendingLookedAt;

	private CaptureManager() {
	}

	/** Capture the block under the crosshair, keeping its real block state. */
	public static void requestLookedAt() {
		pendingLookedAt = true;
	}

	/** Capture an exact block state (a command's default state, for example). */
	public static void requestState(BlockState state) {
		pendingState = state;
	}

	public static void processPending() {
		BlockState direct = pendingState;
		boolean lookedAt = pendingLookedAt;
		pendingState = null;
		pendingLookedAt = false;
		if (direct == null && !lookedAt) {
			return;
		}

		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) {
			return;
		}

		BlockState state = direct;
		if (state == null) {
			state = lookedAtState(client);
			if (state == null) {
				feedback("§c没有瞄准任何方块");
				return;
			}
		}

		try {
			BlockCaptureService.Result result = BlockCaptureService.capture(state);
			feedback(String.format("已捕获 %s（%s，%dx%dx%d，%d 个体素）→ %s",
					result.blockId(),
					modeLabel(result.captureMode()),
					result.sizeX(), result.sizeY(), result.sizeZ(),
					result.voxels(),
					result.path().getFileName()));
		} catch (IOException exception) {
			feedback("§c捕获 " + id(state) + " 失败：" + exception.getMessage());
			BlockCapture.LOGGER.warn("capture {} failed", id(state), exception);
		} catch (RuntimeException exception) {
			feedback("§c捕获 " + id(state) + " 出错：" + exception);
			BlockCapture.LOGGER.error("capture {} threw", id(state), exception);
		}
	}

	private static BlockState lookedAtState(Minecraft client) {
		HitResult hit = client.hitResult;
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		BlockPos pos = blockHit.getBlockPos();
		if (client.level == null || !client.level.isLoaded(pos)) {
			return null;
		}
		return client.level.getBlockState(pos);
	}

	private static String id(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
	/** Plain-language name for how the geometry was obtained. */
	private static String modeLabel(String captureMode) {
		switch (captureMode) {
			case "model":
				return "模型";
			case "blockEntity":
				return "方块实体渲染";
			default:
				return "模型 + 方块实体渲染";
		}
	}

	private static void feedback(String message) {
		Minecraft client = Minecraft.getInstance();
		if (client.player != null) {
			client.player.displayClientMessage(Component.literal(message), false);
		}
		BlockCapture.LOGGER.info(message);
	}
}
