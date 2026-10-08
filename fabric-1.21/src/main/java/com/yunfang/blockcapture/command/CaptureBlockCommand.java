package com.yunfang.blockcapture.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunfang.blockcapture.capture.CaptureManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/** Registers {@code /captureblock [block]} on the client. */
public final class CaptureBlockCommand {
	private CaptureBlockCommand() {
	}

	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommandManager.literal("captureblock")
				.executes(context -> {
					CaptureManager.requestLookedAt();
					context.getSource().sendFeedback(Component.literal("正在捕获准星方块…"));
					return 1;
				})
				.then(ClientCommandManager.argument("block", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								BuiltInRegistries.BLOCK.keySet(), builder))
						.executes(context -> {
							ResourceLocation id = context.getArgument("block", ResourceLocation.class);
							Block block = BuiltInRegistries.BLOCK.get(id);
							if (block == null) {
								context.getSource().sendError(Component.literal("未知方块：" + id));
								return 0;
							}
							CaptureManager.requestState(block.defaultBlockState());
							context.getSource().sendFeedback(Component.literal("正在捕获 " + id + " …"));
							return 1;
						})));
	}
}
