package com.manaawakened.puzzle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The wisdom trial: {@code size} elemental glyphs must be set on a ring of {@code size} slots so that every clue holds.
 * A puzzle is a pure function of its size and seed, so the server and the client each build the same one from the seed
 * and only the seed travels. The generator keeps adding true statements about a hidden arrangement until exactly one
 * arrangement satisfies them all, then strips every statement that is not needed: there is always one answer and no
 * guessing is rewarded (every clue is needed).
 *
 * <p>Slots are numbered clockwise from the top. Glyph numbers are {@code 0..size-1}.
 */
public final class RingPuzzle {
    public static final int MIN_SIZE = 4;
    public static final int MAX_SIZE = 9;

    public enum ClueType {
        /** Glyphs a and b sit next to each other. */
        ADJACENT,
        /** Glyphs a and b do not sit next to each other. */
        APART,
        /** Glyphs a and b face each other across the ring (even rings only). */
        OPPOSITE,
        /** Glyph a sits in slot b. */
        AT,
        /** Glyph a sits between glyphs b and c. */
        BETWEEN,
        /** Glyph b is the next glyph clockwise after glyph a. */
        NEXT
    }

    public record Clue(ClueType type, int a, int b, int c) {
        public boolean holds(int[] slotOf, int size) {
            int pa = slotOf[a];
            return switch (type) {
                case ADJACENT -> distance(pa, slotOf[b], size) == 1;
                case APART -> distance(pa, slotOf[b], size) > 1;
                case OPPOSITE -> distance(pa, slotOf[b], size) * 2 == size;
                case AT -> pa == b;
                case BETWEEN -> {
                    int pb = slotOf[b];
                    int pc = slotOf[c];
                    yield (pb == (pa + 1) % size && pc == (pa + size - 1) % size) || (pc == (pa + 1) % size && pb == (pa + size - 1) % size);
                }
                case NEXT -> slotOf[b] == (pa + 1) % size;
            };
        }

        /** Whether every glyph this clue mentions has already been placed (slot >= 0). */
        boolean decided(int[] slotOf) {
            return slotOf[a] >= 0 && (type == ClueType.AT || slotOf[b] >= 0) && (type != ClueType.BETWEEN || slotOf[c] >= 0);
        }
    }

    private final int size;
    private final long seed;
    private final List<Clue> clues;
    private final int[] solution;

    private RingPuzzle(int size, long seed, List<Clue> clues, int[] solution) {
        this.size = size;
        this.seed = seed;
        this.clues = List.copyOf(clues);
        this.solution = solution;
    }

    public int size() {
        return size;
    }

    public long seed() {
        return seed;
    }

    public List<Clue> clues() {
        return clues;
    }

    static int distance(int a, int b, int size) {
        int d = Math.abs(a - b);
        return Math.min(d, size - d);
    }

    public static RingPuzzle generate(int size, long seed) {
        int n = Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
        Random random = new Random(seed);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        Collections.shuffle(order, random);
        int[] solution = new int[n]; // solution[slot] = glyph
        int[] slotOf = new int[n];
        for (int slot = 0; slot < n; slot++) {
            solution[slot] = order.get(slot);
            slotOf[solution[slot]] = slot;
        }

        List<Clue> candidates = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            candidates.add(new Clue(ClueType.AT, a, slotOf[a], 0));
            int next = solution[(slotOf[a] + 1) % n];
            candidates.add(new Clue(ClueType.NEXT, a, next, 0));
            int before = solution[(slotOf[a] + n - 1) % n];
            candidates.add(new Clue(ClueType.BETWEEN, a, Math.min(next, before), Math.max(next, before)));
            for (int b = a + 1; b < n; b++) {
                int d = distance(slotOf[a], slotOf[b], n);
                if (d == 1) {
                    candidates.add(new Clue(ClueType.ADJACENT, a, b, 0));
                } else {
                    candidates.add(new Clue(ClueType.APART, a, b, 0));
                    if (d * 2 == n) {
                        candidates.add(new Clue(ClueType.OPPOSITE, a, b, 0));
                    }
                }
            }
        }
        Collections.shuffle(candidates, random);
        // A pointing-out clue (AT) says too much for a puzzle meant to need thought: keep them last in line.
        candidates.sort((x, y) -> Integer.compare(x.type() == ClueType.AT ? 1 : 0, y.type() == ClueType.AT ? 1 : 0));

        List<Clue> chosen = new ArrayList<>();
        for (Clue clue : candidates) {
            chosen.add(clue);
            if (countSolutions(n, chosen, 2) == 1) {
                break;
            }
        }
        // Strip every clue that is not needed.
        List<Clue> shuffled = new ArrayList<>(chosen);
        Collections.shuffle(shuffled, random);
        for (Clue clue : shuffled) {
            List<Clue> without = new ArrayList<>(chosen);
            without.remove(clue);
            if (countSolutions(n, without, 2) == 1) {
                chosen = without;
            }
        }
        return new RingPuzzle(n, seed, chosen, solution);
    }

    /** Counts arrangements satisfying every clue, stopping once {@code limit} have been found. */
    public static int countSolutions(int size, List<Clue> clues, int limit) {
        int[] slotOf = new int[size];
        java.util.Arrays.fill(slotOf, -1);
        boolean[] used = new boolean[size];
        int[] found = {0};
        search(size, clues, slotOf, used, 0, limit, found);
        return found[0];
    }

    private static void search(int size, List<Clue> clues, int[] slotOf, boolean[] used, int slot, int limit, int[] found) {
        if (found[0] >= limit) {
            return;
        }
        if (slot == size) {
            found[0]++;
            return;
        }
        for (int glyph = 0; glyph < size; glyph++) {
            if (used[glyph]) {
                continue;
            }
            used[glyph] = true;
            slotOf[glyph] = slot;
            if (consistent(size, clues, slotOf)) {
                search(size, clues, slotOf, used, slot + 1, limit, found);
            }
            slotOf[glyph] = -1;
            used[glyph] = false;
        }
    }

    private static boolean consistent(int size, List<Clue> clues, int[] slotOf) {
        for (Clue clue : clues) {
            if (clue.decided(slotOf) && !clue.holds(slotOf, size)) {
                return false;
            }
        }
        return true;
    }

    /** @param arrangement glyph in every slot; anything that is not a full permutation fails */
    public boolean isSolution(int[] arrangement) {
        if (arrangement == null || arrangement.length != size) {
            return false;
        }
        int[] slotOf = new int[size];
        java.util.Arrays.fill(slotOf, -1);
        for (int slot = 0; slot < size; slot++) {
            int glyph = arrangement[slot];
            if (glyph < 0 || glyph >= size || slotOf[glyph] >= 0) {
                return false;
            }
            slotOf[glyph] = slot;
        }
        for (Clue clue : clues) {
            if (!clue.holds(slotOf, size)) {
                return false;
            }
        }
        return true;
    }

    /** How many clues of this puzzle the given (possibly partial, -1 = empty slot) arrangement already breaks. */
    public int violations(int[] arrangement) {
        int[] slotOf = new int[size];
        java.util.Arrays.fill(slotOf, -1);
        for (int slot = 0; slot < size && slot < arrangement.length; slot++) {
            int glyph = arrangement[slot];
            if (glyph >= 0 && glyph < size) {
                slotOf[glyph] = slot;
            }
        }
        int broken = 0;
        for (Clue clue : clues) {
            if (clue.decided(slotOf) && !clue.holds(slotOf, size)) {
                broken++;
            }
        }
        return broken;
    }

    /** Whether clue {@code index} is already broken by a (possibly partial, -1 = empty slot) arrangement. */
    public boolean clueBroken(int index, int[] arrangement) {
        int[] slotOf = new int[size];
        java.util.Arrays.fill(slotOf, -1);
        for (int slot = 0; slot < size && slot < arrangement.length; slot++) {
            int glyph = arrangement[slot];
            if (glyph >= 0 && glyph < size) {
                slotOf[glyph] = slot;
            }
        }
        Clue clue = clues.get(index);
        return clue.decided(slotOf) && !clue.holds(slotOf, size);
    }

    /** The hidden answer. Server-side tests and hints only: never sent to a client. */
    public int[] solution() {
        return solution.clone();
    }
}
