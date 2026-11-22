package com.paulorchard.islandcraft.dunesofarrakis;

/** Client-side view state of the creative hide-sand toggle. Plain flag, no client classes, so the block can read it on any side. */
public final class SandView {
    private static volatile boolean hidden;

    public static boolean isHidden() { return hidden; }

    public static void setHidden(boolean value) { hidden = value; }

    private SandView() {}
}
