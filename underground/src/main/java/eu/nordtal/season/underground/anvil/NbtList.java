package eu.nordtal.season.underground.anvil;

import java.util.ArrayList;

/** An NBT list, which keeps the element type that an empty list is written with. */
public final class NbtList extends ArrayList<Object> {

    private static final long serialVersionUID = 1L;

    private final byte elementType;

    public NbtList(final byte elementType) {
        this.elementType = elementType;
    }

    /** Returns the element type that this list was read or created with. */
    public byte elementType() {
        return elementType;
    }
}
