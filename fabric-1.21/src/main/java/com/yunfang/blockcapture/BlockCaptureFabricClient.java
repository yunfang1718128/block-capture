package com.yunfang.blockcapture;

import com.mojang.blaze3d.platform.InputConstants;
import com.yunfang.blockcapture.capture.CaptureManager;
import com.yunfang.blockcapture.command.CaptureBlockCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Fabric entry point: one keybind plus the client command, and a tick that
 * drains whatever {@link CaptureManager} was asked to capture.
 */
public class BlockCaptureFabricClient implements ClientModInitializer {
	private static final KeyMapping CAPTURE_KEY = new KeyMapping(
			"key.block-capture.capture",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_K,
			"category.block-capture");

	@Override
	public void onInitializeClient() {
		BlockCapture.setPlatform("1.21.1", "fabric");

		KeyBindingHelper.registerKeyBinding(CAPTURE_KEY);
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> CaptureBlockCommand.register(dispatcher));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (CAPTURE_KEY.consumeClick()) {
				CaptureManager.requestLookedAt();
			}
			CaptureManager.processPending();
		});
	}
}
