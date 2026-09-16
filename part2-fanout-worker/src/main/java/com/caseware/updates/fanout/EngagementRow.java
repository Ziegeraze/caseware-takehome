package com.caseware.updates.fanout;

import java.util.Objects;

/** One row of the regional projection: the cheap, queryable stand-in for an expensive-to-load engagement. */
public final class EngagementRow {
    private final String engagementId;
    private final String firmId;
    private final String templateId;
    private final String appliedVersion; // null when versionSource == UNKNOWN
    private final String marketId;
    private final VersionSource versionSource;
    private final boolean archived;

    public EngagementRow(String engagementId, String firmId, String templateId, String appliedVersion,
                         String marketId, VersionSource versionSource, boolean archived) {
        this.engagementId = Objects.requireNonNull(engagementId, "engagementId");
        this.firmId = Objects.requireNonNull(firmId, "firmId");
        this.templateId = Objects.requireNonNull(templateId, "templateId");
        this.appliedVersion = appliedVersion;
        this.marketId = Objects.requireNonNull(marketId, "marketId");
        this.versionSource = Objects.requireNonNull(versionSource, "versionSource");
        this.archived = archived;
    }

    public String engagementId() { return engagementId; }
    public String firmId() { return firmId; }
    public String templateId() { return templateId; }
    public String appliedVersion() { return appliedVersion; }
    public String marketId() { return marketId; }
    public VersionSource versionSource() { return versionSource; }
    public boolean archived() { return archived; }

    public EngagementRow withConfirmedVersion(String version) {
        return new EngagementRow(engagementId, firmId, templateId, version, marketId, VersionSource.CONFIRMED, archived);
    }
}
