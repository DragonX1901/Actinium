package net.actinium.perf;

public final class FrameBudget {
    private static final long LONGEST = 50_000_000L;

    private static long frameStart;
    private static long lastFrame = 16_000_000L;

    private FrameBudget() {
    }

    public static void beginFrame() {
        long now = System.nanoTime();
        if (frameStart != 0L) {
            lastFrame = Math.min(now - frameStart, LONGEST);
        }
        frameStart = now;
    }

    public static long deadline(long floorNanos, int percentOfFrame) {
        return System.nanoTime() + Math.max(floorNanos, lastFrame * percentOfFrame / 100L);
    }
}
