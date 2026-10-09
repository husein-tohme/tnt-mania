package net.husein.tntmania.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.husein.tntmania.Detonation;
import net.husein.tntmania.Effects;
import net.husein.tntmania.Fill;
import net.husein.tntmania.FillSettings;
import net.husein.tntmania.Ids;
import net.husein.tntmania.Jobs;
import net.husein.tntmania.ModTntBlock;
import net.husein.tntmania.TntMania;
import net.husein.tntmania.catalog.Catalog;
import net.husein.tntmania.catalog.Kind;
import net.husein.tntmania.catalog.TntType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import io.netty.buffer.Unpooled;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runs inside a real game server (no window). Every test gets its own piece of ground far away from the others:
 * a slab of stone hanging in the air, so a blast in one test cannot reach another. TNT is set off the real way
 * (a block that is lit and whose fuse runs out) where the lighting is what is tested, and directly where only the
 * effect is tested.
 */
public class TntManiaTests {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Puts a block without anything around it reacting. */
    private static final int STILL = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static int slots;
    private static final int WAIT = 2;

    private static Block b(String name) {
        return Ids.block(name);
    }

    private static BlockState s(String name) {
        return Ids.state(name);
    }

    private static TntType type(String id) {
        TntType t = Catalog.get(id);
        if (t == null) throw new IllegalStateException("the table has no TNT called " + id);
        return t;
    }

    private static BlockState tnt(String id) {
        return TntMania.BLOCKS.get(id).defaultBlockState();
    }

    /** A piece of test ground. (0, 0, 0) is where the TNT goes: the first free block on top of the slab. */
    private static final class Ground {
        final ServerLevel level;
        final BlockPos c;

        Ground(ServerLevel level, BlockPos c) {
            this.level = level;
            this.c = c;
        }

        /** A stone slab 25 wide and 10 deep with grass on top. */
        static Ground make(ServerLevel level) {
            return make(level, 12, 10, 0, "stone");
        }

        /** A slab `half` blocks to each side and `down` deep, with solid rock `up` blocks high on top of it (0 = grass). */
        static Ground make(ServerLevel level, int half, int down, int up, String rock) {
            // One column running north to south, 48 blocks (3 chunks) apart. Everything that goes "forward" in the
            // tests goes east, where there is nothing. Standing this close keeps the amount of loaded land small.
            int slot = slots++;
            return at(level, 6000 + slot / 60 * 480, -6000 - slot % 60 * 48, half, down, up, rock, 1);
        }

        static Ground at(ServerLevel level, int x, int z, int half, int down, int up, String rock, int chunks) {
            for (int cx = (x >> 4) - chunks; cx <= (x >> 4) + chunks; cx++) {
                for (int cz = (z >> 4) - chunks; cz <= (z >> 4) + chunks; cz++) {
                    level.getChunk(cx, cz);
                    level.setChunkForced(cx, cz, true);
                }
            }
            // Land that was loaded this very moment is not fully "there" yet: mobs put on it cannot be found.
            // In a real game land is loaded long before a player gets near. Here the test waits until it is ready.
            long giveUp = System.nanoTime() + 20_000_000_000L;
            for (int cx = (x >> 4) - chunks; cx <= (x >> 4) + chunks; cx++) {
                for (int cz = (z >> 4) - chunks; cz <= (z >> 4) + chunks; cz++) {
                    BlockPos middle = new BlockPos((cx << 4) + 8, 0, (cz << 4) + 8);
                    while (!level.isPositionEntityTicking(middle)) {
                        if (System.nanoTime() > giveUp) throw new IllegalStateException("the test land at " + x + " " + z + " never became ready");
                        if (!level.getChunkSource().pollTask()) Thread.onSpinWait();
                    }
                }
            }
            Ground g = new Ground(level, new BlockPos(x, level.getMinY() + 120, z));
            g.fill(-half, -down, -half, half, -1, half, s(rock));
            if (up > 0) g.fill(-half, 0, -half, half, up, half, s(rock));
            else g.fill(-half, -1, -half, half, -1, half, s("grass_block"));
            return g;
        }

        BlockPos at(int dx, int dy, int dz) {
            return c.offset(dx, dy, dz);
        }

        BlockState get(int dx, int dy, int dz) {
            return level.getBlockState(at(dx, dy, dz));
        }

        boolean is(int dx, int dy, int dz, String name) {
            return get(dx, dy, dz).is(b(name));
        }

        boolean air(int dx, int dy, int dz) {
            return get(dx, dy, dz).isAir();
        }

        void set(int dx, int dy, int dz, BlockState state) {
            level.setBlock(at(dx, dy, dz), state, STILL);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) set(x, y, z, state);
                }
            }
        }

        /** How many blocks of this kind there are within r of the middle (in a cube). */
        int count(int r, String name) {
            Block block = b(name);
            int n = 0;
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        if (get(x, y, z).is(block)) n++;
                    }
                }
            }
            return n;
        }

        /** How many blocks within r have a name that ends this way ("_wool", "_ore"). */
        int countEnding(int r, String ending) {
            int n = 0;
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        if (BuiltInRegistries.BLOCK.getKey(get(x, y, z).getBlock()).getPath().endsWith(ending)) n++;
                    }
                }
            }
            return n;
        }

        <T extends Entity> List<T> all(Class<T> kind, double r) {
            return level.getEntitiesOfClass(kind, new AABB(c).inflate(r));
        }

        /** A mob that stands still where it is put. */
        Mob mob(String name, double dx, double dz) {
            Mob mob = (Mob) Ids.entity(name).create(level, EntitySpawnReason.COMMAND);
            mob.snapTo(c.getX() + 0.5 + dx, c.getY(), c.getZ() + 0.5 + dz, 0f, 0f);
            mob.setNoAi(true);
            mob.setPersistenceRequired();
            level.addFreshEntity(mob);
            return mob;
        }

        ItemEntity item(Item item, double dx, double dz) {
            ItemEntity e = new ItemEntity(level, c.getX() + 0.5 + dx, c.getY() + 0.2, c.getZ() + 0.5 + dz, new ItemStack(item), 0, 0, 0);
            level.addFreshEntity(e);
            return e;
        }

        Detonation boom(String id) {
            return boom(id, Direction.EAST);
        }

        Detonation boom(String id, Direction facing) {
            return Effects.run(level, type(id), c, facing, null);
        }

        boolean dropped(Item item, double r) {
            for (ItemEntity e : all(ItemEntity.class, r)) {
                if (e.getItem().is(item)) return true;
            }
            return false;
        }

        /** The things the picky TNTs look for, all on one piece of ground. */
        Ground decorate() {
            set(3, -1, 3, s("diamond_ore"));
            set(4, 0, 0, s("oak_log"));
            set(4, 1, 0, Ids.with(s("oak_leaves"), "persistent", "true"));
            set(-3, -2, -3, s("water"));
            set(-4, -2, -4, s("lava"));
            set(0, 0, 5, s("snow_block"));
            set(1, 0, 5, s("glass"));
            set(2, 0, 5, s("torch"));
            set(3, 0, 5, s("short_grass"));
            set(5, -1, 5, Ids.with(s("farmland"), "moisture", "7"));
            set(5, 0, 5, Ids.with(s("wheat"), "age", "7"));
            set(1, -1, 0, s("bedrock"));
            return this;
        }
    }

    /** Waits until these blasts have finished all their work, then checks. Fails with the last complaint if that never happens. */
    private static void when(GameTestHelper helper, Runnable checks, Detonation... blasts) {
        helper.succeedWhen(() -> {
            for (Detonation d : blasts) {
                helper.assertTrue(d.error == null, d.type.id() + " went wrong: " + d.error);
                helper.assertTrue(d.done(), d.type.id() + " is still working");
            }
            checks.run();
        });
    }

    private static void normal(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
    }

    private static String problems() {
        return Ids.PROBLEMS.isEmpty() ? "" : " Problems: " + Ids.PROBLEMS;
    }

    // ---- the table ------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 100)
    public void thereAre250AndEachHasItsBlockItemRecipeDropAndFiles(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        List<TntType> all = Catalog.all();
        helper.assertTrue(all.size() == 250, "the table should have 250 TNT types, it has " + all.size());
        helper.assertTrue(TntMania.BLOCKS.size() == 250, "250 blocks should be registered, there are " + TntMania.BLOCKS.size());
        helper.assertTrue(Ids.PROBLEMS.isEmpty(), "every name in the table should be known to the game." + problems());
        helper.assertTrue(BuiltInRegistries.CREATIVE_MODE_TAB.containsKey(Identifier.fromNamespaceAndPath("tntmania", "all")), "the creative tab is missing");

        String lang;
        try (InputStream in = TntMania.class.getResourceAsStream("/assets/tntmania/lang/en_us.json")) {
            helper.assertTrue(in != null, "the text file is missing");
            lang = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Set<String> labels = new HashSet<>();
        for (TntType t : all) {
            String id = t.id();
            Identifier key = Identifier.fromNamespaceAndPath("tntmania", id);
            ModTntBlock block = TntMania.BLOCKS.get(id);
            helper.assertTrue(block != null && BuiltInRegistries.BLOCK.getValue(key) == block, id + ": the block is not registered");
            Item item = BuiltInRegistries.ITEM.getValue(key);
            helper.assertTrue(item instanceof BlockItem blockItem && blockItem.getBlock() == block, id + ": the item is not registered");
            ItemLore lore = new ItemStack(item).get(DataComponents.LORE);
            List<String> about = Catalog.aboutLines(t);
            helper.assertTrue(lore != null && lore.lines().size() == about.size() && !about.isEmpty(), id + ": the item should say what it does, in " + about.size() + " lines");
            for (int line = 0; line < about.size(); line++) {
                helper.assertTrue(about.get(line).length() <= Catalog.TOOLTIP_WIDTH || !about.get(line).contains(" "), id + ": a line of its text is too long: " + about.get(line));
                helper.assertTrue(lang.contains("\"tntmania.about." + id + "." + line + "\""), id + ": line " + line + " of its text is missing");
            }

            ResourceKey<Recipe<?>> recipe = ResourceKey.create(Registries.RECIPE, key);
            helper.assertTrue(server.getRecipeManager().byKey(recipe).isPresent(), id + ": the game did not accept its recipe");
            helper.assertTrue(player.getRecipeBook().contains(recipe), id + ": a player who joins should know its recipe");

            List<ItemStack> drops = Block.getDrops(block.defaultBlockState(), level, BlockPos.ZERO, null);
            helper.assertTrue(drops.size() == 1 && drops.get(0).is(item), id + ": broken by hand it should drop itself, it drops " + drops);

            for (String file : List.of("blockstates/" + id + ".json", "models/block/" + id + ".json", "items/" + id + ".json",
                    "textures/block/" + id + "_side.png", "textures/block/" + id + "_top.png", "textures/block/" + id + "_bottom.png")) {
                helper.assertTrue(TntMania.class.getResource("/assets/tntmania/" + file) != null, id + ": the file " + file + " is missing");
            }
            helper.assertTrue(lang.contains("\"block.tntmania." + id + "\""), id + ": its name is missing");
            helper.assertTrue(labels.add(t.label()), id + ": the label " + t.label() + " is used twice");
            helper.assertTrue(!t.about().isBlank() && !t.name().isBlank(), id + ": needs a name and a text");
        }
        helper.succeed();
    }

    // ---- lighting it -------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 100)
    public void itFacesThePlayersWayAndLightsWithRedstoneAndFlint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground g = Ground.make(level);
        ModTntBlock block = TntMania.BLOCKS.get("tunnel_tnt");
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        player.snapTo(g.c.getX() + 0.5, g.c.getY(), g.c.getZ() - 2.5, 90f, 0f);
        BlockHitResult floor = new BlockHitResult(Vec3.atCenterOf(g.at(0, -1, 0)), Direction.UP, g.at(0, -1, 0), false);
        BlockState placed = block.getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, new ItemStack(block), floor));
        helper.assertTrue(placed.getValue(ModTntBlock.FACING) == player.getDirection(), "the block should face the way the player looks ("
                + player.getDirection() + "), it faces " + placed.getValue(ModTntBlock.FACING));

        helper.runAfterDelay(WAIT, () -> {
            // redstone
            level.setBlock(g.c, placed, Block.UPDATE_ALL);
            helper.assertTrue(g.all(PrimedTnt.class, 6).isEmpty() && g.get(0, 0, 0) == placed, "just standing there, it must not light");
            level.setBlock(g.at(1, 0, 0), s("redstone_block"), Block.UPDATE_ALL);
            List<PrimedTnt> lit = g.all(PrimedTnt.class, 6);
            helper.assertTrue(lit.size() == 1 && g.air(0, 0, 0), "redstone should light it: " + lit.size() + " lit, block now " + g.get(0, 0, 0));
            helper.assertTrue(lit.get(0).getBlockState() == placed, "the lit TNT should look like the block, facing included");
            helper.assertTrue(lit.get(0).getFuse() == type("tunnel_tnt").fuse(), "the fuse should be " + type("tunnel_tnt").fuse() + ", it is " + lit.get(0).getFuse());
            lit.get(0).discard();
            g.set(1, 0, 0, AIR);

            // an item that is not a lighter does nothing
            level.setBlock(g.c, placed, Block.UPDATE_ALL);
            BlockHitResult side = new BlockHitResult(Vec3.atCenterOf(g.c), Direction.UP, g.c, false);
            placed.useItemOn(new ItemStack(Items.STICK), level, player, InteractionHand.MAIN_HAND, side);
            helper.assertTrue(g.all(PrimedTnt.class, 6).isEmpty() && g.get(0, 0, 0) == placed, "a stick must not light it");

            // flint and steel
            ItemStack flint = new ItemStack(Items.FLINT_AND_STEEL);
            player.setItemInHand(InteractionHand.MAIN_HAND, flint);
            placed.useItemOn(flint, level, player, InteractionHand.MAIN_HAND, side);
            lit = g.all(PrimedTnt.class, 6);
            helper.assertTrue(lit.size() == 1 && g.air(0, 0, 0), "flint and steel should light it");
            helper.assertTrue(lit.get(0).getOwner() == player, "the player who lit it should be remembered");
            helper.assertTrue(flint.getDamageValue() == 1, "the flint and steel should wear by 1, it wore by " + flint.getDamageValue());
            lit.get(0).discard();

            // a world with TNT switched off
            level.getGameRules().set(GameRules.TNT_EXPLODES, false, level.getServer());
            try {
                level.setBlock(g.c, placed, Block.UPDATE_ALL);
                level.setBlock(g.at(1, 0, 0), s("redstone_block"), Block.UPDATE_ALL);
                helper.assertTrue(g.all(PrimedTnt.class, 6).isEmpty() && g.get(0, 0, 0) == placed, "with TNT switched off in the world it must not light");
            } finally {
                level.getGameRules().set(GameRules.TNT_EXPLODES, true, level.getServer());
                g.set(0, 0, 0, AIR);
                g.set(1, 0, 0, AIR);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 200)
    public void aBlastFireAndADispenserLightItToo(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        Ground g = Ground.make(level);
        BlockState west = tnt("cow_tnt").setValue(ModTntBlock.FACING, Direction.WEST);

        // another blast
        level.setBlock(g.at(2, 0, 0), west, Block.UPDATE_ALL);
        level.explode(null, g.c.getX() + 0.5, g.c.getY() + 0.5, g.c.getZ() + 0.5, 3.0f, Level.ExplosionInteraction.TNT);
        List<PrimedTnt> lit = g.all(PrimedTnt.class, 8);
        helper.assertTrue(lit.size() == 1 && lit.get(0).getBlockState() == west, "a blast should light it and keep the way it faced: " + lit.size());
        helper.assertTrue(lit.get(0).getFuse() <= 30, "lit by a blast, the fuse should be short, it is " + lit.get(0).getFuse());
        lit.get(0).discard();

        // fire that burns the block away
        BlockPos burning = g.at(-4, 0, 0);
        level.setBlock(burning, west, Block.UPDATE_ALL);
        Method burn = FireBlock.class.getDeclaredMethod("checkBurnOut", Level.class, BlockPos.class, int.class, RandomSource.class, int.class);
        burn.setAccessible(true);
        burn.invoke(Blocks.FIRE, level, burning, 1, level.getRandom(), 0);
        lit = g.all(PrimedTnt.class, 8);
        helper.assertTrue(lit.size() == 1 && lit.get(0).getBlockState() == west, "fire that burns it away should light it: " + lit.size());
        helper.assertTrue(!level.getBlockState(burning).is(west.getBlock()), "the burnt block should be gone");
        lit.get(0).discard();

        // a dispenser
        BlockPos dispenser = g.at(0, 0, 6);
        level.setBlock(dispenser, Ids.with(s("dispenser"), "facing", "up"), Block.UPDATE_ALL);
        ((DispenserBlockEntity) level.getBlockEntity(dispenser)).insertItem(new ItemStack(TntMania.BLOCKS.get("cow_tnt")));
        level.setBlock(dispenser.east(), s("redstone_block"), Block.UPDATE_ALL);
        helper.succeedWhen(() -> {
            List<PrimedTnt> out = level.getEntitiesOfClass(PrimedTnt.class, new AABB(dispenser.above()).inflate(2));
            helper.assertTrue(out.size() == 1 && out.get(0).getBlockState().is(west.getBlock()), "the dispenser should put out a lit Cow TNT: " + out.size());
            out.get(0).discard();
        });
    }

    @GameTest(maxTicks = 100)
    public void whenTheFuseRunsOutTheModsEffectHappensAndNormalTntStaysNormal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground gold = Ground.make(level), plain = Ground.make(level);
        gold.set(2, 0, 0, s("oak_fence"));
        plain.set(1, 0, 0, s("glass"));
        level.setBlock(gold.c, tnt("midas_tnt"), STILL);
        ModTntBlock.prime(level, gold.c, tnt("midas_tnt"), null, false).setFuse(3);
        level.setBlock(gold.c, AIR, STILL);
        PrimedTnt vanilla = new PrimedTnt(level, plain.c.getX() + 0.5, plain.c.getY(), plain.c.getZ() + 0.5, null);
        vanilla.setFuse(3);
        level.addFreshEntity(vanilla);
        helper.runAfterDelay(12, () -> {
            helper.assertTrue(gold.count(3, "gold_block") > 30, "Midas TNT should leave gold, there are " + gold.count(3, "gold_block") + " gold blocks");
            helper.assertTrue(gold.is(2, 0, 0, "oak_fence"), "Midas TNT must not blow things up: the fence next to it is gone");
            helper.assertTrue(plain.air(1, 0, 0), "normal TNT should still blow up: the glass next to it is still there");
            helper.assertTrue(plain.count(3, "gold_block") == 0, "normal TNT must not make gold");
            helper.succeed();
        });
    }

    // ---- blasts ----------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 300)
    public void theBigBlastsDigARoundCraterButNotThroughBedrockOrObsidian(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground g = Ground.make(level, 14, 14, 12, "stone");
        g.set(0, -3, 0, s("bedrock"));
        g.set(3, 0, 0, s("obsidian"));
        g.fill(6, 0, 0, 6, 1, 0, AIR);
        Mob pig = g.mob("pig", 6, 0);
        Detonation d = g.boom("x10_tnt");
        when(helper, () -> {
            helper.assertTrue(g.air(0, 0, 0) && g.air(5, 0, 0) && g.air(0, -5, 0) && g.air(3, 3, 3) && g.air(0, 6, 0), "the inside of the crater should be empty");
            helper.assertTrue(g.is(11, 0, 0, "stone") && g.is(0, 11, 0, "stone") && g.is(7, 7, 7, "stone"), "rock outside the crater should stay");
            helper.assertTrue(g.is(0, -3, 0, "bedrock"), "bedrock must stay");
            helper.assertTrue(g.is(3, 0, 0, "obsidian"), "obsidian must stand up to a blast");
            helper.assertTrue(d.blocks > 1000 && d.blocks < 3300, "an 8-block crater should take about 1100 to 3000 blocks, it took " + d.blocks);
            helper.assertTrue(!pig.isAlive() || pig.getHealth() < pig.getMaxHealth(), "a pig next to the blast should be hurt");
        }, d);
    }

    @GameTest(maxTicks = 100)
    public void theSmallAndOddOnes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground safe = Ground.make(level), mining = Ground.make(level), silent = Ground.make(level), dud = Ground.make(level), x2 = Ground.make(level);
        Mob safePig = safe.mob("pig", 1, 0), miningPig = mining.mob("pig", 1, 0);
        Detonation a = safe.boom("safe_tnt"), b = mining.boom("mining_tnt"), c = silent.boom("silent_tnt"), e = dud.boom("dud_tnt"), f = x2.boom("x2_tnt");
        when(helper, () -> {
            helper.assertTrue(safe.count(4, "grass_block") == 81 && safePig.getHealth() < safePig.getMaxHealth(), "Safe TNT should hurt the pig and break nothing");
            helper.assertTrue(mining.air(0, -2, 0) && mining.air(2, -2, 0) && b.blocks > 100, "Mining TNT should break the ground, it broke " + b.blocks);
            helper.assertTrue(miningPig.getHealth() == miningPig.getMaxHealth(), "Mining TNT must not hurt the pig");
            helper.assertTrue(mining.dropped(Items.COBBLESTONE, 8) && mining.dropped(Items.DIRT, 8), "Mining TNT should drop what it breaks");
            helper.assertTrue(silent.air(0, -2, 0) && silent.is(4, -1, 0, "grass_block") && c.blocks > 40, "Silent TNT should remove a small ball of ground");
            helper.assertTrue(e.blocks == 0 && e.actions == 1 && dud.count(3, "grass_block") == 49, "the dud must do nothing but smoke");
            helper.assertTrue(x2.air(0, -1, 0) && f.actions == 1, "TNT x2 should blow a hole");
            helper.assertTrue(type("instant_tnt").fuse() == 1 && type("slow_tnt").fuse() == 600, "the instant and the slow one have the wrong fuse");
        }, a, b, c, e, f);
    }

    // ---- digging ---------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 400)
    public void theDiggingShapes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground drill = Ground.make(level, 8, 10, 6, "stone"), chimney = Ground.make(level, 8, 10, 6, "stone"), east = Ground.make(level, 8, 10, 6, "stone"),
                west = Ground.make(level, 8, 10, 6, "stone"), cross = Ground.make(level, 8, 10, 6, "stone"), breach = Ground.make(level, 8, 10, 6, "stone"),
                quarry = Ground.make(level, 8, 10, 6, "stone"), chunk = Ground.make(level, 8, 10, 6, "stone"), cube = Ground.make(level, 8, 10, 6, "stone"),
                crater = Ground.make(level, 13, 10, 6, "stone"), funnel = Ground.make(level, 10, 10, 6, "stone"), flat = Ground.make(level, 13, 10, 6, "stone"),
                pancake = Ground.make(level, 14, 10, 6, "stone"), moat = Ground.make(level, 12, 10, 6, "stone"), stairs = Ground.make(level, 8, 10, 6, "stone"),
                cheese = Ground.make(level, 8, 10, 8, "stone"), shell = Ground.make(level, 10, 10, 10, "stone"), trench = Ground.make(level, 8, 10, 6, "stone"),
                buster = Ground.make(level, 8, 16, 6, "stone"), cave = Ground.make(level, 10, 10, 10, "stone"), needle = Ground.make(level, 8, 10, 6, "stone");
        Detonation[] all = {
                drill.boom("drill_tnt"), chimney.boom("chimney_tnt"), east.boom("tunnel_tnt", Direction.EAST), west.boom("tunnel_tnt", Direction.WEST),
                cross.boom("cross_tunnel_tnt"), breach.boom("breach_tnt", Direction.EAST), quarry.boom("quarry_tnt"), chunk.boom("chunk_tnt"), cube.boom("cube_tnt"),
                crater.boom("crater_tnt"), funnel.boom("funnel_tnt"), flat.boom("flatten_tnt"), pancake.boom("pancake_tnt"), moat.boom("moat_tnt"),
                stairs.boom("staircase_tnt", Direction.EAST), cheese.boom("cheese_tnt"), shell.boom("shell_tnt"), trench.boom("trench_tnt", Direction.EAST),
                buster.boom("bunker_buster_tnt"), cave.boom("cave_tnt"), needle.boom("needle_tnt")};
        when(helper, () -> {
            helper.assertTrue(drill.air(0, -10, 0) && drill.air(1, -5, 1) && drill.air(-1, -1, -1) && drill.is(2, -5, 0, "stone") && drill.is(0, 3, 0, "stone"), "Drill TNT: a 3x3 hole straight down, nothing else");
            helper.assertTrue(needle.air(0, -10, 0) && needle.is(1, -5, 0, "stone"), "Needle TNT: a 1x1 hole straight down");
            helper.assertTrue(chimney.air(0, 6, 0) && chimney.air(1, 3, -1) && chimney.is(0, -1, 0, "stone") && chimney.is(2, 3, 0, "stone"), "Chimney TNT: a 3x3 hole straight up");
            helper.assertTrue(east.air(1, 0, 0) && east.air(8, 2, 1) && east.air(5, 1, -1) && east.is(3, 3, 0, "stone") && east.is(3, -1, 0, "stone")
                    && east.is(3, 0, 2, "stone") && east.is(-1, 0, 0, "stone"), "Tunnel TNT facing east: a 3x3 tunnel to the east only");
            helper.assertTrue(west.air(-5, 1, 0) && west.is(3, 1, 0, "stone"), "Tunnel TNT facing west should dig west");
            helper.assertTrue(cross.air(4, 1, 0) && cross.air(-4, 1, 0) && cross.air(0, 1, 4) && cross.air(0, 1, -4) && cross.is(4, 1, 4, "stone"), "Cross Tunnel TNT: four tunnels");
            helper.assertTrue(breach.air(1, 0, 0) && breach.air(4, 2, 1) && breach.is(5, 1, 0, "stone"), "Breach TNT: four blocks deep, no more");
            helper.assertTrue(quarry.air(5, -9, 5) && quarry.air(-5, -1, -5) && quarry.is(6, -5, 0, "stone") && quarry.is(0, 2, 0, "stone"), "Quarry TNT: an 11x11 pit");
            helper.assertTrue(chunk.air(3, -5, 3) && chunk.air(8, 4, 8) && chunk.air(0, -10, 0) && chunk.is(-1, -5, 0, "stone") && chunk.is(0, 0, -1, "stone"), "Chunk TNT: its own chunk and nothing next to it");
            helper.assertTrue(cube.air(3, 3, 3) && cube.air(-3, -3, 3) && cube.is(4, 0, 0, "stone") && cube.is(0, 4, 0, "stone"), "Cube TNT: a 7x7x7 cube");
            helper.assertTrue(crater.air(0, -5, 0) && crater.is(0, -6, 0, "stone") && crater.air(11, -1, 0) && crater.is(11, -2, 0, "stone") && crater.air(6, 3, 0), "Crater TNT: a shallow bowl");
            helper.assertTrue(funnel.air(0, -8, 0) && funnel.air(4, -4, 0) && funnel.is(4, -5, 0, "stone") && funnel.is(9, -1, 0, "stone"), "Funnel TNT: a pointed pit");
            helper.assertTrue(flat.air(5, 3, 5) && flat.air(0, 6, 0) && flat.is(5, -1, 5, "stone") && flat.is(13, 3, 13, "stone"), "Flatten TNT: everything above the ground, nothing below");
            helper.assertTrue(pancake.air(5, -1, 5) && pancake.air(5, -2, 5) && pancake.is(5, -3, 5, "stone") && pancake.is(5, 1, 5, "stone"), "Pancake TNT: a thin layer");
            helper.assertTrue(moat.air(10, -3, 0) && moat.air(0, -5, 9) && moat.is(7, -3, 0, "stone") && moat.is(0, -3, 0, "stone") && moat.is(10, -6, 0, "stone"), "Moat TNT: a ring-shaped ditch");
            helper.assertTrue(stairs.air(3, -3, 0) && stairs.air(3, 0, 1) && stairs.is(3, -4, 0, "stone") && stairs.is(3, 1, 0, "stone") && stairs.air(8, -8, -1), "Staircase TNT: steps down to the east");
            helper.assertTrue(cheese.air(1, 0, 0) != cheese.air(2, 0, 0) && cheese.air(0, 1, 0) != cheese.air(0, 2, 0), "Swiss Cheese TNT: every second block");
            helper.assertTrue(shell.air(7, 0, 0) && shell.air(0, 6, 0) && shell.is(5, 0, 0, "stone") && shell.is(2, 2, 2, "stone") && shell.is(9, 0, 0, "stone"), "Shell TNT: a thin shell with a ball left inside");
            helper.assertTrue(trench.air(3, -5, 1) && trench.air(8, 3, -1) && trench.is(3, -6, 0, "stone") && trench.is(3, 0, 2, "stone"), "Trench TNT: 3 wide and 5 deep");
            helper.assertTrue(buster.air(0, -5, 0) && buster.air(0, -12, 0) && buster.is(0, -15, 0, "stone") && buster.is(2, -5, 0, "stone"), "Bunker Buster TNT: a shaft 12 deep with the blast at the bottom");
            helper.assertTrue(cave.air(0, 7, 0) && cave.air(5, 2, 0) && cave.is(0, -1, 0, "stone") && cave.is(0, 9, 0, "stone") && cave.is(8, 3, 0, "stone"), "Cave TNT: a round room with a flat floor");
        }, all);
    }

    @GameTest(maxTicks = 300)
    public void thePickyOnesTakeOnlyWhatTheyAreFor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String[] ids = {"xray_tnt", "ore_miner_tnt", "lumberjack_tnt", "leaf_blower_tnt", "lawn_mower_tnt", "sponge_tnt", "extinguisher_tnt", "snow_plow_tnt",
                "stone_eater_tnt", "harvest_tnt", "glass_breaker_tnt", "lights_out_tnt", "bedrock_tnt"};
        Ground[] g = new Ground[ids.length];
        Detonation[] d = new Detonation[ids.length];
        for (int i = 0; i < ids.length; i++) {
            g[i] = Ground.make(level).decorate();
            if (ids[i].equals("sponge_tnt")) g[i].set(-2, 0, 4, Ids.with(s("oak_stairs"), "waterlogged", "true"));
            d[i] = g[i].boom(ids[i]);
        }
        when(helper, () -> {
            helper.assertTrue(g[0].air(2, -3, 2) && g[0].air(2, -1, 2) && g[0].is(3, -1, 3, "diamond_ore") && g[0].is(4, 0, 0, "oak_log"), "X-Ray TNT: stone and dirt go, the ore stays");
            helper.assertTrue(g[1].air(3, -1, 3) && g[1].is(2, -3, 2, "stone") && g[1].dropped(Items.DIAMOND, 3), "Ore Miner TNT: the ore goes and its diamond lies at the TNT");
            helper.assertTrue(g[2].air(4, 0, 0) && g[2].air(4, 1, 0) && g[2].dropped(Items.OAK_LOG, 3) && g[2].is(1, 0, 5, "glass"), "Lumberjack TNT: the tree goes, the log lies at the TNT");
            helper.assertTrue(g[3].air(4, 1, 0) && g[3].is(4, 0, 0, "oak_log"), "Leaf Blower TNT: leaves go, the trunk stays");
            helper.assertTrue(g[4].air(3, 0, 5) && g[4].is(5, 0, 5, "wheat") && g[4].is(3, -1, 5, "grass_block"), "Lawn Mower TNT: grass goes, crops and ground stay");
            helper.assertTrue(g[5].air(-3, -2, -3) && g[5].is(-2, 0, 4, "oak_stairs") && g[5].get(-2, 0, 4).getFluidState().isEmpty(), "Sponge TNT: the water goes, also out of the stairs");
            helper.assertTrue(g[6].air(-4, -2, -4) && g[6].is(-3, -2, -3, "water"), "Extinguisher TNT: lava goes, water stays");
            helper.assertTrue(g[7].air(0, 0, 5) && g[7].is(1, 0, 5, "glass"), "Snow Plow TNT: snow goes");
            helper.assertTrue(g[8].air(2, -3, 2) && g[8].is(2, -1, 2, "grass_block") && g[8].is(3, -1, 3, "diamond_ore"), "Stone Eater TNT: only stone goes");
            helper.assertTrue(g[9].is(5, 0, 5, "wheat") && g[9].get(5, 0, 5) == Ids.with(s("wheat"), "age", "0") && g[9].dropped(Items.WHEAT, 10), "Harvest TNT: wheat is dropped and planted again");
            helper.assertTrue(g[10].air(1, 0, 5) && g[10].is(2, 0, 5, "torch"), "Glass Breaker TNT: only glass goes");
            helper.assertTrue(g[11].air(2, 0, 5) && g[11].is(1, 0, 5, "glass") && g[11].is(-4, -2, -4, "lava"), "Lights Out TNT: the torch goes, lava stays");
            helper.assertTrue(g[12].air(1, -1, 0) && g[12].is(2, -1, 2, "grass_block") && d[12].blocks == 1, "Bedrock Breaker TNT: the bedrock goes and nothing else");
            for (int i = 0; i < 12; i++) helper.assertTrue(g[i].is(1, -1, 0, "bedrock"), ids[i] + " must not break bedrock");
        }, d);
    }

    // ---- changing blocks -----------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 300)
    public void theOnesThatTurnBlocksIntoOtherBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground midas = Ground.make(level), rainbow = Ground.make(level), infection = Ground.make(level), paint = Ground.make(level),
                coal = Ground.make(level, 12, 10, 0, "deepslate"), quartz = Ground.make(level, 12, 10, 0, "netherrack"), jackpot = Ground.make(level),
                debris = Ground.make(level, 12, 10, 0, "netherrack"), diamond = Ground.make(level);
        midas.set(1, -1, 0, s("bedrock"));
        midas.set(-1, -1, 0, s("chest"));
        midas.set(0, 0, 1, s("torch"));
        Detonation[] d = {midas.boom("midas_tnt"), rainbow.boom("rainbow_tnt"), infection.boom("infection_tnt"), paint.boom("red_paint_tnt"),
                coal.boom("coal_ore_tnt"), quartz.boom("quartz_ore_tnt"), jackpot.boom("jackpot_tnt"), debris.boom("debris_tnt"), diamond.boom("diamond_ore_tnt")};
        when(helper, () -> {
            helper.assertTrue(midas.is(0, -1, 0, "gold_block") && midas.is(0, -3, 0, "gold_block") && midas.is(4, -1, 0, "grass_block"), "Midas TNT: gold within 3 blocks, not further");
            helper.assertTrue(midas.is(1, -1, 0, "bedrock") && midas.is(-1, -1, 0, "chest") && midas.is(0, 0, 1, "torch"), "Midas TNT must leave bedrock, chests and torches alone");
            helper.assertTrue(rainbow.countEnding(6, "_wool") > 300 && rainbow.count(6, "red_wool") > 5 && rainbow.count(6, "blue_wool") > 5, "Rainbow TNT: wool of many colours");
            helper.assertTrue(infection.count(3, "tnt") > 30 && infection.all(PrimedTnt.class, 8).isEmpty(), "Infection TNT: more TNT, and none of it lit");
            helper.assertTrue(paint.count(5, "red_concrete") > 200, "Red Paint TNT: red concrete, there are " + paint.count(5, "red_concrete"));
            helper.assertTrue(coal.count(6, "deepslate_coal_ore") > 10 && coal.count(6, "coal_ore") == 0, "Coal Ore TNT in deepslate should make deepslate coal ore: " + coal.count(6, "deepslate_coal_ore"));
            helper.assertTrue(quartz.count(6, "nether_quartz_ore") > 10, "Quartz Ore TNT should make quartz ore in netherrack: " + quartz.count(6, "nether_quartz_ore"));
            helper.assertTrue(jackpot.countEnding(6, "_ore") > 10 && d[6].blocks == jackpot.countEnding(6, "_ore"), "Jackpot TNT should make a mix of ores: " + jackpot.countEnding(6, "_ore"));
            helper.assertTrue(debris.count(8, "ancient_debris") == d[7].blocks && d[7].blocks < 40, "Ancient Debris TNT should make only a little: " + d[7].blocks);
            helper.assertTrue(diamond.count(6, "diamond_ore") == d[8].blocks && d[8].blocks < 30 && diamond.is(0, -1, 0, "grass_block"), "Diamond Ore TNT should make only a little, in stone only: " + d[8].blocks);
        }, d);
    }

    @GameTest(maxTicks = 300)
    public void theGroundChangersAndLiquids(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground snow = Ground.make(level), desert = Ground.make(level), flower = Ground.make(level), mushroom = Ground.make(level), plow = Ground.make(level),
                cake = Ground.make(level), water = Ground.make(level), flood = Ground.make(level), lava = Ground.make(level), fire = Ground.make(level),
                ice = Ground.make(level), web = Ground.make(level), torch = Ground.make(level, 12, 10, 0, "stone");
        snow.set(3, 0, 3, Ids.with(s("oak_leaves"), "persistent", "true"));
        flood.fill(3, -3, 3, 6, -1, 6, AIR);
        ice.set(3, -1, 3, s("water"));
        ice.set(-3, -1, -3, s("lava"));
        Mob cold = ice.mob("pig", 1, 1);
        torch.fill(-12, 4, -12, 12, 4, 12, s("stone"));
        torch.fill(-12, 0, -12, 12, 3, -12, s("stone"));
        torch.fill(-12, 0, 12, 12, 3, 12, s("stone"));
        torch.fill(-12, 0, -12, -12, 3, 12, s("stone"));
        torch.fill(12, 0, -12, 12, 3, 12, s("stone"));
        Detonation[] d = {snow.boom("snow_tnt"), desert.boom("desert_tnt"), flower.boom("flower_tnt"), mushroom.boom("mushroom_tnt"), plow.boom("plow_tnt"),
                cake.boom("cake_tnt"), water.boom("water_tnt"), flood.boom("flood_tnt"), lava.boom("lava_tnt"), fire.boom("fire_tnt"), ice.boom("ice_tnt"),
                web.boom("cobweb_tnt"), torch.boom("torch_tnt")};
        // the cold wears off again, so the pig is looked at right away
        helper.assertTrue(cold.getTicksFrozen() > 0 && cold.hasEffect(Ids.effect("slowness")), "Ice TNT: the pig should be freezing and slow at once");
        when(helper, () -> {
            helper.assertTrue(snow.is(2, 0, 2, "snow") && snow.is(-8, 0, 5, "snow") && snow.is(2, -1, 2, "grass_block") && snow.is(3, -1, 3, "grass_block"), "Snow TNT: a layer of snow on the ground, the ground stays");
            helper.assertTrue(desert.is(2, -1, 2, "sand") && desert.is(0, -2, 0, "stone") && desert.count(12, "sand") > 300, "Desert TNT: sand on top only");
            int flowers = flower.count(12, "poppy") + flower.count(12, "dandelion") + flower.count(12, "cornflower") + flower.count(12, "allium") + flower.count(12, "red_tulip");
            helper.assertTrue(flowers > 30 && flower.is(2, -1, 2, "grass_block"), "Flower TNT: flowers on the grass, there are " + flowers);
            helper.assertTrue(mushroom.is(2, -1, 2, "mycelium") && mushroom.count(12, "red_mushroom") + mushroom.count(12, "brown_mushroom") > 3, "Mushroom TNT: mycelium with mushrooms");
            helper.assertTrue(plow.is(2, -1, 2, "farmland") && plow.is(11, -1, 0, "grass_block"), "Plow TNT: farmland within 10 blocks");
            helper.assertTrue(cake.count(6, "cake") > 10, "Cake TNT: cakes, there are " + cake.count(6, "cake"));
            helper.assertTrue(water.is(0, 0, 0, "water") && water.is(0, 2, 0, "water") && water.count(4, "water") >= 50, "Water TNT: a ball of water above the ground");
            helper.assertTrue(flood.is(4, -3, 4, "water") && flood.is(5, -1, 5, "water") && flood.is(-3, 0, -3, "water"), "Flood TNT: the hole and the ground around it under water");
            helper.assertTrue(lava.is(0, 0, 0, "lava") && lava.count(3, "lava") >= 10, "Lava TNT: a ball of lava");
            helper.assertTrue(d[9].blocks > 30 && fire.count(8, "fire") > 5, "Fire TNT: fire on the ground, it lit " + d[9].blocks);
            helper.assertTrue(ice.is(3, -1, 3, "ice") && ice.is(-3, -1, -3, "obsidian") && ice.is(2, 0, 2, "snow"), "Ice TNT: ice, obsidian and snow: " + ice.get(3, -1, 3) + " " + ice.get(-3, -1, -3) + " " + ice.get(2, 0, 2));
            helper.assertTrue(web.count(4, "cobweb") > 40 && web.is(0, -1, 0, "grass_block"), "Cobweb TNT: webs in the air only");
            int torches = torch.count(12, "torch");
            helper.assertTrue(torches >= 9 && torches <= 30, "Torch TNT in a dark room: torches on a grid, there are " + torches);
        }, d);
    }

    // ---- building ----------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 300)
    public void theBuilders(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        List<TntType> builders = new ArrayList<>();
        for (TntType t : Catalog.all()) {
            if (t.kind() == Kind.BUILD) builders.add(t);
        }
        helper.assertTrue(builders.size() == 28, "there should be 28 builders, there are " + builders.size());
        java.util.Map<String, Ground> g = new java.util.HashMap<>();
        java.util.Map<String, Detonation> d = new java.util.HashMap<>();
        for (TntType t : builders) {
            Ground ground = Ground.make(level);
            if (t.what().equals("cage")) {
                ground.mob("pig", 3, 3);
                ground.mob("cow", -4, 2);
            }
            g.put(t.what(), ground);
            d.put(t.what(), ground.boom(t.id(), Direction.EAST));
        }
        // east is "forward" here: right is south, so (right, up, forward) is (z, y, x)
        helper.runAfterDelay(60, () -> {
            for (TntType t : builders) {
                Detonation blast = d.get(t.what());
                helper.assertTrue(blast.error == null, t.id() + " went wrong: " + blast.error);
                helper.assertTrue(blast.blocks >= 20, t.id() + " should build something, it placed only " + blast.blocks + " blocks");
            }
            helper.assertTrue(Ids.PROBLEMS.isEmpty(), "every block the buildings use should be known to the game." + problems());

            Ground house = g.get("house");
            helper.assertTrue(house.is(-3, 0, 0, "oak_door") && house.is(-3, 1, 0, "oak_door") && house.air(0, 1, 0) && house.is(1, 0, 2, "red_bed") && house.is(2, 0, 2, "red_bed")
                    && house.is(2, 0, -2, "chest") && house.is(3, 1, 0, "glass_pane") && house.is(0, 4, 0, "oak_planks") && house.is(3, 0, 3, "oak_log"), "House TNT: door, bed, chest, window, roof");
            Ground tower = g.get("tower");
            helper.assertTrue(tower.is(1, 5, 0, "ladder") && tower.is(1, 16, 0, "ladder") && tower.is(2, 8, 2, "cobblestone") && tower.air(0, 8, 0) && tower.air(-2, 1, 0), "Tower TNT: walls, ladder to the top, doorway");
            helper.assertTrue(g.get("wall").is(2, 4, 10, "stone_bricks") && g.get("wall").is(2, 0, -10, "stone_bricks") && g.get("wall").air(1, 1, 0), "Wall TNT: a wall across, 2 blocks ahead");
            helper.assertTrue(g.get("bridge").is(48, -1, 2, "oak_planks") && g.get("bridge").is(20, 0, -2, "oak_fence") && g.get("bridge").air(20, 0, 0), "Bridge TNT: 48 blocks of deck with rails");
            helper.assertTrue(g.get("platform").count(8, "smooth_stone") == 225, "Platform TNT: 15x15");
            Ground sky = g.get("sky_platform");
            helper.assertTrue(sky.is(0, 20, 0, "cobblestone") && sky.is(-1, 20, 0, "ladder") && sky.is(-1, 40, 0, "ladder") && sky.is(3, 40, 3, "smooth_stone") && sky.is(4, 41, 0, "oak_fence"), "Sky Platform TNT: pillar, ladder, platform with a rail");
            helper.assertTrue(g.get("stairway").is(1, 0, 0, "oak_stairs") && g.get("stairway").is(40, 39, -1, "oak_stairs"), "Stairway TNT: 40 steps up");
            helper.assertTrue(g.get("pyramid").is(0, 7, 0, "chiseled_sandstone") && g.get("pyramid").is(7, 0, 7, "sandstone") && g.get("pyramid").air(7, 1, 7), "Pyramid TNT");
            helper.assertTrue(g.get("dome").is(8, 0, 0, "glass") && g.get("dome").is(0, 8, 0, "glass") && g.get("dome").air(0, 3, 0), "Dome TNT: a glass dome, empty inside");
            helper.assertTrue(g.get("bunker").is(3, 2, 3, "obsidian") && g.get("bunker").is(0, 4, 0, "obsidian") && g.get("bunker").air(0, 1, 0) && g.get("bunker").air(-3, 1, 0), "Bunker TNT: obsidian box with a doorway");
            helper.assertTrue(g.get("bubble").is(0, 12, 0, "glass") && g.get("bubble").is(6, 6, 0, "glass") && g.get("bubble").air(0, 6, 0), "Bubble TNT: a hollow glass ball");
            helper.assertTrue(g.get("well").is(0, -3, 0, "water") && g.get("well").is(1, 0, 0, "cobblestone") && g.get("well").is(1, 2, 1, "oak_fence") && g.get("well").is(0, 3, 0, "cobblestone_slab"), "Well TNT");
            helper.assertTrue(g.get("pool").count(4, "water") == 147 && g.get("pool").is(4, -1, 0, "quartz_block"), "Pool TNT: 7x7x3 of water in quartz, there is " + g.get("pool").count(4, "water"));
            Ground farm = g.get("farm");
            helper.assertTrue(farm.count(5, "farmland") == 80 && farm.count(5, "wheat") == 80 && farm.is(0, -1, 0, "water") && farm.is(5, 0, 2, "oak_fence") && farm.is(-5, 0, 0, "oak_fence_gate"), "Farm TNT: 80 planted fields, water, fence, gate");
            helper.assertTrue(g.get("igloo").is(4, 0, 0, "snow_block") && g.get("igloo").is(0, 4, 0, "snow_block") && g.get("igloo").air(-4, 0, 0) && g.get("igloo").count(5, "red_bed") == 2, "Igloo TNT: snow dome, doorway, bed");
            Ground maze = g.get("maze");
            helper.assertTrue(maze.is(-12, 1, 0, "oak_leaves") && maze.air(-12, 1, -1) && maze.air(12, 1, 1) && maze.air(-11, 1, -11) && maze.count(12, "oak_leaves") > 600, "Maze TNT: hedges with a way in and a way out");
            helper.assertTrue(g.get("smiley").is(0, -1, 0, "yellow_wool") && g.get("smiley").is(2, -1, 3, "black_wool") && g.get("smiley").is(-3, -1, 0, "black_wool"), "Smiley TNT: yellow face, black eyes and mouth");
            helper.assertTrue(g.get("snowman").is(0, 3, 0, "snow_block") && g.get("snowman").is(-2, 12, 0, "carved_pumpkin") && g.get("snowman").is(0, 13, 0, "snow_block"),
                    "Snowman TNT: three balls and a pumpkin face: " + g.get("snowman").get(0, 3, 0) + " " + g.get("snowman").get(-2, 12, 0) + " " + g.get("snowman").get(0, 13, 0));
            Ground castle = g.get("castle");
            helper.assertTrue(castle.is(12, 3, 0, "stone_bricks") && castle.air(-12, 2, 0) && castle.is(10, 9, 12, "cobblestone") && castle.is(-9, 5, 10, "ladder") && castle.air(0, 3, 0) && castle.count(12, "torch") > 10, "Castle TNT: walls, gate, towers with ladders, torches");
            Ground island = g.get("island");
            helper.assertTrue(island.is(0, 30, 0, "grass_block") || island.is(0, 30, 0, "dirt"), "Island TNT: land 30 blocks up");
            helper.assertTrue(island.is(5, 29, 0, "dirt") && island.is(0, 25, 0, "stone") && island.air(0, 15, 0) && d.get("island").spawned == 1, "Island TNT: dirt, a stone underside, and a tree");
            Ground portal = g.get("portal");
            helper.assertTrue(portal.count(6, "nether_portal") == 6 && portal.is(2, 0, 0, "obsidian") && portal.is(2, 4, 2, "obsidian"), "Portal TNT: a lit portal, it has " + portal.count(6, "nether_portal") + " portal blocks");
            Ground rail = g.get("railway");
            helper.assertTrue(rail.is(1, 0, 0, "powered_rail") && rail.get(1, 0, 0) == Ids.with(Ids.with(s("powered_rail"), "shape", "east_west"), "powered", "true")
                    && rail.is(2, 0, 0, "rail") && rail.is(64, 0, 0, "rail") && rail.is(1, -1, 0, "redstone_block") && rail.air(30, 1, 0), "Railway TNT: 64 blocks of rail, powered every fourth");
            Ground mines = g.get("minefield");
            int plates = mines.count(9, "stone_pressure_plate");
            helper.assertTrue(plates >= 6 && mines.count(9, "tnt") == plates && mines.all(PrimedTnt.class, 12).isEmpty(), "Minefield TNT: plates with TNT under them, none lit: " + plates);
            helper.assertTrue(g.get("cage").count(8, "iron_bars") > 30 && d.get("cage").touched == 2, "Cage TNT: both mobs caged, " + d.get("cage").touched);
            helper.assertTrue(g.get("volcano").is(0, 9, 0, "lava") && g.get("volcano").count(9, "basalt") > 300 && g.get("volcano").count(10, "lava") >= 5, "Volcano TNT: a cone with lava running down");
            Ground statue = g.get("statue");
            helper.assertTrue(statue.is(-6, 6, 0, "white_wool") || statue.is(-6, 6, 0, "black_wool"), "Giant TNT Statue: a white band");
            helper.assertTrue(statue.is(6, 1, 0, "red_wool") && statue.count(9, "black_wool") == 4 * 26 - 1 && statue.air(0, 6, 0), "Giant TNT Statue: red, with TNT written on four sides (" + statue.count(9, "black_wool") + " black blocks)");
            helper.assertTrue(g.get("parkour").is(2, 0, 0, "red_concrete") && d.get("parkour").blocks == 30 + 9 + 1, "Parkour TNT: 30 jumps, a platform and a cake: " + d.get("parkour").blocks);
            Ground lift = g.get("elevator");
            helper.assertTrue(lift.is(0, -1, 0, "soul_sand") && lift.is(-1, 0, 0, "oak_door") && lift.is(1, 15, 0, "glass") && lift.is(2, 30, 2, "stone_bricks"), "Elevator TNT: tube, door, soul sand, platform");
            helper.assertTrue(lift.is(0, 5, 0, "bubble_column") && lift.is(0, 30, 0, "bubble_column"), "Elevator TNT: the water should bubble all the way up, it is " + lift.get(0, 5, 0));
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 300)
    public void thePlantOnes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground tree = Ground.make(level), forest = Ground.make(level), cherry = Ground.make(level), mushroom = Ground.make(level), meal = Ground.make(level);
        meal.set(2, -1, 2, Ids.with(s("farmland"), "moisture", "7"));
        meal.set(2, 0, 2, s("wheat"));
        meal.set(-3, 0, -3, s("short_grass"));
        Detonation[] d = {tree.boom("tree_tnt"), forest.boom("forest_tnt"), cherry.boom("cherry_tnt"), mushroom.boom("giant_mushroom_tnt"), meal.boom("bonemeal_tnt")};
        when(helper, () -> {
            helper.assertTrue(d[0].spawned == 1 && tree.is(0, 0, 0, "oak_log") && tree.count(8, "oak_leaves") > 10, "Tree TNT: an oak where it stood");
            helper.assertTrue(d[1].spawned >= 5 && forest.count(12, "oak_log") + forest.count(12, "birch_log") > 15, "Forest TNT: many trees, it grew " + d[1].spawned);
            helper.assertTrue(d[2].spawned >= 2 && cherry.count(12, "cherry_log") > 5, "Cherry Blossom TNT: cherry trees, it grew " + d[2].spawned);
            helper.assertTrue(d[3].spawned >= 2 && mushroom.count(10, "mushroom_stem") > 5, "Giant Mushroom TNT: it grew " + d[3].spawned);
            helper.assertTrue(meal.is(2, 0, 2, "wheat") && meal.get(2, 0, 2) != s("wheat") && d[4].blocks >= 2, "Bonemeal TNT: the wheat should have grown, it is " + meal.get(2, 0, 2));
        }, d);
    }

    // ---- mobs ----------------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 100)
    public void everyMobTntSpawnsItsMobs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        int kinds = 0;
        for (TntType t : Catalog.all()) {
            if (t.kind() != Kind.MOB) continue;
            kinds++;
            Ground g = Ground.make(level);
            Detonation d = g.boom(t.id());
            helper.assertTrue(d.error == null && d.spawned == t.count(), t.id() + " should spawn " + t.count() + ", it spawned " + d.spawned + " " + d.error);
            List<Mob> mobs = g.all(Mob.class, 12);
            helper.assertTrue(mobs.size() >= t.count(), t.id() + ": " + mobs.size() + " mobs are standing there");
            if (t.id().equals("charged_creeper_tnt")) {
                for (Mob mob : mobs) helper.assertTrue(mob instanceof Creeper creeper && creeper.isPowered(), "the creepers should be charged");
            }
            if (t.id().equals("zoo_tnt")) {
                Set<Object> different = new HashSet<>();
                for (Mob mob : mobs) different.add(mob.getType());
                helper.assertTrue(different.size() == 14, "the zoo should have 14 different animals, it has " + different.size());
            }
            for (Mob mob : mobs) {
                helper.assertTrue(Math.abs(mob.getY() - g.c.getY()) < 1.5, t.id() + ": a mob stands in the wrong place, at height " + (mob.getY() - g.c.getY()));
                mob.discard();
            }
        }
        helper.assertTrue(kinds == 27, "there should be 27 mob TNTs, there are " + kinds);
        helper.succeed();
    }

    @GameTest(maxTicks = 100)
    public void everyPotionTntGivesItsEffect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground g = Ground.make(level), heal = Ground.make(level), harm = Ground.make(level);
        int kinds = 0;
        for (TntType t : Catalog.all()) {
            if (t.kind() != Kind.POTION) continue;
            kinds++;
            Mob pig = g.mob("pig", 2, 0), far = g.mob("pig", 11, 11);
            if (t.n() > 12) far.snapTo(far.getX() + 20, far.getY(), far.getZ());
            Detonation d = g.boom(t.id());
            helper.assertTrue(d.error == null && d.touched == 1, t.id() + " should reach the pig next to it and not the one far away: " + d.touched + " " + d.error);
            for (String spec : Catalog.list(t.what())) {
                String[] part = spec.split(":");
                Holder<MobEffect> effect = Ids.effect(part[0]);
                if (effect.value().isInstantaneous()) continue;
                helper.assertTrue(pig.hasEffect(effect), t.id() + ": the pig should have " + part[0]);
                int strength = part.length > 2 ? Integer.parseInt(part[2]) : t.count();
                helper.assertTrue(pig.getEffect(effect).getAmplifier() == strength && pig.getEffect(effect).getDuration() == Integer.parseInt(part[1]) * 20,
                        t.id() + ": " + part[0] + " has the wrong strength or time");
                helper.assertTrue(!far.hasEffect(effect), t.id() + ": the pig far away must not get it");
            }
            pig.discard();
            far.discard();
        }
        helper.assertTrue(kinds == 22, "there should be 22 potion TNTs, there are " + kinds);
        Mob hurt = heal.mob("pig", 1, 0), healthy = harm.mob("pig", 1, 0);
        hurt.setHealth(2f);
        heal.boom("healing_tnt");
        harm.boom("harming_tnt");
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(hurt.getHealth() > 2f, "Healing TNT should heal the pig, it has " + hurt.getHealth());
            helper.assertTrue(!healthy.isAlive() || healthy.getHealth() < healthy.getMaxHealth(), "Harming TNT should hurt the pig");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void theMobTricks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        Ground push = Ground.make(level), up = Ground.make(level), pull = Ground.make(level), tele = Ground.make(level), butcher = Ground.make(level),
                love = Ground.make(level), milk = Ground.make(level), burn = Ground.make(level), big = Ground.make(level), tiny = Ground.make(level),
                magnet = Ground.make(level), clean = Ground.make(level), xp = Ground.make(level), pinata = Ground.make(level);

        Mob pushed = push.mob("pig", 3, 0), lifted = up.mob("pig", 2, 0), pulled = pull.mob("pig", 6, 0);
        push.boom("knockback_tnt");
        up.boom("launch_tnt");
        pull.boom("vacuum_tnt");
        helper.assertTrue(pushed.getDeltaMovement().x > 0.5 && pushed.getDeltaMovement().y > 0, "Knockback TNT should throw the pig away, it moves " + pushed.getDeltaMovement());
        helper.assertTrue(lifted.getDeltaMovement().y > 1 && Math.abs(lifted.getDeltaMovement().x) < 0.01, "Launch TNT should throw the pig straight up, it moves " + lifted.getDeltaMovement());
        helper.assertTrue(pulled.getDeltaMovement().x < -0.5, "Vacuum TNT should pull the pig in, it moves " + pulled.getDeltaMovement());
        helper.assertTrue(push.count(3, "grass_block") == 49, "Knockback TNT must not break blocks");

        Mob jumper = tele.mob("pig", 1, 0);
        Vec3 before = jumper.position();
        Detonation t = tele.boom("teleport_tnt");
        helper.assertTrue(t.touched == 1 && jumper.position().distanceTo(before) > 1, "Teleport TNT should move the pig somewhere else");

        Mob spider = butcher.mob("spider", 2, 0), safePig = butcher.mob("pig", -2, 0), villager = butcher.mob("villager", 0, 3);
        butcher.boom("butcher_tnt");
        helper.assertTrue(!spider.isAlive() && safePig.isAlive() && villager.isAlive() && safePig.getHealth() == safePig.getMaxHealth(), "Butcher TNT: the spider dies, the pig and the villager live");

        Animal inLove = (Animal) love.mob("pig", 2, 0);
        love.boom("love_tnt");
        helper.assertTrue(inLove.isInLove(), "Love TNT: the pig should be in love");

        Mob sick = milk.mob("pig", 1, 0);
        sick.addEffect(new net.minecraft.world.effect.MobEffectInstance(Ids.effect("poison"), 600, 1));
        milk.boom("milk_tnt");
        helper.assertTrue(sick.getActiveEffects().isEmpty(), "Milk TNT should clear the poison");

        Mob burning = burn.mob("pig", 2, 0);
        ServerPlayer bystander = helper.makeMockServerPlayerInLevel();
        bystander.snapTo(burn.c.getX() + 0.5, burn.c.getY(), burn.c.getZ() + 2.5);
        burn.boom("burn_tnt");
        helper.assertTrue(burning.isOnFire() && !bystander.isOnFire() && burn.count(4, "fire") == 0, "Burn TNT: the pig burns, the player and the ground do not");

        Mob giant = big.mob("pig", 1, 0), dwarf = tiny.mob("pig", 1, 0);
        big.boom("big_tnt");
        tiny.boom("tiny_tnt");
        helper.assertTrue(Math.abs(giant.getAttributeValue(Attributes.SCALE) - 3.0) < 0.001, "Big TNT: the pig should be 3 times its size, it is " + giant.getAttributeValue(Attributes.SCALE));
        helper.assertTrue(Math.abs(dwarf.getAttributeValue(Attributes.SCALE) - 0.3) < 0.001, "Tiny TNT: the pig should be 0.3 times its size, it is " + dwarf.getAttributeValue(Attributes.SCALE));
        big.boom("big_tnt");
        helper.assertTrue(Math.abs(giant.getAttributeValue(Attributes.SCALE) - 3.0) < 0.001, "a second Big TNT must not make it 9 times its size");

        ItemEntity stick = magnet.item(Items.STICK, 9, 9), trash = clean.item(Items.STICK, 5, 5);
        magnet.boom("magnet_tnt");
        clean.boom("cleaner_tnt");
        helper.assertTrue(stick.isAlive() && stick.position().distanceTo(Vec3.atBottomCenterOf(magnet.c)) < 1, "Magnet TNT should bring the stick to the TNT");
        helper.assertTrue(!trash.isAlive(), "Cleaner TNT should delete the stick");

        xp.boom("xp_tnt");
        int worth = 0;
        for (ExperienceOrb orb : xp.all(ExperienceOrb.class, 6)) worth += orb.getValue();
        helper.assertTrue(worth == 500, "XP TNT should leave 500 experience, it left " + worth);

        Detonation p = pinata.boom("pinata_tnt");
        helper.assertTrue(p.spawned >= 12 && pinata.all(ItemEntity.class, 6).size() >= 6, "Pinata TNT should drop its treats: " + p.spawned);
        helper.assertTrue(Ids.PROBLEMS.isEmpty(), "every treat should be an item the game knows." + problems());
        helper.succeed();
    }

    // ---- from the sky -----------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 500)
    public void theOnesFromTheSky(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground zap = Ground.make(level), meteor = Ground.make(level), arrows = Ground.make(level), anvils = Ground.make(level), carpet = Ground.make(level),
                cluster = Ground.make(level), bigCluster = Ground.make(level), fractal = Ground.make(level), fire = Ground.make(level), year = Ground.make(level),
                snow = Ground.make(level), egg = Ground.make(level);
        Detonation z = zap.boom("lightning_tnt"), m = meteor.boom("meteor_tnt"), a = arrows.boom("arrow_rain_tnt"), an = anvils.boom("anvil_rain_tnt"),
                f = fire.boom("firework_tnt"), y = year.boom("new_year_tnt");

        Detonation cb = carpet.boom("carpet_bomb_tnt");
        List<PrimedTnt> falling = level.getEntitiesOfClass(PrimedTnt.class, new AABB(carpet.c).inflate(20, 60, 20));
        helper.assertTrue(cb.spawned == 25 && falling.size() == 25 && falling.get(0).getY() > carpet.c.getY() + 30, "Carpet Bomb TNT: 25 TNT high in the sky, there are " + falling.size());
        falling.forEach(Entity::discard);

        Detonation c = cluster.boom("cluster_tnt"), bc = bigCluster.boom("big_cluster_tnt"), fr = fractal.boom("fractal_tnt");
        List<PrimedTnt> small = cluster.all(PrimedTnt.class, 6), many = bigCluster.all(PrimedTnt.class, 6), five = fractal.all(PrimedTnt.class, 6);
        helper.assertTrue(c.spawned == 12 && small.size() == 12 && small.get(0).getBlockState().is(Blocks.TNT), "Cluster TNT: 12 normal TNT, there are " + small.size());
        helper.assertTrue(bc.spawned == 40 && many.size() == 40, "Big Cluster TNT: 40 TNT, there are " + many.size());
        helper.assertTrue(fr.spawned == 5 && five.size() == 5 && five.get(0).getBlockState().is(TntMania.BLOCKS.get("cluster_tnt")), "Fractal TNT: 5 Cluster TNT, there are " + five.size());
        small.forEach(Entity::discard);
        many.forEach(Entity::discard);
        five.forEach(Entity::discard);

        Detonation sb = snow.boom("snowball_tnt"), eg = egg.boom("egg_tnt");
        helper.assertTrue(sb.spawned == 150 && snow.all(Snowball.class, 6).size() == 150, "Snowball TNT: 150 snowballs");
        helper.assertTrue(eg.spawned == 60 && egg.all(ThrownEgg.class, 6).size() == 60, "Egg TNT: 60 eggs");

        helper.runAfterDelay(8, () -> {
            helper.assertTrue(level.getEntitiesOfClass(Arrow.class, new AABB(arrows.c).inflate(14, 60, 14)).size() > 10, "Arrow Rain TNT: arrows should be in the air");
            helper.assertTrue(!level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(anvils.c).inflate(12, 60, 12)).isEmpty(), "Anvil Rain TNT: anvils should be falling");
            helper.assertTrue(!level.getEntitiesOfClass(FireworkRocketEntity.class, new AABB(fire.c).inflate(12, 40, 12)).isEmpty(), "Firework TNT: rockets should be flying");
            helper.assertTrue(!level.getEntitiesOfClass(LightningBolt.class, new AABB(zap.c).inflate(10, 20, 10)).isEmpty(), "Lightning TNT: a bolt should be striking");
        });
        when(helper, () -> {
            helper.assertTrue(z.spawned == 8, "Lightning TNT: 8 bolts, there were " + z.spawned);
            helper.assertTrue(m.spawned == 30, "Meteor TNT: 30 fireballs, there were " + m.spawned);
            helper.assertTrue(a.spawned == 150, "Arrow Rain TNT: 150 arrows, there were " + a.spawned);
            helper.assertTrue(an.spawned == 20 && anvils.countEnding(12, "anvil") > 5, "Anvil Rain TNT: 20 anvils, " + an.spawned + " fell and " + anvils.countEnding(12, "anvil") + " lie there");
            helper.assertTrue(f.spawned == 25, "Firework TNT: 25 rockets, there were " + f.spawned);
            helper.assertTrue(y.spawned == 120, "New Year TNT: 120 rockets, there were " + y.spawned);
            helper.assertTrue(fire.count(8, "grass_block") == 289, "fireworks must not break the ground");
        }, z, m, a, an, f, y);
    }

    // ---- moving and repeating ------------------------------------------------------------------------------------------------------

    @GameTest(maxTicks = 400)
    public void theOnesThatMove(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        Ground balloon = Ground.make(level), rocket = Ground.make(level), missile = Ground.make(level), homing = Ground.make(level), hop = Ground.make(level);
        PrimedTnt floating = ModTntBlock.prime(level, balloon.c, tnt("floating_tnt"), null, false);
        PrimedTnt flying = ModTntBlock.prime(level, rocket.c, tnt("rocket_tnt"), null, false);
        missile.fill(8, 0, -2, 8, 4, 2, s("glass"));
        PrimedTnt shot = ModTntBlock.prime(level, missile.at(0, 1, 0), tnt("missile_tnt").setValue(ModTntBlock.FACING, Direction.EAST), null, false);
        homing.fill(-12, 5, -12, 12, 5, 12, s("stone"));
        Mob spider = homing.mob("spider", 9, 0);
        PrimedTnt seeker = ModTntBlock.prime(level, homing.c, tnt("homing_tnt"), null, false);
        ModTntBlock.prime(level, hop.c, tnt("jumping_tnt"), null, false).setFuse(2);
        helper.assertTrue(floating.isNoGravity() && flying.isNoGravity() && shot.isNoGravity() && !seeker.isNoGravity(), "the flying ones should not fall");

        helper.runAfterDelay(8, () -> {
            helper.assertTrue(shot.getX() > missile.c.getX() + 4, "Missile TNT facing east should fly east, it moved " + (shot.getX() - missile.c.getX()));
            List<PrimedTnt> hopping = hop.all(PrimedTnt.class, 12);
            helper.assertTrue(hopping.size() == 1 && hopping.get(0).entityTags().contains("tntmania_hops_5"), "Jumping TNT should have hopped once, with 5 hops left: "
                    + hopping.stream().map(Entity::entityTags).toList());
        });
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(floating.getY() > balloon.c.getY() + 1.5 && floating.getY() < balloon.c.getY() + 4, "Floating TNT should rise slowly, it rose " + (floating.getY() - balloon.c.getY()));
            helper.assertTrue(flying.getY() > rocket.c.getY() + 15, "Rocket TNT should shoot up, it rose " + (flying.getY() - rocket.c.getY()));
            helper.assertTrue(!shot.isAlive() && missile.air(8, 1, 0), "Missile TNT should go off when it hits the glass wall");
            helper.assertTrue(seeker.isAlive() ? seeker.getX() > homing.c.getX() + 2 : !spider.isAlive() || spider.getHealth() < spider.getMaxHealth(), "Homing TNT should chase the spider");
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(!floating.isAlive() && !flying.isAlive(), "the balloon and the rocket should have gone off in the air");
            helper.assertTrue(balloon.count(4, "grass_block") == 81, "the balloon went off high up: the ground should be whole");
            helper.assertTrue(!seeker.isAlive(), "Homing TNT should go off next to the spider long before its fuse ends");
            helper.assertTrue(!spider.isAlive() || spider.getHealth() < spider.getMaxHealth(), "the spider should be hurt");
        });
        helper.runAfterDelay(300, () -> {
            boolean any = false;
            for (PrimedTnt tnt : level.getEntitiesOfClass(PrimedTnt.class, new AABB(hop.c).inflate(60))) any |= tnt.getBlockState().is(TntMania.BLOCKS.get("jumping_tnt"));
            helper.assertTrue(!any, "Jumping TNT should stop after six hops");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 400)
    public void theOnesThatRepeatAndTheFallingOnes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Ground domino = Ground.make(level), spiral = Ground.make(level), wave = Ground.make(level), quake = Ground.make(level), gravity = Ground.make(level),
                eruption = Ground.make(level), chain = Ground.make(level, 8, 10, 6, "stone");
        gravity.fill(-3, -6, -3, 3, -4, 3, AIR);
        chain.set(2, 1, 0, Blocks.TNT.defaultBlockState());
        chain.set(-2, 1, 0, tnt("cow_tnt"));
        Detonation a = domino.boom("domino_tnt", Direction.EAST), b = spiral.boom("spiral_tnt"), c = wave.boom("shockwave_tnt"), q = quake.boom("earthquake_tnt"),
                gr = gravity.boom("gravity_tnt"), er = eruption.boom("eruption_tnt"), ch = chain.boom("cube_tnt");
        boolean[] lit = new boolean[3];
        for (int tick = 1; tick <= 12; tick++) {
            helper.runAfterDelay(tick, new Runnable() {
                @Override
                public void run() {
                    for (PrimedTnt tnt : chain.all(PrimedTnt.class, 8)) {
                        lit[0] |= tnt.getBlockState().is(Blocks.TNT);
                        lit[1] |= tnt.getBlockState().is(TntMania.BLOCKS.get("cow_tnt"));
                        tnt.discard();
                    }
                    lit[2] |= !level.getEntitiesOfClass(FallingBlockEntity.class, new AABB(eruption.c).inflate(20, 30, 20)).isEmpty();
                }
            });
        }
        when(helper, () -> {
            helper.assertTrue(lit[0] && lit[1], "TNT that a digging TNT cuts through should be lit, not lost (normal " + lit[0] + ", mod " + lit[1] + ")");
            helper.assertTrue(lit[2], "Eruption TNT: blocks should have been flying");
            helper.assertTrue(a.actions == 10 && domino.air(3, -1, 0) && domino.is(-3, -1, 0, "grass_block"), "Domino TNT: ten blasts to the east, there were " + a.actions);
            helper.assertTrue(b.actions == 30, "Spiral TNT: thirty blasts, there were " + b.actions);
            helper.assertTrue(c.actions == 7 + 15 + 23 + 31, "Shockwave TNT: four rings of blasts, there were " + c.actions);
            helper.assertTrue(q.actions == 40, "Earthquake TNT: forty cracks, there were " + q.actions);
            helper.assertTrue(gr.blocks > 300 && gravity.is(0, -6, 0, "stone") && gravity.air(0, -1, 0), "Gravity TNT: the ground should have fallen into the cave under it (" + gr.blocks + " blocks fell)");
            helper.assertTrue(er.blocks > 100 && eruption.air(0, -2, 0), "Eruption TNT: the ground should be thrown out, " + er.blocks);
            helper.assertTrue(ch.blocks > 300, "Cube TNT through TNT: it should dig");
        }, a, b, c, q, gr, er, ch);
    }

    // ---- fill mode ---------------------------------------------------------------------------------------------------------

    /** A player standing just west of the middle of the ground, looking east, holding this many of an item. */
    private static ServerPlayer builder(GameTestHelper helper, Ground g, GameType mode, Item item, int count) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(mode);
        player.snapTo(g.c.getX() - 1.5, g.c.getY(), g.c.getZ() + 0.5, -90f, 0f);
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item, count));
        return player;
    }

    /** Places what the player holds on the middle of the ground, the way a real click does it. */
    private static void click(Ground g, ServerPlayer player) {
        BlockHitResult floor = new BlockHitResult(Vec3.atCenterOf(g.at(0, -1, 0)).add(0, 0.5, 0), Direction.UP, g.at(0, -1, 0), false);
        player.getItemInHand(InteractionHand.MAIN_HAND).useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, floor));
    }

    private static int holding(ServerPlayer player, Item item) {
        int n = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item)) n += player.getInventory().getItem(slot).getCount();
        }
        return n;
    }

    @GameTest(maxTicks = 300)
    public void fillModePlacesAWholeAreaWithOneClick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Item x2 = TntMania.BLOCKS.get("x2_tnt").asItem();
        Ground creative = Ground.make(level), survival = Ground.make(level), off = Ground.make(level), never = Ground.make(level), normal = Ground.make(level);

        // what a player's game may send is brought into the allowed sizes, and survives the trip through the network
        ServerPlayer anyone = builder(helper, never, GameType.CREATIVE, x2, 64);
        helper.assertTrue(Fill.of(anyone).equals(FillSettings.START) && !FillSettings.START.on(), "a player who never opened the settings has fill mode off");
        Fill.set(anyone, new FillSettings(true, 999, -5, 99));
        helper.assertTrue(Fill.of(anyone).equals(new FillSettings(true, 50, 1, 10)), "sizes should be cut to 50 x 50 x 10, got " + Fill.of(anyone));
        RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        FillSettings.CODEC.encode(wire, new FillSettings(true, 7, 31, 4));
        helper.assertTrue(FillSettings.CODEC.decode(wire).equals(new FillSettings(true, 7, 31, 4)), "the settings should arrive as they were sent");
        Fill.forget(anyone);
        click(never, anyone);

        // creative: 5 wide, 4 long, 2 high = 40, but a stone block and a pig are in the way
        creative.set(2, 0, 1, s("stone"));
        Mob pig = creative.mob("pig", 3, -2);
        ServerPlayer maker = builder(helper, creative, GameType.CREATIVE, x2, 64);
        helper.assertTrue(maker.getDirection() == Direction.EAST, "the test player should look east, looks " + maker.getDirection());
        Fill.set(maker, new FillSettings(true, 5, 4, 2));
        click(creative, maker);
        Detonation big = Fill.last;

        // survival with 10 TNT: 10 are placed and the inventory is empty
        ServerPlayer poor = builder(helper, survival, GameType.SURVIVAL, x2, 7);
        poor.getInventory().add(new ItemStack(x2, 3));
        Fill.set(poor, new FillSettings(true, 5, 4, 2));
        click(survival, poor);
        Detonation few = Fill.last;

        // switched off: one block, as always
        ServerPlayer plain = builder(helper, off, GameType.CREATIVE, x2, 64);
        Fill.set(plain, new FillSettings(false, 5, 4, 2));
        click(off, plain);

        // normal TNT is filled too
        ServerPlayer classic = builder(helper, normal, GameType.CREATIVE, Items.TNT, 64);
        Fill.set(classic, new FillSettings(true, 3, 3, 1));
        click(normal, classic);
        Detonation old = Fill.last;
        helper.assertTrue(big != null && few != null && old != null && big != few && few != old, "each click with fill mode on should start a filling");

        String id = "tntmania:x2_tnt";
        BlockState east = tnt("x2_tnt").setValue(ModTntBlock.FACING, Direction.EAST);
        when(helper, () -> {
            // east is forward: forward is +x, side to side is z
            helper.assertTrue(creative.count(8, id) == 38, "creative: 40 minus the stone and the pig = 38 TNT, there are " + creative.count(8, id));
            helper.assertTrue(creative.get(0, 0, 0) == east && creative.get(3, 1, 2) == east && creative.get(3, 0, -1) == east && creative.get(0, 1, -2) == east,
                    "the area should be 4 forward, 2 to each side and 2 high, all facing the same way");
            helper.assertTrue(creative.is(2, 0, 1, "stone") && creative.air(3, 0, -2) && pig.isAlive(), "a block and a mob that are in the way are left alone");
            helper.assertTrue(creative.air(-1, 0, 0) && creative.air(4, 0, 0) && creative.air(0, 0, 3) && creative.air(0, 0, -3) && creative.air(0, 2, 0), "nothing outside the area");
            helper.assertTrue(holding(maker, x2) == 64, "creative uses up no TNT, the player holds " + holding(maker, x2));

            helper.assertTrue(survival.count(8, id) == 10 && holding(poor, x2) == 0, "survival with 10 TNT: 10 placed and none left, placed "
                    + survival.count(8, id) + ", left " + holding(poor, x2));
            helper.assertTrue(survival.get(0, 0, 0) == east && survival.get(1, 0, 2) == east && survival.air(2, 0, 0), "survival: the nearest rows are filled first");

            helper.assertTrue(off.count(8, id) == 1, "fill mode off: one TNT, there are " + off.count(8, id));
            helper.assertTrue(never.count(8, id) == 1, "no settings: one TNT, there are " + never.count(8, id));
            helper.assertTrue(normal.count(4, "tnt") == 9 && normal.is(2, 0, 1, "tnt") && normal.is(0, 0, -1, "tnt") && normal.air(3, 0, 0),
                    "normal TNT: 3 x 3 = 9, there are " + normal.count(4, "tnt"));
            helper.assertTrue(level.getEntitiesOfClass(PrimedTnt.class, new AABB(creative.c).inflate(12)).isEmpty(), "placing an area must not light anything");
        }, big, few, old);
    }

    // ---- everything at once ------------------------------------------------------------------------------------------------------------

    /** One of 25 pieces of ground that the big test uses again and again: wiped clean and set up fresh for the next TNT. */
    private static Ground fresh(ServerLevel level, int x, int z, TntType t) {
        boolean nether = t.what().startsWith("nether:");
        Ground g = Ground.at(level, x, z, 12, 10, 0, nether ? "netherrack" : "stone", 1);
        for (Entity e : level.getEntities((Entity) null, new AABB(g.c).inflate(23, 100, 23), e -> !(e instanceof ServerPlayer))) e.discard();
        g.fill(-14, 0, -14, 14, 16, 14, AIR);
        g.fill(-14, -12, -14, 14, -11, 14, AIR);
        g.fill(13, -10, -14, 14, -1, 14, AIR);
        g.fill(-14, -10, -14, -13, -1, 14, AIR);
        g.fill(-12, -10, 13, 12, -1, 14, AIR);
        g.fill(-12, -10, -14, 12, -1, -13, AIR);
        g.decorate();
        if (t.kind() == Kind.TORCH) g.fill(-12, 4, -12, 12, 4, 12, s("stone"));
        if (t.kind() == Kind.SHAFT && t.what().equals("up")) g.set(0, 5, 0, s("stone"));
        g.mob("pig", 2, -2);
        g.mob("spider", -2, -2);
        g.item(Items.STICK, 0, 3);
        return g;
    }

    /**
     * Sets off all 250, each on ground with something of everything on it, and checks that every single one
     * finishes without an error and really did something. They go 25 at a time; the three giants go one by one,
     * and for those the test also checks that the game keeps running smoothly and that no land gets loaded.
     * At the end: the weather and the time of day.
     */
    @GameTest(maxTicks = 12000)
    public void all250GoOffWithoutAnErrorAndTheBiggestStaysSmooth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        List<TntType> small = new ArrayList<>(), giants = new ArrayList<>();
        for (TntType t : Catalog.all()) (t.kind() == Kind.SPHERE && t.size() >= 35 ? giants : small).add(t);
        List<Detonation> all = new ArrayList<>(), batch = new ArrayList<>();
        int before = Jobs.errors;
        int[] next = {0}, far = new int[2];
        long[] step = {0};
        Ground[] sky = {null};
        Runnable look = () -> {
            if (step[0] == 0) {
                for (Detonation d : batch) {
                    // the two that change a mob's size put it back a minute later: no need to wait for that
                    if (!d.done() && d.type.kind() != Kind.SCALE) return;
                }
                batch.clear();
                if (next[0] < small.size()) {
                    for (int i = 0; i < 25 && next[0] < small.size(); i++) {
                        TntType t = small.get(next[0]++);
                        Ground g = fresh(level, 4000, 4000 + i * 48, t);
                        Detonation d = g.boom(t.id(), Direction.EAST);
                        // a Wither or a Warden must not roam among the other grounds
                        if (t.kind() == Kind.MOB) g.all(Mob.class, 16).forEach(Entity::discard);
                        batch.add(d);
                        all.add(d);
                    }
                    return;
                }
                step[0] = 1;
                next[0] = 0;
            }
            if (step[0] == 1) {
                if (!batch.isEmpty()) {
                    Detonation d = batch.get(0);
                    if (!d.done()) return;
                    // The mod gives itself 12 ms of work per tick. A little over is normal (another program using the PC,
                    // the game tidying its memory); a blast that is NOT cut into pieces would take seconds.
                    TntMania.LOG.info("TNT Mania test: {} took {} blocks, longest tick {} ms", d.type.id(), d.blocks, Jobs.longestTick / 1_000_000);
                    if (Jobs.longestTick > 150_000_000L) helper.fail(d.type.id() + ": the mod worked " + Jobs.longestTick / 1_000_000 + " ms in one tick: that would stutter");
                    if (d.type.size() >= 48 && level.hasChunk(far[0], far[1])) helper.fail(d.type.id() + " loaded land that was not loaded");
                    if (d.blocks < 3000) helper.fail(d.type.id() + " should have removed the whole ground, it took only " + d.blocks);
                    batch.clear();
                }
                if (next[0] < giants.size()) {
                    TntType t = giants.get(next[0]);
                    Ground g = fresh(level, -9008, 9008 + next[0]++ * 1600, t);
                    far[0] = (g.c.getX() >> 4) - 4;
                    far[1] = g.c.getZ() >> 4;
                    if (level.hasChunk(far[0], far[1])) helper.fail("the land 4 chunks away should not be loaded before the test");
                    Jobs.longestTick = 0;
                    Detonation d = g.boom(t.id(), Direction.EAST);
                    batch.add(d);
                    all.add(d);
                    return;
                }
                List<String> wrong = new ArrayList<>();
                for (Detonation d : all) {
                    if (d.error != null) wrong.add(d.type.id() + " went wrong: " + d.error);
                    // the random one may pick a TNT that has nothing to do on this ground
                    else if (d.total() == 0 && d.type.kind() != Kind.RANDOM) wrong.add(d.type.id() + " did nothing at all");
                }
                if (all.size() != 250) helper.fail("all 250 should have been set off, it was " + all.size());
                if (!wrong.isEmpty() || Jobs.errors != before) helper.fail(wrong.size() + " of 250 failed: " + wrong + " " + Jobs.lastError + problems());
                if (!Ids.PROBLEMS.isEmpty()) helper.fail("names the game does not know:" + problems());

                Ground g = Ground.at(level, -9008, 4000, 12, 10, 0, "stone", 1);
                sky[0] = g;
                g.boom("rain_tnt");
                if (!level.getWeatherData().isRaining()) helper.fail("Rain TNT should make it rain");
                g.boom("sun_tnt");
                if (level.getWeatherData().isRaining()) helper.fail("Sun TNT should clear the sky");
                g.boom("thunderstorm_tnt");
                if (!level.getWeatherData().isThundering()) helper.fail("Thunderstorm TNT should start a thunderstorm");
                g.boom("sun_tnt");
                g.boom("night_tnt");
                step[0] = 2;
            } else if (step[0] == 2) {
                if (!level.isDarkOutside()) helper.fail("Night TNT should make it night");
                sky[0].boom("day_tnt");
                step[0] = 3;
            } else if (step[0] == 3) {
                if (!level.isBrightOutside()) helper.fail("Day TNT should make it day");
                step[0] = 4;
                helper.succeed();
            }
        };
        // each call gets its own object: the game keeps these in a table by object, and one object would be kept only once
        for (int tick = 5; tick < 11990; tick += 5) {
            int at = tick;
            helper.runAfterDelay(tick, new Runnable() {
                @Override
                public void run() {
                    if (at > 11900 && step[0] < 4) {
                        List<String> busy = new ArrayList<>();
                        for (Detonation d : batch) {
                            if (!d.done()) busy.add(d.waiting());
                        }
                        helper.fail("stuck at step " + step[0] + " after " + all.size() + " of 250, waiting for " + busy);
                    }
                    look.run();
                }
            });
        }
    }

    @GameTest(maxTicks = 600)
    public void theRandomOneNeverBreaks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        normal(level);
        int before = Jobs.errors;
        List<Detonation> blasts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Ground g = Ground.make(level).decorate();
            g.mob("pig", 2, -2);
            for (int n = 0; n < 5; n++) blasts.add(g.boom("random_tnt"));
            // it may have picked a mob TNT: a Wither or a Warden must not roam among the other tests
            for (Mob mob : g.all(Mob.class, 16)) mob.discard();
        }
        helper.runAfterDelay(500, () -> {
            for (Detonation d : blasts) helper.assertTrue(d.error == null, "Random TNT went wrong: " + d.error);
            helper.assertTrue(Jobs.errors == before, "a blast set off by Random TNT went wrong: " + Jobs.lastError);
            helper.succeed();
        });
    }
}
