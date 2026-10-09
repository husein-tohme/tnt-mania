package net.husein.tntmania;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * The TNTs that build instead of break. Every building is written from where the TNT stood: "right", "up" and
 * "forward", with forward being the way the player looked when placing the block. Bedrock and chests are never
 * overwritten.
 */
final class Builds {
    static final List<String> NAMES = List.of("house", "tower", "wall", "bridge", "platform", "sky_platform", "stairway", "pyramid",
            "dome", "bunker", "bubble", "well", "pool", "farm", "igloo", "maze", "smiley", "snowman", "castle", "island", "portal",
            "railway", "minefield", "cage", "volcano", "statue", "parkour", "elevator");

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private Builds() {
    }

    static void build(Detonation d, String name) {
        Site s = new Site(d);
        switch (name) {
            case "house" -> house(s);
            case "tower" -> tower(s, 0, 0, 15, 0, -2);
            case "wall" -> wall(s);
            case "bridge" -> bridge(s);
            case "platform" -> s.fill(-7, -1, -7, 7, -1, 7, st("smooth_stone"));
            case "sky_platform" -> skyPlatform(s);
            case "stairway" -> stairway(s);
            case "pyramid" -> pyramid(s);
            case "dome" -> shell(s, 0, 0, 0, 8, st("glass"), true);
            case "bunker" -> bunker(s);
            case "bubble" -> shell(s, 0, 6, 0, 6, st("glass"), false);
            case "well" -> well(s);
            case "pool" -> pool(s);
            case "farm" -> farm(s);
            case "igloo" -> igloo(s);
            case "maze" -> maze(s);
            case "smiley" -> smiley(s);
            case "snowman" -> snowman(s);
            case "castle" -> castle(s);
            case "island" -> island(s);
            case "portal" -> portal(s);
            case "railway" -> railway(s);
            case "minefield" -> minefield(s);
            case "cage" -> cage(s);
            case "volcano" -> volcano(s);
            case "statue" -> statue(s);
            case "parkour" -> parkour(s);
            case "elevator" -> elevator(s);
            default -> Ids.problem("there is no building called " + name);
        }
    }

    private static BlockState st(String name) {
        return Ids.state(name);
    }

    /** Where a building stands and which way it looks, with the few moves all buildings are made of. */
    private static final class Site {
        final Detonation d;
        final ServerLevel level;
        final BlockPos o;
        final Direction f, r;

        Site(Detonation d) {
            this.d = d;
            this.level = d.level;
            this.o = d.at;
            this.f = d.facing;
            this.r = d.facing.getClockWise();
        }

        BlockPos at(int right, int up, int forward) {
            return o.relative(r, right).above(up).relative(f, forward);
        }

        boolean set(int right, int up, int forward, BlockState state) {
            return d.place(at(right, up, forward), state);
        }

        /** For fences, glass panes and iron bars: they join up with what is already next to them. */
        boolean link(BlockPos pos, BlockState state) {
            return d.place(pos, Block.updateFromNeighbourShapes(state, level, pos));
        }

        boolean link(int right, int up, int forward, BlockState state) {
            return link(at(right, up, forward), state);
        }

        void fill(int r0, int u0, int f0, int r1, int u1, int f1, BlockState state) {
            for (int up = Math.min(u0, u1); up <= Math.max(u0, u1); up++) {
                for (int right = Math.min(r0, r1); right <= Math.max(r0, r1); right++) {
                    for (int forward = Math.min(f0, f1); forward <= Math.max(f0, f1); forward++) set(right, up, forward, state);
                }
            }
        }

        /** The way a block must face to look back at the player. */
        String back() {
            return f.getOpposite().getName();
        }

        String ahead() {
            return f.getName();
        }

        /** A door or a bed: two blocks that belong together. */
        void door(int right, int up, int forward, String name) {
            BlockState door = Ids.with(st(name), "facing", ahead());
            set(right, up, forward, Ids.with(door, "half", "lower"));
            set(right, up + 1, forward, Ids.with(door, "half", "upper"));
        }

        void bed(int right, int up, int forward) {
            BlockState bed = Ids.with(st("red_bed"), "facing", ahead());
            set(right, up, forward, Ids.with(bed, "part", "foot"));
            set(right, up, forward + 1, Ids.with(bed, "part", "head"));
        }
    }

    /** A hollow ball (or only its upper half) of one block, around a point given from the TNT. */
    private static void shell(Site s, int cr, int cu, int cf, double radius, BlockState state, boolean upperHalf) {
        int n = (int) Math.ceil(radius) + 1;
        for (int up = upperHalf ? 0 : -n; up <= n; up++) {
            for (int right = -n; right <= n; right++) {
                for (int forward = -n; forward <= n; forward++) {
                    double far = Math.sqrt(right * right + up * up + forward * forward);
                    if (far >= radius - 0.5 && far < radius + 0.5) s.set(cr + right, cu + up, cf + forward, state);
                }
            }
        }
    }

    /** A full ball of one block. */
    private static void ball(Site s, int cr, double cu, int cf, double radius, BlockState state) {
        int n = (int) Math.ceil(radius) + 1;
        for (int up = Mth.floor(cu) - n; up <= Mth.floor(cu) + n; up++) {
            for (int right = -n; right <= n; right++) {
                for (int forward = -n; forward <= n; forward++) {
                    double du = up - cu;
                    if (right * right + du * du + forward * forward <= radius * radius) s.set(cr + right, up, cf + forward, state);
                }
            }
        }
    }

    private static void house(Site s) {
        BlockState planks = st("oak_planks"), log = st("oak_log"), pane = st("glass_pane");
        s.fill(-3, -1, -3, 3, -1, 3, planks);
        s.fill(-3, 0, -3, 3, 3, 3, planks);
        s.fill(-2, 0, -2, 2, 3, 2, AIR);
        for (int right = -3; right <= 3; right += 6) {
            for (int forward = -3; forward <= 3; forward += 6) s.fill(right, 0, forward, right, 3, forward, log);
        }
        s.fill(-3, 4, -3, 3, 4, 3, planks);
        s.fill(-2, 5, -2, 2, 5, 2, planks);
        s.fill(-1, 6, -1, 1, 6, 1, planks);
        s.fill(0, 0, -3, 0, 1, -3, AIR);
        s.door(0, 0, -3, "oak_door");
        for (int up = 1; up <= 2; up++) {
            s.set(-3, up, 0, AIR);
            s.set(3, up, 0, AIR);
            s.set(0, up, 3, AIR);
            s.link(-3, up, 0, pane);
            s.link(3, up, 0, pane);
            s.link(0, up, 3, pane);
        }
        s.bed(2, 0, 1);
        s.set(-2, 0, 2, Ids.with(st("chest"), "facing", s.back()));
        s.set(-2, 0, 1, st("crafting_table"));
        s.set(-2, 0, 0, Ids.with(st("furnace"), "facing", s.r.getName()));
        s.set(2, 0, -2, st("torch"));
        s.set(-2, 0, -2, st("torch"));
    }

    /** A hollow stone tower, 5 wide, with a ladder inside and a walk at the top. The doorway is given from its middle. Also used for the castle's corners. */
    private static void tower(Site s, int cr, int cf, int height, int doorRight, int doorForward) {
        BlockState stone = st("cobblestone");
        s.fill(cr - 2, -1, cf - 2, cr + 2, -1, cf + 2, stone);
        s.fill(cr - 2, 0, cf - 2, cr + 2, height, cf + 2, stone);
        s.fill(cr - 1, 0, cf - 1, cr + 1, height, cf + 1, AIR);
        s.fill(cr - 1, height + 1, cf - 1, cr + 1, height + 1, cf + 1, stone);
        s.fill(cr - 2, height + 1, cf - 2, cr + 2, height + 1, cf + 2, stone);
        for (int i = -2; i <= 2; i += 2) {
            s.set(cr + i, height + 2, cf - 2, stone);
            s.set(cr + i, height + 2, cf + 2, stone);
            s.set(cr - 2, height + 2, cf + i, stone);
            s.set(cr + 2, height + 2, cf + i, stone);
        }
        BlockState ladder = Ids.with(st("ladder"), "facing", s.back());
        for (int up = 0; up <= height + 1; up++) s.set(cr, up, cf + 1, ladder);
        s.fill(cr + doorRight, 0, cf + doorForward, cr + doorRight, 1, cf + doorForward, AIR);
    }

    private static void wall(Site s) {
        BlockState bricks = st("stone_bricks");
        s.fill(-10, 0, 2, 10, 4, 2, bricks);
        for (int right = -10; right <= 10; right += 2) s.set(right, 5, 2, bricks);
    }

    private static void bridge(Site s) {
        BlockState fence = st("oak_fence");
        s.fill(-2, -1, 1, 2, -1, 48, st("oak_planks"));
        for (int forward = 1; forward <= 48; forward++) {
            s.link(-2, 0, forward, fence);
            s.link(2, 0, forward, fence);
        }
    }

    private static void skyPlatform(Site s) {
        BlockState ladder = Ids.with(st("ladder"), "facing", s.back()), fence = st("oak_fence");
        s.fill(0, 0, 0, 0, 39, 0, st("cobblestone"));
        s.fill(-4, 40, -4, 4, 40, 4, st("smooth_stone"));
        for (int up = 0; up <= 40; up++) s.set(0, up, -1, ladder);
        for (int i = -4; i <= 4; i++) {
            s.link(i, 41, -4, fence);
            s.link(i, 41, 4, fence);
            s.link(-4, 41, i, fence);
            s.link(4, 41, i, fence);
        }
    }

    private static void stairway(Site s) {
        BlockState step = Ids.with(st("oak_stairs"), "facing", s.ahead());
        for (int i = 1; i <= 40; i++) s.fill(-1, i - 1, i, 1, i - 1, i, step);
    }

    private static void pyramid(Site s) {
        for (int up = 0; up <= 7; up++) s.fill(up - 7, up, up - 7, 7 - up, up, 7 - up, st(up == 7 ? "chiseled_sandstone" : "sandstone"));
    }

    private static void bunker(Site s) {
        s.fill(-3, -1, -3, 3, 4, 3, st("obsidian"));
        s.fill(-2, 0, -2, 2, 3, 2, AIR);
        s.fill(0, 0, -3, 0, 1, -3, AIR);
        s.set(2, 0, 2, st("torch"));
    }

    private static void well(Site s) {
        BlockState stone = st("cobblestone"), fence = st("oak_fence");
        s.fill(-1, -5, -1, 1, 0, 1, stone);
        s.fill(0, -4, 0, 0, -1, 0, Blocks.WATER.defaultBlockState());
        s.set(0, 0, 0, AIR);
        for (int right = -1; right <= 1; right += 2) {
            for (int forward = -1; forward <= 1; forward += 2) {
                s.link(right, 1, forward, fence);
                s.link(right, 2, forward, fence);
            }
        }
        s.fill(-1, 3, -1, 1, 3, 1, st("cobblestone_slab"));
    }

    private static void pool(Site s) {
        s.fill(-4, 0, -4, 4, 2, 4, AIR);
        s.fill(-4, -4, -4, 4, -1, 4, st("quartz_block"));
        s.fill(-3, -3, -3, 3, -1, 3, Blocks.WATER.defaultBlockState());
    }

    private static void farm(Site s) {
        BlockState soil = Ids.with(st("farmland"), "moisture", "7"), fence = st("oak_fence");
        s.fill(-5, 0, -5, 5, 2, 5, AIR);
        s.fill(-4, -1, -4, 4, -1, 4, soil);
        s.set(0, -1, 0, Blocks.WATER.defaultBlockState());
        for (int right = -4; right <= 4; right++) {
            for (int forward = -4; forward <= 4; forward++) {
                if (right != 0 || forward != 0) s.set(right, 0, forward, Ids.with(st("wheat"), "age", String.valueOf(s.d.random.nextInt(4))));
            }
        }
        for (int i = -5; i <= 5; i++) {
            s.link(i, 0, 5, fence);
            s.link(-5, 0, i, fence);
            s.link(5, 0, i, fence);
            if (i != 0) s.link(i, 0, -5, fence);
        }
        s.set(0, 0, -5, Ids.with(st("oak_fence_gate"), "facing", s.ahead()));
    }

    private static void igloo(Site s) {
        BlockState snow = st("snow_block");
        s.fill(-4, -1, -4, 4, -1, 4, snow);
        for (int up = 0; up <= 5; up++) {
            for (int right = -5; right <= 5; right++) {
                for (int forward = -5; forward <= 5; forward++) {
                    double far = Math.sqrt(right * right + up * up + forward * forward);
                    if (far >= 3.5 && far < 4.5) s.set(right, up, forward, snow);
                    else if (far < 3.5) s.set(right, up, forward, AIR);
                }
            }
        }
        s.fill(0, 0, -4, 0, 1, -4, AIR);
        s.bed(1, 0, 1);
        s.set(-1, 0, 2, Ids.with(st("furnace"), "facing", s.back()));
        s.set(-2, 0, 0, st("torch"));
    }

    /** A new hedge maze every time: paths are dug through a grid of hedges until every corner has been reached. */
    private static void maze(Site s) {
        int size = 25, half = size / 2;
        boolean[][] open = new boolean[size][size];
        ArrayDeque<int[]> trail = new ArrayDeque<>();
        open[1][1] = true;
        trail.push(new int[]{1, 1});
        int[][] steps = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        while (!trail.isEmpty()) {
            int[] here = trail.peek();
            List<int[]> ways = new ArrayList<>();
            for (int[] step : steps) {
                int x = here[0] + step[0], z = here[1] + step[1];
                if (x > 0 && z > 0 && x < size - 1 && z < size - 1 && !open[x][z]) ways.add(step);
            }
            if (ways.isEmpty()) {
                trail.pop();
                continue;
            }
            int[] step = ways.get(s.d.random.nextInt(ways.size()));
            open[here[0] + step[0] / 2][here[1] + step[1] / 2] = true;
            open[here[0] + step[0]][here[1] + step[1]] = true;
            trail.push(new int[]{here[0] + step[0], here[1] + step[1]});
        }
        open[11][0] = true;
        open[13][size - 1] = true;
        BlockState hedge = Ids.with(st("oak_leaves"), "persistent", "true");
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) s.fill(x - half, 0, z - half, x - half, 2, z - half, open[x][z] ? AIR : hedge);
        }
    }

    private static void smiley(Site s) {
        BlockState yellow = st("yellow_wool"), black = st("black_wool");
        for (int right = -7; right <= 7; right++) {
            for (int forward = -7; forward <= 7; forward++) {
                if (right * right + forward * forward > 7.3 * 7.3) continue;
                boolean eye = Math.abs(right) == 3 && (forward == 2 || forward == 3);
                boolean mouth = (forward == -3 && Math.abs(right) <= 3) || (forward == -2 && Math.abs(right) == 4);
                s.set(right, -1, forward, eye || mouth ? black : yellow);
            }
        }
    }

    private static void snowman(Site s) {
        BlockState snow = st("snow_block"), coal = st("coal_block"), fence = st("oak_fence");
        ball(s, 0, 3, 0, 3.5, snow);
        ball(s, 0, 8, 0, 2.7, snow);
        ball(s, 0, 12, 0, 2.0, snow);
        // Put down the quiet way: a carved pumpkin that "notices" two snow blocks in a row behind it would turn
        // them, and itself, into a living snow golem and leave a hole in the head.
        s.d.place(s.at(0, 12, -2), Ids.with(st("carved_pumpkin"), "facing", s.back()), Detonation.EDGE | Block.UPDATE_SKIP_ON_PLACE);
        s.set(0, 8, -2, coal);
        s.set(0, 3, -3, coal);
        s.set(0, 4, -3, coal);
        for (int i = 3; i <= 4; i++) {
            s.link(i, 9, 0, fence);
            s.link(-i, 9, 0, fence);
        }
    }

    private static void castle(Site s) {
        BlockState bricks = st("stone_bricks");
        s.fill(-12, 0, -12, 12, 8, 12, AIR);
        for (int i = -12; i <= 12; i++) {
            s.fill(i, 0, -12, i, 5, -12, bricks);
            s.fill(i, 0, 12, i, 5, 12, bricks);
            s.fill(-12, 0, i, -12, 5, i, bricks);
            s.fill(12, 0, i, 12, 5, i, bricks);
            if ((i & 1) == 0) {
                s.set(i, 6, -12, bricks);
                s.set(i, 6, 12, bricks);
                s.set(-12, 6, i, bricks);
                s.set(12, 6, i, bricks);
            } else if (Math.floorMod(i, 4) == 1) {
                s.set(i, 6, -12, st("torch"));
                s.set(i, 6, 12, st("torch"));
                s.set(-12, 6, i, st("torch"));
                s.set(12, 6, i, st("torch"));
            }
        }
        for (int right = -10; right <= 10; right += 20) {
            for (int forward = -10; forward <= 10; forward += 20) tower(s, right, forward, 9, right > 0 ? -2 : 2, 0);
        }
        s.fill(-1, 0, -12, 1, 3, -12, AIR);
    }

    private static void island(Site s) {
        BlockState grass = st("grass_block"), dirt = st("dirt"), stone = st("stone");
        int top = 30;
        for (int right = -7; right <= 7; right++) {
            for (int forward = -7; forward <= 7; forward++) {
                double far = Math.sqrt(right * right + forward * forward);
                if (far > 7) continue;
                s.set(right, top, forward, grass);
                s.fill(right, top - 2, forward, right, top - 1, forward, dirt);
                int depth = (int) ((7 - far) * 1.2);
                if (depth > 0) s.fill(right, top - 2 - depth, forward, right, top - 3, forward, stone);
            }
        }
        Optional<Holder.Reference<ConfiguredFeature<?, ?>>> oak = s.level.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE)
                .get(ResourceKey.create(Registries.CONFIGURED_FEATURE, Ids.id("fancy_oak")));
        if (oak.isEmpty()) Ids.problem("there is no tree called fancy_oak");
        else if (oak.get().value().place(s.level, s.level.getChunkSource().getGenerator(), s.d.random, s.at(0, top + 1, 0))) s.d.spawned++;
    }

    private static void portal(Site s) {
        BlockState obsidian = st("obsidian");
        for (int right = -1; right <= 2; right++) {
            for (int up = 0; up <= 4; up++) s.set(right, up, 2, right == -1 || right == 2 || up == 0 || up == 4 ? obsidian : AIR);
        }
        if (s.level.dimension() == Level.OVERWORLD || s.level.dimension() == Level.NETHER) {
            PortalShape.findEmptyPortalShape(s.level, s.at(0, 1, 2), s.r.getAxis()).ifPresent(shape -> {
                shape.createPortalBlocks(s.level);
                s.d.blocks += 6;
            });
        }
    }

    private static void railway(Site s) {
        String along = s.f.getAxis() == Direction.Axis.Z ? "north_south" : "east_west";
        BlockState rail = Ids.with(st("rail"), "shape", along);
        BlockState powered = Ids.with(Ids.with(st("powered_rail"), "shape", along), "powered", "true");
        for (int forward = 1; forward <= 64; forward++) {
            boolean boost = forward % 4 == 1;
            s.fill(0, 0, forward, 0, 2, forward, AIR);
            s.set(0, -1, forward, st(boost ? "redstone_block" : "stone_bricks"));
            s.set(0, 0, forward, boost ? powered : rail);
        }
    }

    private static void minefield(Site s) {
        BlockState plate = st("stone_pressure_plate"), tnt = Blocks.TNT.defaultBlockState();
        for (int i = 0; i < 12; i++) {
            int x = s.o.getX() + s.d.random.nextInt(17) - 8, z = s.o.getZ() + s.d.random.nextInt(17) - 8;
            if (!s.level.hasChunk(x >> 4, z >> 4)) continue;
            BlockPos top = new BlockPos(x, s.d.surface(x, z), z);
            if (!s.level.getBlockState(top).canBeReplaced() || !s.level.getBlockState(top.below()).isCollisionShapeFullBlock(s.level, top.below())) continue;
            // the plate goes down first: TNT that is put under a pressed plate would go off at once
            if (s.d.place(top, plate)) s.d.place(top.below(), tnt);
        }
    }

    private static void cage(Site s) {
        BlockState bars = st("iron_bars");
        List<LivingEntity> mobs = s.d.living(10);
        mobs.removeIf(e -> e instanceof Player);
        Collections.shuffle(mobs);
        for (LivingEntity mob : mobs.subList(0, Math.min(12, mobs.size()))) {
            AABB box = mob.getBoundingBox();
            int x0 = Mth.floor(box.minX) - 1, x1 = Mth.floor(box.maxX) + 1, z0 = Mth.floor(box.minZ) - 1, z1 = Mth.floor(box.maxZ) + 1;
            int y0 = Mth.floor(box.minY), y1 = Mth.floor(box.maxY) + 1;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        boolean side = x == x0 || x == x1 || z == z0 || z == z1;
                        if (side || y == y1) s.link(new BlockPos(x, y, z), bars);
                    }
                }
            }
            s.d.touched++;
        }
    }

    private static void volcano(Site s) {
        BlockState[] rock = {st("basalt"), st("basalt"), st("basalt"), st("blackstone"), st("blackstone"), st("magma_block")};
        for (int up = 0; up <= 8; up++) {
            int radius = 9 - up;
            for (int right = -radius; right <= radius; right++) {
                for (int forward = -radius; forward <= radius; forward++) {
                    if (right * right + forward * forward <= radius * radius) s.set(right, up, forward, rock[s.d.random.nextInt(rock.length)]);
                }
            }
        }
        s.d.place(s.at(0, 9, 0), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** A 13-block TNT made of wool, with "TNT" written on all four sides. */
    private static void statue(Site s) {
        BlockState red = st("red_wool"), white = st("white_wool"), black = st("black_wool");
        String[] word = {"111010010111", "010011010010", "010010110010", "010010010010", "010010010010"};
        for (int up = 0; up <= 12; up++) {
            for (int a = -6; a <= 6; a++) {
                for (int b = -6; b <= 6; b++) {
                    boolean outside = Math.abs(a) == 6 || Math.abs(b) == 6 || up == 0 || up == 12;
                    if (outside) s.set(a, up, b, up >= 4 && up <= 8 ? white : red);
                }
            }
        }
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 12; col++) {
                if (word[row].charAt(col) != '1') continue;
                int up = 8 - row, along = col - 6;
                s.set(along, up, -6, black);
                s.set(-along - 1, up, 6, black);
                s.set(-6, up, -along - 1, black);
                s.set(6, up, along, black);
            }
        }
    }

    private static void parkour(Site s) {
        String[] colours = {"red", "orange", "yellow", "lime", "light_blue", "blue", "purple", "magenta"};
        int right = 0, up = 0, forward = 2;
        for (int i = 0; i < 30; i++) {
            s.set(right, up, forward, st(colours[i % colours.length] + "_concrete"));
            int jump = 2 + s.d.random.nextInt(3);
            forward += jump;
            right += s.d.random.nextInt(5) - 2;
            if (jump < 4 && s.d.random.nextBoolean()) up++;
        }
        s.fill(right - 1, up, forward, right + 1, up, forward + 2, st("lime_concrete"));
        s.set(right, up + 1, forward + 1, st("cake"));
    }

    /** A glass tube of water over soul sand: the bubbles carry you up. The door keeps the water in. */
    private static void elevator(Site s) {
        int top = 30;
        BlockState water = Blocks.WATER.defaultBlockState();
        s.fill(-1, 0, -1, 1, top, 1, st("glass"));
        s.fill(-2, top, -2, 2, top, 2, st("stone_bricks"));
        s.fill(0, 0, 0, 0, top, 0, water);
        s.fill(0, 0, -1, 0, 1, -1, AIR);
        s.door(0, 0, -1, "oak_door");
        s.d.place(s.at(0, -1, 0), st("soul_sand"), Block.UPDATE_ALL);
    }
}
