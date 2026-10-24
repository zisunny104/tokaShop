package dev.toka.shop;

/** Splits a number of pieces into whole stacks and the rest, using the real largest stack of the item. */
public final class Stacks {
    private Stacks() {}

    /** {stacks, rest}; a stack size of 1 or less (tools) never makes a stack */
    public static int[] split(int pieces, int stack) {
        if (stack <= 1) return new int[]{0, pieces};
        return new int[]{pieces / stack, pieces % stack};
    }
}
