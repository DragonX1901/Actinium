package net.actinium.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import net.actinium.gl.GlLimits;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(value = GlStateManager.class, priority = 1500)
public class GlStateManagerLimitsMixin {

    /**
     * @author DragonX1901
     * @reason answer integer state queries from the device instead of zero
     */
    @Overwrite(remap = false)
    public static int _getInteger(int pname) {
        return GlLimits.get(pname);
    }
}
