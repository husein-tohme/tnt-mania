package net.husein.tntmania;

import net.husein.tntmania.catalog.Catalog;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player's fill mode: on or off, and how big the area is that one placed TNT turns into.
 * The player's game sends this to the world whenever it changes; the world does the placing.
 */
public record FillSettings(boolean on, int wide, int length, int high) implements CustomPacketPayload {
    public static final int MAX_WIDE = 50, MAX_LENGTH = 50, MAX_HIGH = 10;
    public static final FillSettings START = new FillSettings(false, 5, 5, 1);

    public static final Type<FillSettings> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Catalog.MOD, "fill"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FillSettings> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, FillSettings::on, ByteBufCodecs.VAR_INT, FillSettings::wide,
            ByteBufCodecs.VAR_INT, FillSettings::length, ByteBufCodecs.VAR_INT, FillSettings::high, FillSettings::new);

    /** The same settings with every size brought into what is allowed. Whatever a player's game sends, the world uses this. */
    public FillSettings clamped() {
        return new FillSettings(on, Math.clamp(wide, 1, MAX_WIDE), Math.clamp(length, 1, MAX_LENGTH), Math.clamp(high, 1, MAX_HIGH));
    }

    /** How many TNT one click places. */
    public int blocks() {
        return wide * length * high;
    }

    public FillSettings withOn(boolean value) {
        return new FillSettings(value, wide, length, high);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
