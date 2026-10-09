package net.husein.tntmania.mixin;

import net.husein.tntmania.Effects;
import net.husein.tntmania.Flight;
import net.husein.tntmania.ModTntBlock;
import net.husein.tntmania.catalog.Kind;
import net.minecraft.world.entity.item.PrimedTnt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A lit TNT of this mod is the game's own lit TNT wearing the mod block's look. When its fuse runs out, the mod's
 * effect runs in place of the normal blast. Normal TNT is not touched.
 */
@Mixin(PrimedTnt.class)
public abstract class PrimedTntMixin {
    @Inject(method = "explode", at = @At("HEAD"), cancellable = true)
    private void tntmania$explode(CallbackInfo ci) {
        PrimedTnt self = (PrimedTnt) (Object) this;
        if (self.getBlockState().getBlock() instanceof ModTntBlock block) {
            ci.cancel();
            Effects.detonate(self, block);
        }
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void tntmania$tick(CallbackInfo ci) {
        PrimedTnt self = (PrimedTnt) (Object) this;
        if (self.getBlockState().getBlock() instanceof ModTntBlock block && block.type.kind() == Kind.FLY) {
            Flight.steer(self, block.type);
        }
    }
}
