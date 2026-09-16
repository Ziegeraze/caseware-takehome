package com.caseware.updates.fanout;

/**
 * How we learned an engagement's applied template version.
 *
 * <p>Only {@link #CONFIRMED} may drive a user-visible "up to date" / "update pending" answer. The other
 * two render as "Verifying", because claiming "up to date" from a guess is a silent lie — the single
 * most damaging failure mode of this feature.
 */
public enum VersionSource {
    /** Read from the engagement itself (creation, apply, or a load). Trustworthy. */
    CONFIRMED,
    /** Derived from creation timestamp + publish history. Wrong exactly where the domain is interesting
     *  (roll-forward, market branches, withdrawn versions), so it only prioritises verification. */
    INFERRED,
    /** No information at all (e.g. pre-existing engagement not yet backfilled). */
    UNKNOWN
}
