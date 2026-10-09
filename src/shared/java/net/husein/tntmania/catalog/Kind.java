package net.husein.tntmania.catalog;

/** What a TNT does when it goes off. Each kind is one piece of code; the table gives it its numbers. */
public enum Kind {
    // blasts
    BLAST, SPHERE, NOTHING, SOUND,
    // digging
    SELECT, SHAFT, TUNNEL, CHUNK, CUBE, BOWL, FLATTEN, DISK, RING, STAIRS_DOWN, CHECKER, SHELL, TRENCH, BURROW, DOME_CUT,
    // changing blocks
    CONVERT, SURFACE, ORE, FILL, FLOOD, FIRE, FREEZE, TORCH,
    // making things
    BUILD, TREES, BONEMEAL,
    // mobs and players
    MOB, POTION, PUSH, TELEPORT, BUTCHER, LOVE, MILK, BURN, SCALE, MAGNET, CLEAN, XP, LOOT,
    // from the sky
    LIGHTNING, RAIN_OF, CLUSTER, FIREWORK, BURST, WEATHER, TIME,
    // moving and repeating
    FLY, HOP, CHAIN, SPIRAL, WAVES, QUAKE, GRAVITY, RANDOM
}
