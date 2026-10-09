package com.oliver.erydon.compat.state;

import com.google.common.collect.ForwardingTable;
import com.google.common.collect.Table;
import net.minecraft.state.property.Property;

/** Distinct table type to prove ERYDON preserves another optimizer's lookup. */
public final class ForeignArchStateTable<S> extends ForwardingTable<Property<?>, Comparable<?>, S> {
    private final Table<Property<?>, Comparable<?>, S> table;

    public ForeignArchStateTable(Table<Property<?>, Comparable<?>, S> table) { this.table = table; }
    @Override protected Table<Property<?>, Comparable<?>, S> delegate() { return table; }
}
