package net.husein.tntmania;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Big blasts touch hundreds of thousands of blocks. Doing that in one go would freeze the game, so the work is
 * cut into pieces: every tick the mod works for a few thousandths of a second and carries on in the next tick.
 */
public final class Jobs {
    /** One piece of work. step() works until the given time and says whether it is finished. */
    public interface Work {
        boolean step(long deadline);
    }

    /** Which blocks of an up-and-down column are wanted: fills range[0] (lowest) and range[1] (highest), relative to the TNT. */
    public interface Shape {
        boolean column(int dx, int dz, int[] range);
    }

    public interface Visitor {
        void visit(BlockPos.MutableBlockPos pos, int dx, int dy, int dz);
    }

    /** How long the mod may work per tick, in nanoseconds (a tick is 50 million). */
    private static final long BUDGET = 12_000_000L;
    private static final long SMALLEST_SLICE = 200_000L;

    private static final List<Detonation> active = new ArrayList<>();
    private static final List<Timer> timers = new ArrayList<>();
    private static long now;
    /** How many blasts went wrong with an error. The tests want this to stay 0. */
    public static int errors;
    public static Throwable lastError;
    /** The longest the mod has worked in a single tick, in nanoseconds. The tests check that big blasts stay short. */
    public static long longestTick;

    private record Timer(Detonation d, long at, Runnable run) {
    }

    private Jobs() {
    }

    static void wake(Detonation d) {
        if (!active.contains(d)) active.add(d);
    }

    static void timer(Detonation d, int ticks, Runnable run) {
        d.timers++;
        timers.add(new Timer(d, now + Math.max(1, ticks), run));
    }

    public static boolean idle() {
        return active.isEmpty() && timers.isEmpty();
    }

    public static void clear() {
        active.clear();
        timers.clear();
    }

    static void fail(Detonation d, Throwable t) {
        errors++;
        lastError = t;
        d.error = t;
        TntMania.LOG.error("TNT Mania: {} went wrong", d.type.id(), t);
    }

    public static void tick(MinecraftServer server) {
        now++;
        if (!timers.isEmpty()) {
            List<Timer> due = new ArrayList<>();
            for (Iterator<Timer> it = timers.iterator(); it.hasNext(); ) {
                Timer t = it.next();
                if (t.at <= now) {
                    due.add(t);
                    it.remove();
                }
            }
            for (Timer t : due) {
                t.d.timers--;
                try {
                    t.run.run();
                } catch (Throwable e) {
                    fail(t.d, e);
                }
            }
        }
        if (active.isEmpty()) return;
        long began = System.nanoTime();
        long end = began + BUDGET;
        List<Detonation> round = new ArrayList<>(active);
        int left = round.size();
        for (Detonation d : round) {
            long start = System.nanoTime();
            long deadline = start + Math.max(SMALLEST_SLICE, (end - start) / left--);
            try {
                while (!d.queue.isEmpty()) {
                    if (!d.queue.peek().step(deadline)) break;
                    d.queue.poll();
                    if (System.nanoTime() >= deadline) break;
                }
            } catch (Throwable e) {
                d.queue.clear();
                fail(d, e);
            }
            if (d.queue.isEmpty()) active.remove(d);
        }
        // so the same blast is not always served first
        if (active.size() > 1) active.add(active.remove(0));
        longestTick = Math.max(longestTick, System.nanoTime() - began);
    }

    /** Walks through a box of blocks, column by column and from the top down, a bit every tick. */
    static final class Scan implements Work {
        private final ServerLevel level;
        private final int ox, oy, oz;
        private final int x0, x1, z0, z1, lowest, highest;
        private final Shape shape;
        private final Visitor visitor;
        private final Runnable finish;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final int[] range = new int[2];
        private int x, z, y, bottom;
        private boolean started;

        /** The box is given relative to the TNT at (ox, oy, oz). */
        Scan(ServerLevel level, BlockPos origin, int dx0, int dy0, int dz0, int dx1, int dy1, int dz1, Shape shape, Visitor visitor, Runnable finish) {
            this.level = level;
            this.ox = origin.getX();
            this.oy = origin.getY();
            this.oz = origin.getZ();
            this.x0 = ox + Math.min(dx0, dx1);
            this.x1 = ox + Math.max(dx0, dx1);
            this.z0 = oz + Math.min(dz0, dz1);
            this.z1 = oz + Math.max(dz0, dz1);
            this.lowest = Math.max(level.getMinY(), oy + Math.min(dy0, dy1));
            this.highest = Math.min(level.getMaxY(), oy + Math.max(dy0, dy1));
            this.shape = shape;
            this.visitor = visitor;
            this.finish = finish;
        }

        @Override
        public boolean step(long deadline) {
            if (!started) {
                started = true;
                x = x0;
                z = z0 - 1;
                y = 0;
                bottom = 1;
            }
            int n = 0;
            while (true) {
                if (y < bottom) {
                    if (!nextColumn()) {
                        if (finish != null) finish.run();
                        return true;
                    }
                    if ((++n & 31) == 0 && System.nanoTime() >= deadline) return false;
                    continue;
                }
                pos.set(x, y, z);
                visitor.visit(pos, x - ox, y - oy, z - oz);
                y--;
                if ((++n & 31) == 0 && System.nanoTime() >= deadline) return false;
            }
        }

        /** Moves to the next column. Columns in chunks that are not loaded are skipped: the mod never loads land. */
        private boolean nextColumn() {
            z++;
            if (z > z1) {
                z = z0;
                x++;
            }
            if (x > x1) return false;
            y = 0;
            bottom = 1;
            if (!level.hasChunk(x >> 4, z >> 4)) return true;
            int lo = lowest, hi = highest;
            if (shape != null) {
                if (!shape.column(x - ox, z - oz, range)) return true;
                lo = Math.max(lo, oy + range[0]);
                hi = Math.min(hi, oy + range[1]);
            }
            if (lo > hi) return true;
            bottom = lo;
            y = hi;
            return true;
        }
    }
}
