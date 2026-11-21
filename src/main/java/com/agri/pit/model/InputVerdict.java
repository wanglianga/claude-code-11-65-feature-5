package com.agri.pit.model;

public enum InputVerdict {
    /** Available at the cutoff and used in the calculation. */
    ELIGIBLE,
    /** Available at the cutoff; a newer revision existed but was ingested after the cutoff and was ignored. */
    ELIGIBLE_OLDER_REVISION_USED,
    /** Observation exists but its availability time is after the cutoff. Blocked from the calculation. */
    EXCLUDED_LATE_AVAILABILITY,
    /** No revision of this observation had been ingested by the cutoff. */
    EXCLUDED_NOT_YET_INGESTED,
    /** All samples of the scene fall outside the case parcel after overlap allocation. */
    EXCLUDED_NO_SAMPLES_IN_PARCEL,
    /** Samples were partly or wholly allocated to another parcel to avoid pixel double counting. */
    EXCLUDED_PIXEL_ALLOCATED_OTHER_PARCEL,
    /** Considered for audit only, not applicable to the feature formula. */
    NOT_APPLICABLE
}
