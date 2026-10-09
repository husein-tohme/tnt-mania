package net.husein.tntmania.mixin;

import net.husein.tntmania.Fill;
import net.husein.tntmania.ModTntBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells fill mode when someone has placed a TNT: one of the mod's, or a normal one. Other blocks are not touched. */
@Mixin(Block.class)
public abstract class BlockPlacedMixin {
    @Inject(method = "setPlacedBy", at = @At("HEAD"))
    private void tntmania$placed(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack, CallbackInfo ci) {
        Block self = (Block) (Object) this;
        if (self instanceof ModTntBlock || self == Blocks.TNT) Fill.placed(level, pos, state, placer);
    }
}
