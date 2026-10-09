package net.husein.tntmania;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Looks game things up by their name ("oak_planks", "zombie", "speed"). The table and the buildings are written
 * with names, so a name the game does not know lands in PROBLEMS instead of crashing, and the tests check that list.
 */
public final class Ids {
    public static final Set<String> PROBLEMS = Collections.synchronizedSet(new LinkedHashSet<>());

    private Ids() {
    }

    static void problem(String what) {
        if (PROBLEMS.add(what)) TntMania.LOG.warn("TNT Mania: {}", what);
    }

    static Identifier id(String name) {
        return name.indexOf(':') >= 0 ? Identifier.parse(name) : Identifier.withDefaultNamespace(name);
    }

    public static Block block(String name) {
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id(name));
        if (block.isEmpty()) problem("there is no block called " + name);
        return block.orElse(Blocks.AIR);
    }

    public static BlockState state(String name) {
        return block(name).defaultBlockState();
    }

    public static EntityType<?> entity(String name) {
        Optional<EntityType<?>> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id(name));
        if (type.isEmpty()) problem("there is no mob called " + name);
        return type.orElse(null);
    }

    public static Holder<MobEffect> effect(String name) {
        Optional<Holder.Reference<MobEffect>> effect = BuiltInRegistries.MOB_EFFECT.get(id(name));
        if (effect.isEmpty()) problem("there is no potion effect called " + name);
        return effect.orElse(null);
    }

    public static Item item(String name) {
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id(name));
        if (item.isEmpty()) problem("there is no item called " + name);
        return item.orElse(null);
    }

    /** The same block with one of its settings changed, as in "facing" = "north". */
    public static BlockState with(BlockState state, String property, String value) {
        Property<?> p = state.getBlock().getStateDefinition().getProperty(property);
        if (p == null) {
            problem(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " has no setting called " + property);
            return state;
        }
        return set(state, p, value);
    }

    private static <T extends Comparable<T>> BlockState set(BlockState state, Property<T> property, String value) {
        Optional<T> v = property.getValue(value);
        if (v.isEmpty()) {
            problem(BuiltInRegistries.BLOCK.getKey(state.getBlock()) + ": " + property.getName() + " cannot be " + value);
            return state;
        }
        return state.setValue(property, v.get());
    }
}
