package com.agri.pit.catalog;

/**
 * Published after the feature catalog changes (a new version is created). Release gates that are
 * still awaiting approval listen for it and invalidate themselves, because the fingerprint their
 * approval references no longer describes the current catalog.
 */
public record CatalogChangedEvent(long featureDefinitionId, String featureCode, int newVersionNo) {
}
