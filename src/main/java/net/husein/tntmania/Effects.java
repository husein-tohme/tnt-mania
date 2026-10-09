package net.husein.tntmania;

import it.unimi.dsi.fastutil.ints.IntList;
import net.husein.tntmania.catalog.Catalog;
import net.husein.tntmania.catalog.Kind;
import net.husein.tntmania.catalog.TntType;
import net.husein.tntmania.mixin.CreeperAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.husein.tntmania.Detonation.EDGE;
import static net.husein.tntmania.Detonation.QUIET;

/** What each kind of TNT does when its fuse runs out. The numbers and words come from the table. */
public final class Effects {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final String HOPS = "tntmania_hops_";
    /** Blocks this tough (obsidian, anvils, ancient debris, ender chests) survive the big blasts. Stone is 6. */
    private static final float BLAST_PROOF = 600f;
    private static final Identifier SCALE = Identifier.fromNamespaceAndPath(Catalog.MOD, "scale");
    private static final List<Direction> SIDES = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);

    /** The newest blast, for the tests. */
    public static Detonation last;

    private Effects() {
    }

    /** Called when a lit TNT of this mod runs out of fuse. */
    public static Detonation detonate(PrimedTnt primed, ModTntBlock block) {
        if (!(primed.level() instanceof ServerLevel level) || !level.getGameRules().get(GameRules.TNT_EXPLODES)) return null;
        BlockState state = primed.getBlockState();
        Vec3 center = new Vec3(primed.getX(), primed.getY(0.0625), primed.getZ());
        BlockPos at = BlockPos.containing(primed.getX(), primed.getY() + 0.3, primed.getZ());
        Detonation d = new Detonation(level, block.type, at, center, state.getValue(ModTntBlock.FACING), primed.getOwner(), primed);
        run(d, block.type);
        return d;
    }

    /** Sets a TNT off at a spot without a block or a fuse. The tests use this. */
    public static Detonation run(ServerLevel level, TntType type, BlockPos at, Direction facing, LivingEntity owner) {
        Vec3 center = new Vec3(at.getX() + 0.5, at.getY() + 0.0625, at.getZ() + 0.5);
        Detonation d = new Detonation(level, type, at, center, facing, owner, null);
        run(d, type);
        return d;
    }

    private static void run(Detonation d, TntType t) {
        last = d;
        try {
            apply(d, t);
        } catch (Throwable e) {
            Jobs.fail(d, e);
        }
    }

    private static void apply(Detonation d, TntType t) {
        ServerLevel level = d.level;
        int n = t.n();
        int toBottom = level.getMinY() - d.at.getY(), toTop = level.getMaxY() - d.at.getY();
        switch (t.kind()) {
            case BLAST -> blast(d, d.center, (float) t.size(), t.what().contains("fire"), !t.what().contains("noblocks"));
            case SPHERE -> bigBlast(d, t);
            case NOTHING -> {
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.0f, 0.8f);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, d.center.x, d.center.y + 0.5, d.center.z, 30, 0.3, 0.3, 0.3, 0.02);
                d.actions++;
            }
            case SOUND -> {
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.CREEPER_PRIMED, SoundSource.BLOCKS, 2.0f, 0.6f);
                d.later(15, () -> level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.AMBIENT_CAVE.value(), SoundSource.BLOCKS, 3.0f, 1.0f));
                d.later(35, () -> level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.WITHER_SPAWN, SoundSource.BLOCKS, 0.6f, 1.4f));
                level.sendParticles(ParticleTypes.NOTE, d.center.x, d.center.y + 1, d.center.z, 20, 1, 1, 1, 1);
                d.actions++;
            }
            case SELECT -> select(d, t);
            case SHAFT -> {
                pop(d);
                boolean up = t.what().equals("up"), both = t.what().equals("both");
                d.box(-n, up ? 0 : toBottom, -n, n, up || both ? toTop : 0, n,
                        (pos, dx, dy, dz) -> carve(d, pos, Math.abs(dx) == n || Math.abs(dz) == n ? EDGE : QUIET));
            }
            case TUNNEL -> {
                pop(d);
                for (Direction dir : t.what().equals("cross") ? SIDES : List.of(d.facing)) corridor(d, dir, n, 1, t.count(), 0, 2 * n);
            }
            case CHUNK -> {
                pop(d);
                int cx = d.at.getX() >> 4, cz = d.at.getZ() >> 4;
                int x0 = ((cx - n) << 4) - d.at.getX(), x1 = ((cx + n) << 4) + 15 - d.at.getX();
                int z0 = ((cz - n) << 4) - d.at.getZ(), z1 = ((cz + n) << 4) + 15 - d.at.getZ();
                d.box(x0, toBottom, z0, x1, toTop, z1,
                        (pos, dx, dy, dz) -> carve(d, pos, dx == x0 || dx == x1 || dz == z0 || dz == z1 ? EDGE : QUIET));
            }
            case CUBE -> {
                pop(d);
                d.box(-n, -n, -n, n, n, n, (pos, dx, dy, dz) -> carve(d, pos, EDGE));
            }
            case BOWL -> {
                pop(d);
                double r = t.size();
                int depth = t.count();
                boolean cone = t.what().equals("cone");
                d.scan(-n, -depth, -n, n, 8, n, (dx, dz, range) -> {
                    double far = Math.sqrt(dx * dx + dz * dz) / r;
                    if (far > 1) return false;
                    range[0] = -(int) Math.round(depth * (cone ? 1 - far : 1 - far * far));
                    range[1] = 8;
                    return true;
                }, (pos, dx, dy, dz) -> carve(d, pos, EDGE), null);
            }
            case FLATTEN -> {
                pop(d);
                d.disk(t.size(), 0, toTop, (pos, dx, dy, dz) -> carve(d, pos, EDGE));
            }
            case DISK -> {
                pop(d);
                d.disk(t.size(), t.count(), 0, (pos, dx, dy, dz) -> carve(d, pos, EDGE));
            }
            case RING -> {
                pop(d);
                double r = t.size();
                int depth = t.count();
                d.scan(-n - 1, -depth, -n - 1, n + 1, 3, n + 1, (dx, dz, range) -> {
                    double far = Math.sqrt(dx * dx + dz * dz);
                    if (far < r - 1.5 || far >= r + 0.5) return false;
                    range[0] = -depth;
                    range[1] = 3;
                    return true;
                }, (pos, dx, dy, dz) -> carve(d, pos, EDGE), null);
            }
            case STAIRS_DOWN -> {
                pop(d);
                Direction side = d.facing.getClockWise();
                for (int i = 1; i <= t.count(); i++) {
                    for (int w = -1; w <= 1; w++) {
                        for (int h = 0; h <= 3; h++) {
                            BlockPos pos = d.at.relative(d.facing, i).relative(side, w).above(h - i);
                            if (pos.getY() <= level.getMinY() || !level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
                            carve(d, pos, EDGE);
                        }
                    }
                }
            }
            case CHECKER -> {
                pop(d);
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    if (((pos.getX() + pos.getY() + pos.getZ()) & 1) == 0) carve(d, pos, EDGE);
                });
            }
            case SHELL -> {
                pop(d);
                double inner = (t.size() - 1) * (t.size() - 1);
                d.sphere(t.size() + 0.5, (pos, dx, dy, dz) -> {
                    if (dx * dx + dy * dy + dz * dz >= inner) carve(d, pos, EDGE);
                });
            }
            case TRENCH -> {
                pop(d);
                corridor(d, d.facing, n, 1, t.count(), -number(t.what(), 5), 3);
            }
            case BURROW -> {
                BlockPos.MutableBlockPos pos = d.at.mutable();
                for (int i = 0; i < t.count() && pos.getY() > level.getMinY() + 1; i++) {
                    BlockState below = level.getBlockState(pos.below());
                    if (!below.isAir() && !d.removable(below, pos.below())) break;
                    pos.move(Direction.DOWN);
                    if (!below.isAir()) d.remove(pos, below, EDGE);
                }
                blast(d, new Vec3(pos.getX() + 0.5, pos.getY() + 0.1, pos.getZ() + 0.5), (float) t.size(), false, true);
            }
            case DOME_CUT -> {
                pop(d);
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    if (dy >= 0) carve(d, pos, EDGE);
                });
            }
            case CONVERT -> convert(d, t);
            case SURFACE -> surface(d, t);
            case ORE -> ore(d, t);
            case FILL -> {
                pop(d);
                BlockState with = Ids.state(t.what());
                boolean liquid = with.getBlock() instanceof LiquidBlock;
                int chance = t.count() > 0 ? t.count() : 100;
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    BlockState s = level.getBlockState(pos);
                    if (s == with || !s.canBeReplaced()) return;
                    if (chance < 100 && d.random.nextInt(100) >= chance) return;
                    if (level.setBlock(pos, with, liquid ? Block.UPDATE_ALL : EDGE)) d.blocks++;
                });
            }
            case FLOOD -> {
                pop(d);
                BlockState water = Blocks.WATER.defaultBlockState();
                d.disk(t.size(), t.count(), 0, (pos, dx, dy, dz) -> {
                    BlockState s = level.getBlockState(pos);
                    if (s == water || !s.canBeReplaced()) return;
                    if (level.setBlock(pos, water, EDGE)) d.blocks++;
                });
            }
            case FIRE -> {
                pop(d);
                int chance = t.count();
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    if (!level.getBlockState(pos).isAir() || d.random.nextInt(100) >= chance) return;
                    if (!BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) return;
                    if (level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL)) d.blocks++;
                });
                for (LivingEntity e : d.living(t.size())) {
                    if (e != d.owner) e.igniteForSeconds(6);
                }
            }
            case FREEZE -> freeze(d, t);
            case TORCH -> {
                BlockState torch = Ids.state("torch");
                double r2 = t.size() * t.size();
                d.scan(-n, -n, -n, n, n, n, (dx, dz, range) -> {
                    if (Math.floorMod(d.at.getX() + dx, 5) != 0 || Math.floorMod(d.at.getZ() + dz, 5) != 0) return false;
                    double left = r2 - dx * dx - dz * dz;
                    if (left < 0) return false;
                    range[1] = (int) Math.sqrt(left);
                    range[0] = -range[1];
                    return true;
                }, (pos, dx, dy, dz) -> {
                    if (!level.getBlockState(pos).isAir()) return;
                    BlockPos below = pos.below();
                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return;
                    if (level.getMaxLocalRawBrightness(pos) >= 8) return;
                    if (level.setBlock(pos, torch, EDGE)) d.blocks++;
                }, null);
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 2.0f, 1.0f);
            }
            case BUILD -> {
                Builds.build(d, t.what());
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.5f, 1.6f);
                level.sendParticles(ParticleTypes.POOF, d.center.x, d.center.y + 1, d.center.z, 40, 2, 1, 2, 0.05);
            }
            case TREES -> trees(d, t);
            case BONEMEAL -> {
                int times = Math.max(1, t.count());
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    BlockState s = level.getBlockState(pos);
                    if (!(s.getBlock() instanceof BonemealableBlock) || s.isCollisionShapeFullBlock(level, pos)) return;
                    BlockPos here = pos.immutable();
                    boolean grew = false;
                    for (int i = 0; i < times; i++) {
                        BlockState now = level.getBlockState(here);
                        if (!(now.getBlock() instanceof BonemealableBlock plant) || !plant.isValidBonemealTarget(level, here, now)) break;
                        plant.performBonemeal(level, d.random, here, now);
                        grew = true;
                    }
                    if (grew) {
                        d.blocks++;
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, here.getX() + 0.5, here.getY() + 0.5, here.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0);
                    }
                });
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0f, 1.8f);
            }
            case MOB -> mobs(d, t);
            case POTION -> potion(d, t);
            case PUSH -> {
                pop(d);
                double r = t.size(), strength = t.count() / 10.0;
                for (Entity e : level.getEntities((Entity) null, d.around(r), x -> x.isAlive() && !x.isSpectator())) {
                    Vec3 away = e.position().subtract(d.center);
                    double far = away.length();
                    if (far > r) continue;
                    Vec3 way = switch (t.what()) {
                        case "up" -> new Vec3(0, 1, 0);
                        case "in" -> away.scale(-1 / Math.max(far, 0.1)).add(0, 0.15, 0);
                        default -> away.scale(1 / Math.max(far, 0.1)).add(0, 0.35, 0);
                    };
                    e.push(way.scale(strength * (1 - far / (r + 1))));
                    e.hurtMarked = true;
                    d.touched++;
                }
            }
            case TELEPORT -> {
                for (LivingEntity e : d.living(t.size())) {
                    for (int tries = 0; tries < 8; tries++) {
                        double x = e.getX() + (d.random.nextDouble() - 0.5) * 2 * t.count();
                        double z = e.getZ() + (d.random.nextDouble() - 0.5) * 2 * t.count();
                        double y = Mth.clamp(e.getY() + d.random.nextInt(17) - 8, level.getMinY() + 1, level.getMaxY() - 2);
                        if (e.randomTeleport(x, y, z, true)) {
                            d.touched++;
                            break;
                        }
                    }
                }
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.BLOCKS, 2.0f, 1.0f);
                level.sendParticles(ParticleTypes.PORTAL, d.center.x, d.center.y + 1, d.center.z, 200, t.size() / 2, 1, t.size() / 2, 0.5);
            }
            case BUTCHER -> {
                pop(d);
                for (LivingEntity e : d.living(t.size())) {
                    if (e instanceof Enemy) {
                        e.kill(level);
                        d.touched++;
                    }
                }
            }
            case LOVE -> {
                for (LivingEntity e : d.living(t.size())) {
                    if (e instanceof Animal animal && animal.getAge() == 0) {
                        animal.setInLove(d.owner instanceof Player player ? player : null);
                        d.touched++;
                    }
                }
                level.sendParticles(ParticleTypes.HEART, d.center.x, d.center.y + 1, d.center.z, 60, t.size() / 2, 1, t.size() / 2, 0);
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0f, 1.9f);
            }
            case MILK -> {
                for (LivingEntity e : d.living(t.size())) {
                    e.removeAllEffects();
                    d.touched++;
                }
                level.sendParticles(ParticleTypes.CLOUD, d.center.x, d.center.y + 1, d.center.z, 80, t.size() / 3, 1, t.size() / 3, 0.02);
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 2.0f, 1.0f);
            }
            case BURN -> {
                pop(d);
                for (LivingEntity e : d.living(t.size())) {
                    if (e instanceof Player) continue;
                    e.igniteForSeconds(t.count());
                    d.touched++;
                }
            }
            case SCALE -> {
                double factor = Double.parseDouble(t.what());
                List<LivingEntity> changed = new ArrayList<>();
                for (LivingEntity e : d.living(t.size())) {
                    AttributeInstance scale = e.getAttribute(Attributes.SCALE);
                    if (scale == null) continue;
                    scale.removeModifier(SCALE);
                    scale.addTransientModifier(new AttributeModifier(SCALE, factor - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    changed.add(e);
                    d.touched++;
                }
                d.later(t.count() * 20, () -> {
                    for (LivingEntity e : changed) {
                        AttributeInstance scale = e.getAttribute(Attributes.SCALE);
                        if (scale != null) scale.removeModifier(SCALE);
                    }
                });
                level.sendParticles(ParticleTypes.POOF, d.center.x, d.center.y + 1, d.center.z, 60, t.size() / 3, 1, t.size() / 3, 0.05);
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0f, factor > 1 ? 0.5f : 2.0f);
            }
            case MAGNET -> {
                for (Entity e : level.getEntities((Entity) null, d.around(t.size()), x -> x instanceof ItemEntity || x instanceof ExperienceOrb)) {
                    e.teleportTo(d.center.x, d.center.y + 0.3, d.center.z);
                    e.setDeltaMovement(Vec3.ZERO);
                    d.touched++;
                }
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.BLOCKS, 2.0f, 0.6f);
                d.actions++;
            }
            case CLEAN -> {
                for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, d.around(t.size()))) {
                    e.discard();
                    d.touched++;
                }
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.5f, 1.5f);
                d.actions++;
            }
            case XP -> {
                ExperienceOrb.award(level, d.center.add(0, 0.5, 0), t.count());
                level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0f, 1.0f);
                d.spawned++;
            }
            case LOOT -> {
                List<String> entries = Catalog.list(t.what());
                int picks = t.count() > 0 ? t.count() : entries.size();
                for (int i = 0; i < picks; i++) {
                    String entry = t.count() > 0 ? entries.get(d.random.nextInt(entries.size())) : entries.get(i);
                    int star = entry.indexOf('*');
                    Item item = Ids.item(star < 0 ? entry : entry.substring(0, star));
                    if (item == null) continue;
                    drop(d, new ItemStack(item, star < 0 ? 1 : Integer.parseInt(entry.substring(star + 1))), true);
                }
                fireworks(d, d.center, 3);
            }
            case LIGHTNING -> {
                for (int i = 0; i < t.count(); i++) {
                    d.later(1 + i * 3, () -> {
                        Vec3 spot = d.spot(t.size());
                        int x = Mth.floor(spot.x), z = Mth.floor(spot.z);
                        LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(level, EntitySpawnReason.TRIGGERED);
                        if (bolt == null) return;
                        bolt.snapTo(x + 0.5, d.surface(x, z), z + 0.5);
                        level.addFreshEntity(bolt);
                        d.spawned++;
                    });
                }
                if (t.what().equals("thunder")) command(d, "weather thunder");
            }
            case RAIN_OF -> rain(d, t);
            case CLUSTER -> {
                blast(d, d.center, 3.0f, false, true);
                BlockState custom = t.what().startsWith("@") && TntMania.BLOCKS.get(t.what().substring(1)) != null
                        ? TntMania.BLOCKS.get(t.what().substring(1)).defaultBlockState() : null;
                for (int i = 0; i < t.count(); i++) {
                    double angle = d.random.nextDouble() * Math.PI * 2, speed = 0.25 + d.random.nextDouble() * 0.55;
                    int fuse = 30 + d.random.nextInt(30);
                    PrimedTnt tnt = custom != null ? ModTntBlock.lit(level, d.center.x, d.center.y + 0.5, d.center.z, custom, d.owner, fuse)
                            : new PrimedTnt(level, d.center.x, d.center.y + 0.5, d.center.z, d.owner);
                    tnt.setFuse(fuse);
                    tnt.setDeltaMovement(Math.cos(angle) * speed, 0.45 + d.random.nextDouble() * 0.45, Math.sin(angle) * speed);
                    level.addFreshEntity(tnt);
                    d.spawned++;
                }
            }
            case FIREWORK -> {
                int gap = t.count() > 40 ? 3 : 2;
                for (int i = 0; i < t.count(); i++) {
                    d.later(1 + i * gap, () -> {
                        Vec3 spot = d.spot(t.size());
                        fireworks(d, new Vec3(spot.x, d.surface(Mth.floor(spot.x), Mth.floor(spot.z)), spot.z), 1);
                    });
                }
            }
            case BURST -> {
                pop(d);
                boolean egg = t.what().equals("egg");
                for (int i = 0; i < t.count(); i++) {
                    ThrowableItemProjectile thrown = egg ? new ThrownEgg(level, d.center.x, d.center.y + 1, d.center.z, new ItemStack(Items.EGG))
                            : new Snowball(level, d.center.x, d.center.y + 1, d.center.z, new ItemStack(Items.SNOWBALL));
                    thrown.shoot(d.random.nextDouble() - 0.5, 0.2 + d.random.nextDouble() * 0.6, d.random.nextDouble() - 0.5, 0.9f, 10.0f);
                    level.addFreshEntity(thrown);
                    d.spawned++;
                }
            }
            case WEATHER -> {
                pop(d);
                command(d, "weather " + t.what());
            }
            case TIME -> {
                pop(d);
                command(d, "time set " + t.what());
            }
            case FLY -> {
                blast(d, d.center, (float) t.size(), false, true);
                if (t.what().equals("rocket")) fireworks(d, d.center, 5);
            }
            case HOP -> {
                int hops = t.count();
                if (d.source != null) {
                    for (String tag : d.source.entityTags()) {
                        if (tag.startsWith(HOPS)) hops = (int) number(tag.substring(HOPS.length()), 1);
                    }
                }
                blast(d, d.center, (float) t.size(), false, true);
                ModTntBlock block = TntMania.BLOCKS.get(t.id());
                if (hops > 1 && block != null) {
                    PrimedTnt next = ModTntBlock.lit(level, d.center.x, d.center.y + 0.5, d.center.z,
                            block.defaultBlockState().setValue(ModTntBlock.FACING, d.facing), d.owner, 30);
                    next.setDeltaMovement(d.random.nextDouble() - 0.5, 0.75, d.random.nextDouble() - 0.5);
                    next.addTag(HOPS + (hops - 1));
                    level.addFreshEntity(next);
                    d.spawned++;
                }
            }
            case CHAIN -> {
                for (int i = 0; i < t.count(); i++) {
                    BlockPos spot = d.at.relative(d.facing, 3 + i * 4);
                    d.later(1 + i * 5, () -> blast(d, Vec3.atCenterOf(spot), (float) t.size(), false, true));
                }
            }
            case SPIRAL -> {
                for (int i = 0; i < t.count(); i++) {
                    double angle = i * 0.7, far = 2 + i * 0.6;
                    int x = Mth.floor(d.center.x + Math.cos(angle) * far), z = Mth.floor(d.center.z + Math.sin(angle) * far);
                    d.later(1 + i * 2, () -> blast(d, new Vec3(x + 0.5, d.surface(x, z) + 0.1, z + 0.5), (float) t.size(), false, true));
                }
            }
            case WAVES -> {
                for (int ring = 1; ring <= t.count(); ring++) {
                    double far = ring * 5.0;
                    int blasts = Math.max(6, (int) (Math.PI * 2 * far / 4));
                    d.later(ring * 10, () -> {
                        for (int i = 0; i < blasts; i++) {
                            double angle = Math.PI * 2 * i / blasts;
                            int x = Mth.floor(d.center.x + Math.cos(angle) * far), z = Mth.floor(d.center.z + Math.sin(angle) * far);
                            blast(d, new Vec3(x + 0.5, d.surface(x, z) + 0.1, z + 0.5), (float) t.size(), false, true);
                        }
                    });
                }
            }
            case QUAKE -> {
                for (int i = 0; i < t.count(); i++) {
                    d.later(1 + i * 3, () -> {
                        Vec3 spot = d.spot(t.size());
                        int x = Mth.floor(spot.x), z = Mth.floor(spot.z);
                        blast(d, new Vec3(x + 0.5, d.surface(x, z) - 0.5, z + 0.5), 2.5f, false, true);
                        for (LivingEntity e : d.living(t.size())) {
                            if (e.onGround()) {
                                e.push(0, 0.25, 0);
                                e.hurtMarked = true;
                            }
                        }
                    });
                }
            }
            case GRAVITY -> {
                pop(d);
                boolean up = t.what().equals("up");
                d.sphere(t.size(), (pos, dx, dy, dz) -> {
                    BlockState s = level.getBlockState(pos);
                    if (!d.removable(s, pos) || s.hasBlockEntity() || s.getBlock() instanceof LiquidBlock) return;
                    if (s.getBlock() instanceof ModTntBlock || s.getBlock() == Blocks.TNT || (up && d.random.nextInt(100) >= 60)) {
                        d.remove(pos, s, EDGE);
                        return;
                    }
                    FallingBlockEntity falling = FallingBlockEntity.fall(level, pos.immutable(), s);
                    if (up) {
                        double far = Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz));
                        falling.setDeltaMovement(dx / far * 0.5 + (d.random.nextDouble() - 0.5) * 0.3, 0.6 + d.random.nextDouble() * 0.7,
                                dz / far * 0.5 + (d.random.nextDouble() - 0.5) * 0.3);
                        falling.hurtMarked = true;
                    }
                    d.blocks++;
                });
            }
            case RANDOM -> {
                List<TntType> pool = new ArrayList<>();
                for (TntType other : Catalog.all()) {
                    boolean tooBig = other.kind() == Kind.CHUNK || (other.kind() == Kind.SPHERE && other.size() > 16) || other.id().equals("bedrock_tnt");
                    if (other.kind() != Kind.RANDOM && !tooBig) pool.add(other);
                }
                apply(d, pool.get(d.random.nextInt(pool.size())));
            }
        }
    }

    // ---- blasts ---------------------------------------------------------------------------------------------------

    /** A normal explosion, as the game does it, of the given strength (normal TNT is 4). */
    static void blast(Detonation d, Vec3 at, float power, boolean fire, boolean blocks) {
        d.level.explode(d.source, null, null, at.x, at.y, at.z, power, fire, blocks ? Level.ExplosionInteraction.TNT : Level.ExplosionInteraction.NONE);
        d.actions++;
    }

    /** The bang and the cloud of a blast, with no damage. */
    private static void pop(Detonation d) {
        d.level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 4.0f, 0.9f + d.random.nextFloat() * 0.2f);
        d.level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, d.center.x, d.center.y + 0.5, d.center.z, 1, 0, 0, 0, 0);
    }

    private static void carve(Detonation d, BlockPos pos, int flags) {
        BlockState s = d.level.getBlockState(pos);
        if (d.removable(s, pos)) d.remove(pos, s, flags);
    }

    /** A straight box from the TNT in one direction: `from`..`to` blocks away, `w` to each side. */
    private static void corridor(Detonation d, Direction dir, int w, int from, int to, int dyLow, int dyHigh) {
        Direction side = dir.getClockWise();
        d.box(dir.getStepX() * from - side.getStepX() * w, dyLow, dir.getStepZ() * from - side.getStepZ() * w,
                dir.getStepX() * to + side.getStepX() * w, dyHigh, dir.getStepZ() * to + side.getStepZ() * w,
                (pos, dx, dy, dz) -> carve(d, pos, EDGE));
    }

    /** The big ones: a round crater with a ragged rim, dug by the mod itself because the game's own blast cannot reach that far. */
    private static void bigBlast(Detonation d, TntType t) {
        ServerLevel level = d.level;
        double r = t.size();
        level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, (float) Math.max(4, r / 2), 0.6f);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, d.center.x, d.center.y + 1, d.center.z, (int) Math.min(40, 2 + r), r / 3, r / 3, r / 3, 0);
        double reach = r * 1.3;
        for (LivingEntity e : d.living(reach)) {
            double far = Math.sqrt(e.distanceToSqr(d.center));
            double force = 1 - far / reach;
            e.hurtServer(level, level.damageSources().explosion(d.source, d.owner), (float) (force * (8 + 3 * r)));
            Vec3 away = e.position().subtract(d.center);
            e.push(away.scale(1 / Math.max(far, 0.1)).add(0, 0.4, 0).scale(force * 2.5));
            e.hurtMarked = true;
        }
        double rim = r - 1.5;
        d.sphere(r + 1, (pos, dx, dy, dz) -> {
            BlockState s = level.getBlockState(pos);
            // obsidian and the like shrug off any blast, as they do with normal TNT: a bunker stays a bunker
            if (!d.removable(s, pos) || s.getBlock().getExplosionResistance() >= BLAST_PROOF) return;
            double far = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (far <= rim) d.remove(pos, s, QUIET);
            else if (far <= rim + 2.5 * crumble(pos)) d.remove(pos, s, EDGE);
        });
        if (t.what().contains("fire")) {
            double inner = (r - 3) * (r - 3);
            d.sphere(r + 2, (pos, dx, dy, dz) -> {
                if (dx * dx + dy * dy + dz * dz < inner || d.random.nextInt(100) >= 10) return;
                if (level.getBlockState(pos).isAir() && BaseFireBlock.canBePlacedAt(level, pos, Direction.UP)) {
                    level.setBlock(pos, BaseFireBlock.getState(level, pos), Block.UPDATE_ALL);
                }
            });
        }
        d.actions++;
    }

    /** A number from 0 to 1 that is always the same for the same block: makes the rim of a crater ragged. */
    private static double crumble(BlockPos pos) {
        int h = pos.getX() * 73428767 ^ pos.getY() * 912931 ^ pos.getZ() * 42317861;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 0xFFFF) / 65536.0;
    }

    // ---- picking blocks ---------------------------------------------------------------------------------------------

    private static final Map<String, Map<Block, Boolean>> BY_NAME = new HashMap<>();

    private static boolean named(Block block, String kind) {
        return BY_NAME.computeIfAbsent(kind, k -> new IdentityHashMap<>()).computeIfAbsent(block, b -> {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            return kind.equals("ore") ? path.endsWith("_ore") || path.equals("ancient_debris") : path.contains(kind);
        });
    }

    private static boolean water(BlockState s) {
        return s.getFluidState().getType().isSame(Fluids.WATER);
    }

    static boolean matches(String filter, BlockState s) {
        return switch (filter) {
            case "any" -> true;
            case "common" -> s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.BASE_STONE_NETHER) || s.is(BlockTags.DIRT)
                    || s.is(BlockTags.GRASS_BLOCKS) || s.is(BlockTags.MUD) || s.is(BlockTags.SAND) || s.is(BlockTags.TERRACOTTA)
                    || s.is(Blocks.GRAVEL) || s.is(Blocks.COBBLESTONE) || named(s.getBlock(), "sandstone");
            case "ore" -> named(s.getBlock(), "ore");
            case "tree" -> s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES);
            case "leaves" -> s.is(BlockTags.LEAVES);
            case "plants" -> s.is(BlockTags.FLOWERS) || (s.is(BlockTags.REPLACEABLE) && s.getFluidState().isEmpty()
                    && !s.is(BlockTags.FIRE) && !s.is(BlockTags.SNOW));
            case "water" -> water(s);
            case "hot" -> s.is(BlockTags.FIRE) || s.getFluidState().getType().isSame(Fluids.LAVA);
            case "snow" -> s.is(BlockTags.SNOW) || s.is(BlockTags.ICE);
            case "stone" -> s.is(BlockTags.BASE_STONE_OVERWORLD);
            case "crops" -> s.getBlock() instanceof CropBlock crop && crop.isMaxAge(s);
            case "glass" -> named(s.getBlock(), "glass");
            case "light" -> s.getLightEmission() > 0 && !s.hasBlockEntity() && s.getFluidState().isEmpty()
                    && !s.is(BlockTags.FIRE) && !s.is(BlockTags.PORTALS);
            case "bedrock" -> s.is(Blocks.BEDROCK);
            default -> false;
        };
    }

    private static final List<String> FILTERS = List.of("any", "common", "ore", "tree", "leaves", "plants", "water", "hot", "snow",
            "stone", "crops", "glass", "light", "bedrock");

    /** Breaks only the blocks of one sort, and can hand over what they drop. */
    private static void select(Detonation d, TntType t) {
        ServerLevel level = d.level;
        boolean quiet = t.what().endsWith("!quiet");
        String filter = quiet ? t.what().substring(0, t.what().length() - 6) : t.what();
        boolean drops = t.count() == 1;
        if (!quiet) pop(d);
        List<ItemStack> gathered = new ArrayList<>();
        d.sphere(t.size(), (pos, dx, dy, dz) -> {
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || !matches(filter, s)) return;
            switch (filter) {
                case "bedrock" -> d.remove(pos, s, EDGE);
                case "water" -> {
                    BlockState dry = s.hasProperty(BlockStateProperties.WATERLOGGED) ? s.setValue(BlockStateProperties.WATERLOGGED, false) : AIR;
                    if (level.setBlock(pos, dry, EDGE)) d.blocks++;
                }
                case "crops" -> {
                    Block.dropResources(s, level, pos);
                    if (level.setBlock(pos, ((CropBlock) s.getBlock()).getStateForAge(0), EDGE)) d.blocks++;
                }
                default -> {
                    if (s.getDestroySpeed(level, pos) < 0f) return;
                    if (drops && filter.equals("any")) Block.dropResources(s, level, pos, level.getBlockEntity(pos));
                    else if (drops) gather(gathered, Block.getDrops(s, level, pos, level.getBlockEntity(pos)));
                    d.remove(pos, s, EDGE);
                }
            }
        }, () -> {
            for (ItemStack stack : gathered) drop(d, stack, false);
        });
    }

    private static void gather(List<ItemStack> into, List<ItemStack> more) {
        for (ItemStack stack : more) {
            if (stack.isEmpty()) continue;
            boolean merged = false;
            for (ItemStack have : into) {
                if (ItemStack.isSameItemSameComponents(have, stack)) {
                    have.grow(stack.getCount());
                    merged = true;
                    break;
                }
            }
            if (!merged) into.add(stack.copy());
        }
    }

    /** Drops a pile of one item at the TNT, cut into normal stacks. */
    private static void drop(Detonation d, ItemStack pile, boolean scatter) {
        int left = pile.getCount();
        while (left > 0) {
            int size = Math.min(left, pile.getMaxStackSize());
            left -= size;
            ItemEntity item = new ItemEntity(d.level, d.center.x, d.center.y + 0.5, d.center.z, pile.copyWithCount(size));
            if (scatter) item.setDeltaMovement((d.random.nextDouble() - 0.5) * 0.5, 0.3 + d.random.nextDouble() * 0.3, (d.random.nextDouble() - 0.5) * 0.5);
            else item.setDeltaMovement(0, 0.2, 0);
            item.setDefaultPickUpDelay();
            d.level.addFreshEntity(item);
            d.spawned++;
        }
    }

    // ---- changing blocks --------------------------------------------------------------------------------------------

    private static void convert(Detonation d, TntType t) {
        ServerLevel level = d.level;
        List<BlockState> into = new ArrayList<>();
        for (String name : Catalog.list(t.what())) into.add(Ids.state(name));
        int chance = t.count() > 0 ? t.count() : 100;
        pop(d);
        d.sphere(t.size(), (pos, dx, dy, dz) -> {
            BlockState s = level.getBlockState(pos);
            if (s.isAir() || s.hasBlockEntity() || s.getDestroySpeed(level, pos) < 0f || !s.isCollisionShapeFullBlock(level, pos)) return;
            if (chance < 100 && d.random.nextInt(100) >= chance) return;
            BlockState to = into.get(d.random.nextInt(into.size()));
            if (s.getBlock() != to.getBlock() && level.setBlock(pos, to, EDGE)) d.blocks++;
        });
    }

    /** Turns some of the stone (or netherrack) into ore. The chance is given in thousandths. */
    private static void ore(Detonation d, TntType t) {
        ServerLevel level = d.level;
        boolean nether = t.what().startsWith("nether:");
        List<String> names = Catalog.list(nether ? t.what().substring(7) : t.what());
        pop(d);
        d.sphere(t.size(), (pos, dx, dy, dz) -> {
            BlockState s = level.getBlockState(pos);
            boolean deep = false;
            if (nether) {
                if (!s.is(Blocks.NETHERRACK)) return;
            } else {
                deep = s.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
                if (!deep && !s.is(BlockTags.STONE_ORE_REPLACEABLES)) return;
            }
            if (d.random.nextInt(1000) >= t.count()) return;
            String name = names.get(d.random.nextInt(names.size()));
            BlockState to = Ids.state(nether ? name : (deep ? "deepslate_" : "") + name + "_ore");
            if (!to.isAir() && level.setBlock(pos, to, EDGE)) d.blocks++;
        });
    }

    /** Changes only the top of the ground: "sand>dead_bush:2" is sand, with a dead bush on 2 in 100 of it. */
    private static void surface(Detonation d, TntType t) {
        ServerLevel level = d.level;
        String what = t.what();
        int arrow = what.indexOf('>');
        String topName = arrow < 0 ? what : what.substring(0, arrow);
        BlockState top = topName.isEmpty() ? null : Ids.state(topName);
        List<BlockState> covers = new ArrayList<>();
        int chance = 0;
        if (arrow >= 0) {
            String rest = what.substring(arrow + 1);
            int colon = rest.lastIndexOf(':');
            chance = colon < 0 ? 100 : Integer.parseInt(rest.substring(colon + 1));
            for (String name : Catalog.list(colon < 0 ? rest : rest.substring(0, colon))) covers.add(Ids.state(name));
        }
        int coverChance = chance;
        int[] column = {Integer.MIN_VALUE, Integer.MIN_VALUE};
        boolean[] found = {false};
        pop(d);
        d.disk(t.size(), 8, 8, (pos, dx, dy, dz) -> {
            if (dx != column[0] || dz != column[1]) {
                column[0] = dx;
                column[1] = dz;
                found[0] = false;
            }
            if (found[0]) return;
            BlockState s = level.getBlockState(pos);
            if (!s.isCollisionShapeFullBlock(level, pos) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS)) return;
            BlockPos above = pos.above();
            BlockState over = level.getBlockState(above);
            if (!over.isAir() && !(over.canBeReplaced() && over.getFluidState().isEmpty())) return;
            found[0] = true;
            if (top != null && s.getBlock() != top.getBlock() && !s.hasBlockEntity() && s.getDestroySpeed(level, pos) >= 0f) {
                if (level.setBlock(pos, top, EDGE)) d.blocks++;
            }
            if (!covers.isEmpty() && d.random.nextInt(100) < coverChance) {
                BlockState cover = covers.get(d.random.nextInt(covers.size()));
                if (cover.canSurvive(level, above) && level.setBlock(above, cover, EDGE)) d.blocks++;
            }
        });
    }

    private static void freeze(Detonation d, TntType t) {
        ServerLevel level = d.level;
        BlockState ice = Ids.state("ice"), obsidian = Ids.state("obsidian"), cobble = Ids.state("cobblestone"), snow = Ids.state("snow");
        pop(d);
        level.sendParticles(ParticleTypes.SNOWFLAKE, d.center.x, d.center.y + 1, d.center.z, 300, t.size() / 2, 2, t.size() / 2, 0.05);
        d.sphere(t.size(), (pos, dx, dy, dz) -> {
            BlockState s = level.getBlockState(pos);
            FluidState fluid = s.getFluidState();
            if (!fluid.isEmpty()) {
                if (!(s.getBlock() instanceof LiquidBlock)) return;
                BlockState to = water(s) ? ice : fluid.isSource() ? obsidian : cobble;
                if (level.setBlock(pos, to, EDGE)) d.blocks++;
            } else if (s.is(BlockTags.FIRE)) {
                if (level.setBlock(pos, AIR, EDGE)) d.blocks++;
            } else if (s.isAir()) {
                BlockPos below = pos.below();
                if (level.getBlockState(below).isCollisionShapeFullBlock(level, below) && snow.canSurvive(level, pos) && level.setBlock(pos, snow, EDGE)) d.blocks++;
            }
        });
        Holder<MobEffect> slow = Ids.effect("slowness");
        for (LivingEntity e : d.living(t.size())) {
            e.setTicksFrozen(Math.max(e.getTicksFrozen(), 300));
            if (slow != null) e.addEffect(new MobEffectInstance(slow, 200, 2));
            d.touched++;
        }
    }

    // ---- growing and spawning -----------------------------------------------------------------------------------------

    private static void trees(Detonation d, TntType t) {
        ServerLevel level = d.level;
        List<String> names = Catalog.list(t.what());
        Registry<ConfiguredFeature<?, ?>> features = level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
        level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 1.0f, 1.8f);
        for (int i = 0; i < Math.max(1, t.count()); i++) {
            d.later(1 + i * 2, () -> {
                String name = names.get(d.random.nextInt(names.size()));
                Optional<Holder.Reference<ConfiguredFeature<?, ?>>> tree = features.get(ResourceKey.create(Registries.CONFIGURED_FEATURE, Ids.id(name)));
                if (tree.isEmpty()) {
                    Ids.problem("there is no tree called " + name);
                    return;
                }
                BlockPos spot = d.at;
                if (t.size() >= 1) {
                    Vec3 v = d.spot(t.size());
                    int x = Mth.floor(v.x), z = Mth.floor(v.z);
                    if (!level.hasChunk(x >> 4, z >> 4)) return;
                    spot = new BlockPos(x, d.surface(x, z), z);
                }
                BlockState here = level.getBlockState(spot);
                if (!here.isAir() && here.canBeReplaced() && here.getFluidState().isEmpty()) level.setBlock(spot, AIR, EDGE);
                if (tree.get().value().place(level, level.getChunkSource().getGenerator(), d.random, spot)) d.spawned++;
            });
        }
    }

    /** A free spot to stand on near (x, z), close to the TNT's height. */
    private static BlockPos settle(Detonation d, double x, double z) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), d.at.getY(), Mth.floor(z));
        for (int i = 0; i < 4 && !d.level.getBlockState(pos).getCollisionShape(d.level, pos).isEmpty(); i++) pos.move(Direction.UP);
        for (int i = 0; i < 6; i++) {
            BlockPos below = pos.below();
            if (!d.level.getBlockState(below).getCollisionShape(d.level, below).isEmpty()) break;
            pos.move(Direction.DOWN);
        }
        return pos.immutable();
    }

    private static void mobs(Detonation d, TntType t) {
        ServerLevel level = d.level;
        List<String> names = Catalog.list(t.what());
        pop(d);
        for (int i = 0; i < t.count(); i++) {
            String name = names.get(i % names.size());
            boolean charged = name.endsWith("+charged");
            EntityType<?> type = Ids.entity(charged ? name.substring(0, name.length() - 8) : name);
            if (type == null) continue;
            Vec3 spot = d.spot(t.size());
            BlockPos pos = settle(d, spot.x, spot.z);
            Entity e = type.create(level, EntitySpawnReason.TRIGGERED);
            if (e == null) continue;
            e.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, d.random.nextFloat() * 360f, 0f);
            if (e instanceof Mob mob) mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.TRIGGERED, null);
            if (charged && e instanceof Creeper) e.getEntityData().set(CreeperAccessor.tntmania$powered(), true);
            level.addFreshEntityWithPassengers(e);
            level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 0.5, e.getZ(), 8, 0.3, 0.5, 0.3, 0.02);
            d.spawned++;
        }
    }

    /** "speed:60" is Speed for 60 seconds at the strength of the table's count; "slowness:20:6" gives its own strength. */
    private static void potion(Detonation d, TntType t) {
        ServerLevel level = d.level;
        List<MobEffectInstance> effects = new ArrayList<>();
        for (String spec : Catalog.list(t.what())) {
            String[] part = spec.split(":");
            Holder<MobEffect> effect = Ids.effect(part[0]);
            if (effect == null) continue;
            int seconds = part.length > 1 ? Integer.parseInt(part[1]) : 30;
            int strength = part.length > 2 ? Integer.parseInt(part[2]) : t.count();
            effects.add(new MobEffectInstance(effect, effect.value().isInstantaneous() ? 1 : seconds * 20, strength));
        }
        for (LivingEntity e : d.living(t.size())) {
            for (MobEffectInstance effect : effects) e.addEffect(new MobEffectInstance(effect), d.owner);
            d.touched++;
        }
        level.playSound(null, d.center.x, d.center.y, d.center.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 2.0f, 0.8f);
        level.sendParticles(ParticleTypes.END_ROD, d.center.x, d.center.y + 1, d.center.z, 150, t.size() / 3, 1, t.size() / 3, 0.05);
        d.actions++;
    }

    // ---- from the sky --------------------------------------------------------------------------------------------------

    private static void rain(Detonation d, TntType t) {
        ServerLevel level = d.level;
        int sky = Math.min(d.at.getY() + 45, level.getMaxY() - 2);
        double r = t.size();
        int count = t.count();
        pop(d);
        switch (t.what()) {
            case "arrow" -> {
                for (int i = 0; i < count; i++) {
                    d.later(1 + i * 40 / count, () -> {
                        Vec3 spot = d.spot(r);
                        Arrow arrow = new Arrow(level, spot.x, sky, spot.z, new ItemStack(Items.ARROW), null);
                        arrow.setDeltaMovement((d.random.nextDouble() - 0.5) * 0.1, -2.2, (d.random.nextDouble() - 0.5) * 0.1);
                        arrow.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
                        level.addFreshEntity(arrow);
                        d.spawned++;
                    });
                }
            }
            case "fireball" -> {
                for (int i = 0; i < count; i++) {
                    d.later(1 + i * 4, () -> {
                        Vec3 spot = d.spot(r);
                        LargeFireball ball = EntityTypes.FIREBALL.create(level, EntitySpawnReason.TRIGGERED);
                        if (ball == null) return;
                        ball.snapTo(spot.x, sky, spot.z);
                        ball.setDeltaMovement((d.random.nextDouble() - 0.5) * 0.3, -1.0, (d.random.nextDouble() - 0.5) * 0.3);
                        level.addFreshEntity(ball);
                        d.spawned++;
                    });
                }
            }
            case "anvil" -> {
                BlockState anvil = Ids.state("anvil");
                for (int i = 0; i < count; i++) {
                    d.later(1 + i * 2, () -> {
                        Vec3 spot = d.spot(r);
                        BlockPos pos = BlockPos.containing(spot.x, sky, spot.z);
                        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || !level.getBlockState(pos).isAir()) return;
                        FallingBlockEntity falling = FallingBlockEntity.fall(level, pos, anvil);
                        falling.setHurtsEntities(2.0f, 40);
                        d.spawned++;
                    });
                }
            }
            case "tnt" -> {
                int side = Math.max(1, (int) Math.round(Math.sqrt(count)));
                double step = side > 1 ? 2 * r / (side - 1) : 0;
                for (int i = 0; i < side * side; i++) {
                    PrimedTnt tnt = new PrimedTnt(level, d.center.x - r + (i % side) * step, sky, d.center.z - r + (i / side) * step, d.owner);
                    tnt.setFuse(80);
                    tnt.setDeltaMovement(0, 0, 0);
                    level.addFreshEntity(tnt);
                    d.spawned++;
                }
            }
            default -> Ids.problem(t.id() + ": nothing called " + t.what() + " can fall from the sky");
        }
    }

    /** Fireworks in random colours and shapes. They only look nice. */
    private static void fireworks(Detonation d, Vec3 at, int count) {
        FireworkExplosion.Shape[] shapes = FireworkExplosion.Shape.values();
        for (int i = 0; i < count; i++) {
            FireworkExplosion burst = new FireworkExplosion(shapes[d.random.nextInt(shapes.length)],
                    IntList.of(Mth.hsvToRgb(d.random.nextFloat(), 0.9f, 1.0f), Mth.hsvToRgb(d.random.nextFloat(), 0.9f, 1.0f)),
                    IntList.of(Mth.hsvToRgb(d.random.nextFloat(), 0.6f, 1.0f)), d.random.nextBoolean(), d.random.nextBoolean());
            ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS, new Fireworks(1 + d.random.nextInt(2), List.of(burst)));
            d.level.addFreshEntity(new FireworkRocketEntity(d.level, at.x + (d.random.nextDouble() - 0.5), at.y + 0.5, at.z + (d.random.nextDouble() - 0.5), rocket));
            d.spawned++;
        }
    }

    /** Runs one of the game's own commands, quietly. Used for the weather and the time of day. */
    private static void command(Detonation d, String command) {
        d.level.getServer().getCommands().performPrefixedCommand(d.level.getServer().createCommandSourceStack().withLevel(d.level).withSuppressedOutput(), command);
        d.actions++;
    }

    private static double number(String text, double otherwise) {
        try {
            return Double.parseDouble(text.strip());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static int number(String text, int otherwise) {
        return (int) Math.round(number(text, (double) otherwise));
    }

    // ---- checking the table ---------------------------------------------------------------------------------------------

    /** Looks up every name the table uses, so a wrong name shows up when the game starts and not in the middle of a blast. */
    static void check() {
        for (TntType t : Catalog.all()) {
            String what = t.what();
            switch (t.kind()) {
                case CONVERT -> Catalog.list(what).forEach(Ids::block);
                case FILL -> Ids.block(what);
                case ORE -> {
                    boolean nether = what.startsWith("nether:");
                    for (String name : Catalog.list(nether ? what.substring(7) : what)) {
                        if (nether) {
                            Ids.block(name);
                        } else {
                            Ids.block(name + "_ore");
                            Ids.block("deepslate_" + name + "_ore");
                        }
                    }
                }
                case SURFACE -> {
                    for (String part : what.replaceAll(":\\d+$", "").split("[>,]")) {
                        if (!part.isBlank()) Ids.block(part.strip());
                    }
                }
                case MOB -> {
                    for (String name : Catalog.list(what)) Ids.entity(name.replace("+charged", ""));
                }
                case POTION -> {
                    for (String spec : Catalog.list(what)) Ids.effect(spec.split(":")[0]);
                }
                case LOOT -> {
                    for (String entry : Catalog.list(what)) Ids.item(entry.replaceAll("\\*\\d+$", ""));
                }
                case SELECT -> {
                    if (!FILTERS.contains(what.replace("!quiet", ""))) Ids.problem(t.id() + ": there is no filter called " + what);
                }
                case BUILD -> {
                    if (!Builds.NAMES.contains(what)) Ids.problem(t.id() + ": there is no building called " + what);
                }
                case CLUSTER -> {
                    if (what.startsWith("@") && Catalog.get(what.substring(1)) == null) Ids.problem(t.id() + ": there is no TNT called " + what);
                }
                default -> {
                }
            }
            for (String item : t.recipe()) {
                if (item.startsWith("minecraft:")) Ids.item(item);
                else if (Catalog.get(item.substring(item.indexOf(':') + 1)) == null) Ids.problem(t.id() + ": the recipe needs " + item + ", which does not exist");
            }
        }
    }
}
