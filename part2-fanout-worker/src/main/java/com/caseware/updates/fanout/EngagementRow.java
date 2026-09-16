package com.caseware.updates.fanout;

import java.util.Objects;

/**
 * One row of the regional projection: the cheap, queryable stand-in for an expensive-to-load engagement.
 *
 * <p>{@code appliedVersion} is null exactly when {@code versionSource} is {@link VersionSource#UNKNOWN}.
 */
public record EngagementRow(String engagementId, String firmId, String templateId, String appliedVersion,
                            String marketId, VersionSource versionSource, boolean archived) {

    public EngagementRow {
        Objects.requireNonNull(engagementId, "engagementId");
        Objects.requireNonNull(firmId, "firmId");
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(marketId, "marketId");
        Objects.requireNonNull(versionSource, "versionSource");
    }
}
