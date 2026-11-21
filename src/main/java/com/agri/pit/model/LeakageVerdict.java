package com.agri.pit.model;

public enum LeakageVerdict {
    /**
     * Every input used by the run was available at or before the scoring cutoff and no
     * post-cutoff data influenced the value.
     */
    PASS,
    /**
     * The run is as-of correct, but at least one late-arriving input had to be excluded.
     * The exclusion is recorded on the run ledger so reviewers can see what was not known.
     */
    PASS_WITH_EXCLUSIONS,
    /** The run could not be scored at the cutoff because required information was not yet available. */
    BLOCKED,
    /** Controlled backfill that deliberately uses post-cutoff data. Never valid for original scoring. */
    CANDIDATE_ONLY,
    NOT_EVALUATED
}
