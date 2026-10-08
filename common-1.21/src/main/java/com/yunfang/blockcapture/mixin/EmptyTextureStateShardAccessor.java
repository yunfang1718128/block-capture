package com.yunfang.blockcapture.mixin;

import java.util.Optional;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invokes the protected {@code cutoutTexture()} accessor on a texture state shard. */
@Mixin(targets = "net.minecraft.client.renderer.RenderStateShard$EmptyTextureStateShard")
public interface EmptyTextureStateShardAccessor {
	@Invoker("cutoutTexture")
	Optional<ResourceLocation> invokeCutoutTexture();
}
