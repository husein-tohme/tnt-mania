package net.husein.tntmania;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.husein.tntmania.catalog.Catalog;
import net.husein.tntmania.catalog.TntType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.core.dispenser.DefaultDispenseItemBehavior;
import net.minecraft.core.dispenser.DispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TNT Mania: 250 new kinds of TNT. Every kind is one line in the table tnt-types.txt; this class turns each
 * line into a block and an item, and puts them all into one tab of the creative inventory.
 */
public final class TntMania implements ModInitializer {
    public static final String ID = Catalog.MOD;
    public static final Logger LOG = LoggerFactory.getLogger("TNT Mania");

    /** Every TNT block of the mod by its id, in the order of the table. */
    public static final Map<String, ModTntBlock> BLOCKS = new LinkedHashMap<>();
    private static final List<ResourceKey<Recipe<?>>> RECIPES = new ArrayList<>();

    /** A dispenser lights the TNT in front of itself, as it does with normal TNT. */
    private static final DispenseItemBehavior DISPENSE = new DefaultDispenseItemBehavior() {
        @Override
        protected ItemStack execute(BlockSource source, ItemStack stack) {
            if (!(stack.getItem() instanceof BlockItem item) || !(item.getBlock() instanceof ModTntBlock block)) return super.execute(source, stack);
            Direction way = source.state().getValue(DispenserBlock.FACING);
            BlockPos pos = source.pos().relative(way);
            BlockState state = block.defaultBlockState().setValue(ModTntBlock.FACING, way.getAxis().isHorizontal() ? way : Direction.NORTH);
            if (ModTntBlock.prime(source.level(), pos, state, null, false) != null) stack.shrink(1);
            return stack;
        }
    };

    @Override
    public void onInitialize() {
        for (TntType type : Catalog.all()) {
            Identifier id = Identifier.fromNamespaceAndPath(ID, type.id());
            ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, id);
            ModTntBlock block = Registry.register(BuiltInRegistries.BLOCK, blockKey, new ModTntBlock(type, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.FIRE).instabreak().sound(SoundType.GRASS).ignitedByLava()
                    .isRedstoneConductor((state, level, pos) -> false).setId(blockKey)));
            BLOCKS.put(type.id(), block);

            // the grey lines under the name that say what this TNT does
            List<Component> lines = new ArrayList<>();
            for (int line = 0; line < Catalog.aboutLines(type).size(); line++) {
                lines.add(Component.translatable(ID + ".about." + type.id() + "." + line).withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY).withItalic(false)));
            }
            ItemLore about = new ItemLore(lines);
            ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, id);
            Item item = Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties()
                    .setId(itemKey).useBlockDescriptionPrefix().component(DataComponents.LORE, about)));

            DispenserBlock.registerBehavior(item, DISPENSE);
            FlammableBlockRegistry.getDefaultInstance().add(block, 15, 100);
            RECIPES.add(ResourceKey.create(Registries.RECIPE, id));
        }

        ResourceKey<CreativeModeTab> tab = ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath(ID, "all"));
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, tab, FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup." + ID))
                .icon(() -> new ItemStack(BLOCKS.get("x100_tnt")))
                .displayItems((parameters, output) -> BLOCKS.values().forEach(output::accept))
                .build());

        ServerTickEvents.END_SERVER_TICK.register(Jobs::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> Jobs.clear());
        // the recipes show up in the recipe book from the start, so nobody has to guess them
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                handler.player.awardRecipesByKey(RECIPES);
            } catch (Throwable t) {
                LOG.warn("TNT Mania: could not show the recipes: {}", t.toString());
            }
        });

        // fill mode: each player's game tells the world its settings; the world forgets them when the player leaves
        PayloadTypeRegistry.serverboundPlay().register(FillSettings.TYPE, FillSettings.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(FillSettings.TYPE, (settings, context) ->
                context.server().execute(() -> Fill.set(context.player(), settings)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> Fill.forget(handler.player));

        Effects.check();
        LOG.info("TNT Mania: {} kinds of TNT ready{}", BLOCKS.size(), Ids.PROBLEMS.isEmpty() ? "" : ", with " + Ids.PROBLEMS.size() + " problems in the table");
    }
}
