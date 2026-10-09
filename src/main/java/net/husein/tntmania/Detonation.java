package net.husein.tntmania;

import net.husein.tntmania.catalog.TntType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.List;

/** One TNT going off: where, which way it faced, who lit it, and a count of what it did. */
public final class Detonation {
    /** Inside a big hole: nothing around needs to hear about the change. */
    static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    /** At the rim: the neighbours fix themselves (water flows in, torches drop, sand falls). */
    static final int EDGE = Block.UPDATE_CLIENTS;

    public final ServerLevel level;
    public final TntType type;
    public final BlockPos at;
    public final Vec3 center;
    public final Direction facing;
    public final LivingEntity owner;
    /** The lit TNT itself (already gone from the world), or null. */
    public final Entity source;
    public final RandomSource random;

    /** What it did, counted so the tests can see that every TNT really does something. */
    public int blocks, spawned, touched, actions;
    public Throwable error;
    final ArrayDeque<Jobs.Work> queue = new ArrayDeque<>();
    int timers;

    public Detonation(ServerLevel level, TntType type, BlockPos at, Vec3 center, Direction facing, LivingEntity owner, Entity source) {
        this.level = level;
        this.type = type;
        this.at = at.immutable();
        this.center = center;
        this.facing = facing;
        this.owner = owner;
        this.source = source;
        this.random = level.getRandom();
    }

    /** True when all of its work is finished. */
    public boolean done() {
        return queue.isEmpty() && timers == 0;
    }

    public int total() {
        return blocks + spawned + touched + actions;
    }

    /** What it is still busy with, for messages. */
    public String waiting() {
        return type.id() + " (" + queue.size() + " jobs, " + timers + " timers)";
    }

    void then(Jobs.Work work) {
        queue.add(work);
        Jobs.wake(this);
    }

    void later(int ticks, Runnable run) {
        Jobs.timer(this, ticks, run);
    }

    // ---- walking through blocks -------------------------------------------------------------------------------

    void scan(int dx0, int dy0, int dz0, int dx1, int dy1, int dz1, Jobs.Shape shape, Jobs.Visitor visitor, Runnable finish) {
        then(new Jobs.Scan(level, at, dx0, dy0, dz0, dx1, dy1, dz1, shape, visitor, finish));
    }

    void box(int dx0, int dy0, int dz0, int dx1, int dy1, int dz1, Jobs.Visitor visitor) {
        scan(dx0, dy0, dz0, dx1, dy1, dz1, null, visitor, null);
    }

    /** Every block within r of the TNT. */
    void sphere(double r, Jobs.Visitor visitor, Runnable finish) {
        int n = (int) Math.ceil(r);
        double r2 = r * r;
        scan(-n, -n, -n, n, n, n, (dx, dz, range) -> {
            double left = r2 - dx * dx - dz * dz;
            if (left < 0) return false;
            int h = (int) Math.sqrt(left);
            range[0] = -h;
            range[1] = h;
            return true;
        }, visitor, finish);
    }

    void sphere(double r, Jobs.Visitor visitor) {
        sphere(r, visitor, null);
    }

    /** Every block in a round area of radius r, from `down` below the TNT to `up` above it. */
    void disk(double r, int down, int up, Jobs.Visitor visitor) {
        int n = (int) Math.ceil(r);
        double r2 = r * r;
        scan(-n, -down, -n, n, up, n, (dx, dz, range) -> {
            if (dx * dx + dz * dz > r2) return false;
            range[0] = -down;
            range[1] = up;
            return true;
        }, visitor, null);
    }

    // ---- changing blocks ----------------------------------------------------------------------------------------

    /** Bedrock, barriers and the like can never be broken; air has nothing to break. */
    boolean removable(BlockState state, BlockPos pos) {
        return !state.isAir() && state.getDestroySpeed(level, pos) >= 0f;
    }

    /** Takes a block away. TNT that is in the way is lit, not lost. */
    void remove(BlockPos pos, BlockState state, int flags) {
        Block block = state.getBlock();
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
        blocks++;
        if (block instanceof ModTntBlock) {
            ModTntBlock.prime(level, pos, state, owner, true);
        } else if (block == Blocks.TNT) {
            PrimedTnt tnt = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, owner);
            tnt.setFuse(10 + random.nextInt(20));
            level.addFreshEntity(tnt);
        }
    }

    /** Puts a block. Bedrock and chests (anything with things inside) are left alone. */
    boolean place(BlockPos pos, BlockState state, int flags) {
        if (level.isOutsideBuildHeight(pos)) return false;
        BlockState old = level.getBlockState(pos);
        if (old == state) return false;
        if (!old.isAir() && (old.getDestroySpeed(level, pos) < 0f || old.hasBlockEntity())) return false;
        if (!level.setBlock(pos, state, flags)) return false;
        blocks++;
        return true;
    }

    boolean place(BlockPos pos, BlockState state) {
        return place(pos, state, EDGE);
    }

    /** The first free block above the ground at this column, or the TNT's own height when the ground is far off. */
    int surface(int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return at.getY();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return Math.abs(y - at.getY()) <= 10 ? y : at.getY();
    }

    // ---- mobs and players ---------------------------------------------------------------------------------------

    AABB around(double r) {
        return new AABB(center.x - r, center.y - r, center.z - r, center.x + r, center.y + r, center.z + r);
    }

    /** Everything alive within r of the TNT: mobs and players. */
    List<LivingEntity> living(double r) {
        double r2 = r * r;
        return level.getEntitiesOfClass(LivingEntity.class, around(r), e -> e.isAlive() && !e.isSpectator() && e.distanceToSqr(center) <= r2);
    }

    /** A random spot within r of the TNT, on the same level. */
    Vec3 spot(double r) {
        double angle = random.nextDouble() * Math.PI * 2, far = Math.sqrt(random.nextDouble()) * r;
        return new Vec3(center.x + Math.cos(angle) * far, center.y, center.z + Math.sin(angle) * far);
    }
}
