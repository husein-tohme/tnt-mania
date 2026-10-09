package net.husein.tntmania.mixin;

import net.husein.tntmania.ModTntBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fire that burns a block away lights normal TNT. The game only knows its own TNT there, so this does the same
 * for the mod's TNT: it remembers what stood at the spot before the fire took it, and lights it if it was one of ours.
 */
@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Unique
    private BlockState tntmania$burning;

    @Inject(method = "checkBurnOut", at = @At("HEAD"))
    private void tntmania$before(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        tntmania$burning = level.getBlockState(pos);
    }

    @Inject(method = "checkBurnOut", at = @At("RETURN"))
    private void tntmania$after(Level level, BlockPos pos, int chance, RandomSource random, int age, CallbackInfo ci) {
        BlockState was = tntmania$burning;
        tntmania$burning = null;
        if (was != null && was.getBlock() instanceof ModTntBlock && !level.getBlockState(pos).is(was.getBlock())) {
            ModTntBlock.prime(level, pos, was, null, false);
        }
    }
}
