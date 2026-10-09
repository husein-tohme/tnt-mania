package net.husein.tntmania;

import net.husein.tntmania.catalog.Kind;
import net.husein.tntmania.catalog.TntType;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The TNTs that move while their fuse burns: the balloon, the rocket, the missile and the one that chases monsters. */
public final class Flight {
    private static final double FLOAT_UP = 0.12, ROCKET_UP = 1.0, MISSILE = 0.9, CHASE = 0.32;

    private Flight() {
    }

    /** The first push, given when the TNT is lit. */
    static void launch(PrimedTnt tnt, TntType type, BlockState state) {
        if (type.kind() != Kind.FLY) return;
        switch (type.what()) {
            case "float" -> {
                tnt.setNoGravity(true);
                tnt.setDeltaMovement(0, FLOAT_UP, 0);
            }
            case "rocket" -> {
                tnt.setNoGravity(true);
                tnt.setDeltaMovement(0, ROCKET_UP, 0);
            }
            case "missile" -> {
                Direction way = state.getValue(ModTntBlock.FACING);
                tnt.setNoGravity(true);
                tnt.setDeltaMovement(way.getStepX() * MISSILE, 0, way.getStepZ() * MISSILE);
            }
            default -> {
            }
        }
    }

    /** Called every tick while the fuse burns, on the server and in the player's game, so both see the same flight. */
    public static void steer(PrimedTnt tnt, TntType type) {
        boolean server = !tnt.level().isClientSide();
        switch (type.what()) {
            case "float" -> tnt.setDeltaMovement(tnt.getDeltaMovement().x * 0.9, FLOAT_UP, tnt.getDeltaMovement().z * 0.9);
            case "rocket" -> tnt.setDeltaMovement(0, ROCKET_UP, 0);
            case "missile" -> {
                Direction way = tnt.getBlockState().getValue(ModTntBlock.FACING);
                // it hit something on its last move: it goes off now
                if (server && tnt.horizontalCollision && tnt.getFuse() > 2) tnt.setFuse(2);
                tnt.setDeltaMovement(way.getStepX() * MISSILE, 0, way.getStepZ() * MISSILE);
            }
            case "homing" -> {
                if (!server) return;
                Mob target = null;
                double best = 24 * 24;
                for (Mob mob : tnt.level().getEntitiesOfClass(Mob.class, tnt.getBoundingBox().inflate(24), m -> m instanceof Enemy && m.isAlive())) {
                    double far = mob.distanceToSqr(tnt);
                    if (far < best) {
                        best = far;
                        target = mob;
                    }
                }
                if (target == null) return;
                if (best < 2.5 * 2.5) {
                    if (tnt.getFuse() > 2) tnt.setFuse(2);
                    return;
                }
                Vec3 to = target.position().subtract(tnt.position());
                double flat = Math.max(0.001, Math.sqrt(to.x * to.x + to.z * to.z));
                boolean stuck = tnt.horizontalCollision && tnt.onGround();
                tnt.setDeltaMovement(to.x / flat * CHASE, stuck ? 0.45 : tnt.getDeltaMovement().y, to.z / flat * CHASE);
                tnt.hurtMarked = true;
            }
            default -> {
            }
        }
    }
}
