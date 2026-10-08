package com.yunfang.blockcapture.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the private {@code state} field of {@code RenderType$CompositeRenderType}
 * so capture can recover the texture bound to a render layer.
 */
@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeRenderTypeAccessor {
	@Accessor("state")
	RenderType.CompositeState getState();
}
