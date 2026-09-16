package com.caseware.updates.fanout;

import java.util.List;
import java.util.Objects;

/**
 * A page of projection rows plus an opaque cursor ({@code null} means end of scan).
 *
 * <p>Paging is not a performance detail here: the largest firm has ~40,000 engagements and a publish can
 * touch far more, so the worker must hold bounded memory and must be able to resume from a cursor after a
 * crash or a deploy.
 */
public record Page<T>(List<T> rows, String nextCursor) {

    public Page {
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}
