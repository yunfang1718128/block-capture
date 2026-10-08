package com.yunfang.blockcapture.mixin;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the package-private {@code textureState} held by a composite render state. */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeState")
public interface CompositeStateAccessor {
	@Accessor("textureState")
	RenderStateShard.EmptyTextureStateShard getTextureState();
}
