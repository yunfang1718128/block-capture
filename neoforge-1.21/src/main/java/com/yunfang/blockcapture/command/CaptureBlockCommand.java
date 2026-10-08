package com.yunfang.blockcapture.command;

import com.mojang.brigadier.CommandDispatcher;
import com.yunfang.blockcapture.capture.CaptureManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("captureblock")
				.executes(context -> {
					CaptureManager.requestLookedAt();
					context.getSource().sendSuccess(() -> Component.literal("正在捕获准星方块…"), false);
					return 1;
				})
				.then(Commands.argument("block", ResourceLocationArgument.id())
						.suggests((context, builder) -> SharedSuggestionProvider.suggestResource(
								BuiltInRegistries.BLOCK.keySet(), builder))
						.executes(context -> {
							ResourceLocation id = context.getArgument("block", ResourceLocation.class);
							Block block = BuiltInRegistries.BLOCK.get(id);
							if (block == null) {
								context.getSource().sendFailure(Component.literal("未知方块：" + id));
								return 0;
							}
							CaptureManager.requestState(block.defaultBlockState());
							context.getSource().sendSuccess(() -> Component.literal("正在捕获 " + id + " …"), false);
							return 1;
						})));
	}
}
