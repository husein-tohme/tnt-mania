package net.husein.tntmania;

import net.husein.tntmania.catalog.TntType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.BiConsumer;

/**
 * One of the 250 TNT blocks. It is lit the same ways as the game's own TNT (flint and steel, fire charge,
 * redstone, a burning arrow, fire, another blast). Lit, it becomes the game's own falling TNT that carries this
 * block's look; what happens when the fuse runs out is decided in Effects.
 */
public class ModTntBlock extends Block {
    /** The way the player looked when placing it. Tunnels, bridges and the like go this way. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    public final TntType type;

    public ModTntBlock(TntType type, BlockBehaviour.Properties properties) {
        super(properties);
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    /**
     * Lights the TNT that stood at this spot. The caller takes the block away.
     *
     * @param chain true when another blast set it off: then the fuse is short and random, as with normal TNT
     * @return the lit TNT, or null when the world has TNT switched off
     */
    public static PrimedTnt prime(Level level, BlockPos pos, BlockState state, LivingEntity owner, boolean chain) {
        if (!(level instanceof ServerLevel server) || !server.getGameRules().get(GameRules.TNT_EXPLODES)) return null;
        if (!(state.getBlock() instanceof ModTntBlock block)) return null;
        int fuse = block.type.fuse();
        if (chain) fuse = Math.max(1, level.getRandom().nextInt(Math.max(1, fuse / 4)) + fuse / 8);
        PrimedTnt primed = lit(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, state, owner, fuse);
        Flight.launch(primed, block.type, state);
        level.addFreshEntity(primed);
        if (!chain) {
            level.playSound(null, primed.getX(), primed.getY(), primed.getZ(), SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0f, 1.0f);
            level.gameEvent(owner, GameEvent.PRIME_FUSE, pos);
        }
        return primed;
    }

    /** A lit TNT of this kind that is not in the world yet. */
    public static PrimedTnt lit(Level level, double x, double y, double z, BlockState state, LivingEntity owner, int fuse) {
        PrimedTnt primed = new PrimedTnt(level, x, y, z, owner);
        primed.setBlockState(state);
        primed.setFuse(fuse);
        return primed;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(state.getBlock()) && level.hasNeighborSignal(pos) && prime(level, pos, state, null, false) != null) {
            level.removeBlock(pos, false);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation, boolean movedByPiston) {
        if (level.hasNeighborSignal(pos) && prime(level, pos, state, null, false) != null) {
            level.removeBlock(pos, false);
        }
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.FLINT_AND_STEEL) && !stack.is(Items.FIRE_CHARGE)) {
            return super.useItemOn(stack, state, level, pos, player, hand, hit);
        }
        if (level instanceof ServerLevel) {
            if (prime(level, pos, state, player, false) == null) return InteractionResult.PASS;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_IMMEDIATE);
            if (stack.is(Items.FLINT_AND_STEEL)) stack.hurtAndBreak(1, player, hand);
            else stack.consume(1, player);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected void onProjectileHit(Level level, BlockState state, BlockHitResult hit, Projectile projectile) {
        if (level instanceof ServerLevel server) {
            BlockPos pos = hit.getBlockPos();
            if (projectile.isOnFire() && projectile.mayInteract(server, pos)) {
                Entity owner = projectile.getOwner();
                if (prime(level, pos, state, owner instanceof LivingEntity living ? living : null, false) != null) {
                    level.removeBlock(pos, false);
                }
            }
        }
    }

    /** The block as it stood when a blast reached it, so the way it faced is not lost. */
    private BlockState hit;

    @Override
    protected void onExplosionHit(BlockState state, ServerLevel level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> drops) {
        hit = state;
        try {
            super.onExplosionHit(state, level, pos, explosion, drops);
        } finally {
            hit = null;
        }
    }

    /** Another blast took this block: it goes off too, a moment later. The block is already gone here. */
    @Override
    public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion) {
        prime(level, pos, hit != null ? hit : defaultBlockState(), explosion.getIndirectSourceEntity(), true);
    }

    @Override
    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }
}
