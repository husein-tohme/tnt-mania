package net.husein.tntmania.catalog;

import java.util.List;

/**
 * One line of the table: one kind of TNT.
 *
 * @param size   the main number of its kind (mostly a radius, in blocks)
 * @param count  the second number of its kind (how many, how long, how deep)
 * @param what   the word of its kind (a block, a mob, a direction...), "" when it has none
 * @param fuse   ticks from lighting it to the bang (20 ticks = 1 second)
 * @param colour the colour of the block, as 0xRRGGBB
 * @param label  the short word printed on the block
 * @param recipe the items it is crafted from, with their namespaces
 */
public record TntType(int index, String group, String id, String name, Kind kind, double size, int count, String what,
                      int fuse, int colour, String label, List<String> recipe, String about) {
    public static final int NORMAL_FUSE = 80;

    /** The size as a whole number of blocks. */
    public int n() {
        return (int) Math.round(size);
    }
}
