package net.husein.tntmania;

import net.husein.tntmania.catalog.Kind;
import net.husein.tntmania.catalog.TntType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fill mode: with it on, a player who places one TNT gets a whole area of that TNT. The area starts at the
 * placed block, goes forward (the way the player looks), is centred on it from side to side, and goes up.
 * Only empty space is filled: blocks that are already there, and mobs and players, are left alone.
 * In survival every extra TNT is taken from the player's inventory, and the filling stops when they run out.
 */
public final class Fill {
    private static final Map<UUID, FillSettings> BY_PLAYER = new ConcurrentHashMap<>();
    /** Stands in as the "kind of TNT" when the area is made of normal TNT. */
    private static final TntType NORMAL = new TntType(-1, "", "fill", "Fill", Kind.NOTHING, 0, 0, "", TntType.NORMAL_FUSE, 0, "FILL", List.of(), "");

    /** The newest filling, for the tests. */
    public static Detonation last;

    private Fill() {
    }

    public static void set(ServerPlayer player, FillSettings settings) {
        BY_PLAYER.put(player.getUUID(), settings.clamped());
    }

    public static FillSettings of(ServerPlayer player) {
        return BY_PLAYER.getOrDefault(player.getUUID(), FillSettings.START);
    }

    public static void forget(ServerPlayer player) {
        BY_PLAYER.remove(player.getUUID());
    }

    /** Called when someone has placed a TNT (one of the mod's or a normal one). */
    public static void placed(Level level, BlockPos pos, BlockState state, LivingEntity placer) {
        if (!(level instanceof ServerLevel server) || !(placer instanceof ServerPlayer player)) return;
        FillSettings settings = BY_PLAYER.get(player.getUUID());
        if (settings == null || !settings.on() || settings.blocks() <= 1) return;
        Direction forward = state.hasProperty(ModTntBlock.FACING) ? state.getValue(ModTntBlock.FACING) : player.getDirection();
        if (forward.getAxis().isVertical()) forward = Direction.NORTH;
        TntType type = state.getBlock() instanceof ModTntBlock block ? block.type : NORMAL;
        Detonation d = new Detonation(server, type, pos, Vec3.atBottomCenterOf(pos), forward, player, null);
        last = d;
        d.then(new Rows(d, player, state, settings));
    }

    /** Places the area row by row, nearest first, a bit every tick so a big area does not freeze the game. */
    private static final class Rows implements Jobs.Work {
        private final Detonation d;
        private final ServerPlayer player;
        private final BlockState state;
        private final FillSettings size;
        private final Direction right;
        private final Item item;
        private final int total;
        private int next;           // the block the player placed is somewhere in the first row: it is skipped as "not empty"

        Rows(Detonation d, ServerPlayer player, BlockState state, FillSettings size) {
            this.d = d;
            this.player = player;
            this.state = state;
            this.size = size;
            this.right = d.facing.getClockWise();
            this.item = state.getBlock().asItem();
            this.total = size.blocks();
        }

        @Override
        public boolean step(long deadline) {
            if (player.isRemoved() || player.hasDisconnected()) return true;
            boolean free = player.hasInfiniteMaterials();
            int perLayer = size.wide() * size.length(), n = 0;
            while (next < total) {
                int index = next++;
                int inLayer = index % perLayer;
                BlockPos pos = d.at.relative(d.facing, inLayer / size.wide())
                        .relative(right, inLayer % size.wide() - (size.wide() - 1) / 2).above(index / perLayer);
                if (fits(pos)) {
                    if (!free && !takeOne()) {
                        tell(true);
                        return true;
                    }
                    if (d.level.setBlock(pos, state, Detonation.EDGE)) d.blocks++;
                }
                if ((++n & 15) == 0 && System.nanoTime() >= deadline) return false;
            }
            tell(false);
            return true;
        }

        /** Empty space, inside the world, on loaded land, with nobody standing in it. */
        private boolean fits(BlockPos pos) {
            if (d.level.isOutsideBuildHeight(pos) || !d.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return false;
            BlockState old = d.level.getBlockState(pos);
            if (!old.isAir() && !(old.canBeReplaced() && old.getFluidState().isEmpty())) return false;
            return d.level.isUnobstructed(state, pos, CollisionContext.empty());
        }

        private boolean takeOne() {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(item)) {
                    stack.shrink(1);
                    return true;
                }
            }
            return false;
        }

        private void tell(boolean ranOut) {
            player.sendOverlayMessage(Component.literal("Placed " + (d.blocks + 1) + ": ").append(state.getBlock().getName())
                    .append(Component.literal(ranOut ? " (you ran out)" : "")));
        }
    }
}
