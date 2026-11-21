package com.agri.pit.model;

public enum RunMode {
    /** Original scoring run. Bound to the case cutoff and allowed to produce the frozen snapshot. */
    ORIGINAL,
    /** Re-run at the original cutoff, optionally on a variant boundary version. Trace only. */
    REPLAY,
    /** Controlled backfill after a revision. Uses newly available data but only creates review candidates. */
    CONTROLLED_BACKFILL,
    /**
     * Shadow scoring of candidate definition versions against a frozen case. Runs at the original
     * cutoff, never writes a snapshot and never changes the claim's production status.
     */
    SHADOW
}
