package eu.nordtal.season.underground.transplant;

import java.util.Arrays;

/** A growing list of cell indices, without a box around each. */
final class Cells {

    private int[] items = new int[1024];
    private int size;

    void push(final int cell) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
        }
        items[size++] = cell;
    }

    int pop() {
        return items[--size];
    }

    int get(final int at) {
        return items[at];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }
}
