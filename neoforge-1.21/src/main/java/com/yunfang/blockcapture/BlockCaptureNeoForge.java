package com.yunfang.blockcapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.blockcapture.capture.CaptureManager;
import com.yunfang.blockcapture.command.CaptureBlockCommand;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * NeoForge entry point: one keybind plus the client command, and a tick that
 * drains whatever {@link CaptureManager} was asked to capture.
 */
@Mod(value = BlockCaptureNeoForge.MOD_ID, dist = Dist.CLIENT)
public class BlockCaptureNeoForge {
	/** NeoForge mod ids must be lowercase letters, digits and underscores only. */
	public static final String MOD_ID = "block_capture";

	private static final KeyMapping CAPTURE_KEY = new KeyMapping(
			"key.block-capture.capture",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"category.block-capture");

	public BlockCaptureNeoForge(IEventBus modBus, ModContainer container) {
		BlockCapture.setPlatform("1.21.1", "neoforge");

		modBus.addListener(this::registerKeyMappings);
		NeoForge.EVENT_BUS.addListener(this::registerClientCommands);
		NeoForge.EVENT_BUS.addListener(this::onClientTick);
	}

	private void registerKeyMappings(RegisterKeyMappingsEvent event) {
		event.register(CAPTURE_KEY);
	}

	private void registerClientCommands(RegisterClientCommandsEvent event) {
		CaptureBlockCommand.register(event.getDispatcher());
	}

	private void onClientTick(ClientTickEvent.Post event) {
		while (CAPTURE_KEY.consumeClick()) {
			CaptureManager.requestLookedAt();
		}
		CaptureManager.processPending();
	}
}
