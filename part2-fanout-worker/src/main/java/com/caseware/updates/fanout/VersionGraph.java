package com.caseware.updates.fanout;

/**
 * Authoritative view of template version lineage, read from the global template DB.
 *
 * <p>Template versions form a DAG: markets branch and versions are withdrawn after publication. "Is
 * there an update pending" is therefore an <em>ancestry</em> question, never a numeric comparison — an
 * engagement on v6 of a CA branch satisfies {@code 6 < 7} but v7 (EU) is not a descendant of v6, so v7
 * must not be offered to it.
 */
public interface VersionGraph {

    /** True iff {@code descendant} is reachable from {@code ancestor} by following parent edges. */
    boolean isAncestor(String templateId, String ancestor, String descendant);

    /**
     * True iff the version has been withdrawn after publication. Withdrawal is a flag, never a delete:
     * deleting the node would orphan descendants and make an already-shown summary unreproducible.
     */
    boolean isWithdrawn(String templateId, String version);
}
