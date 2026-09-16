package com.caseware.updates.fanout;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A page of projection rows plus an opaque cursor.
 *
 * <p>Paging is not a performance detail here: the largest firm has ~40,000 engagements and a publish can
 * touch far more, so the worker must hold bounded memory and must be able to resume from a cursor after a
 * crash or a deploy.
 */
public final class Page<T> {
    private final List<T> rows;
    private final String nextCursor; // null == end of scan

    public Page(List<T> rows, String nextCursor) {
        this.rows = Collections.unmodifiableList(Objects.requireNonNull(rows, "rows"));
        this.nextCursor = nextCursor;
    }

    public List<T> rows() { return rows; }
    public String nextCursor() { return nextCursor; }
    public boolean hasMore() { return nextCursor != null; }
}
