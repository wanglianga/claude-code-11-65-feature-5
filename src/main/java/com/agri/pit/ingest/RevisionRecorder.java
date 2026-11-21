package com.agri.pit.ingest;

import com.agri.pit.common.Db;
import com.agri.pit.model.SourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RevisionRecorder {

    private final Db db;

    public RevisionRecorder(Db db) {
        this.db = db;
    }

    public long record(SourceType type, String table, long sourceId, Long parcelId, String externalKey,
                       int revisionNo, Long previousId, Instant observedAt, Instant ingestedAt,
                       Instant windowStart, Instant windowEnd, String description) {
        return db.insert("""
                        insert into data_revision_event
                            (source_type, source_table, source_id, external_key, revision_no, previous_id,
                             parcel_id, observed_at, ingested_at, window_start, window_end, description)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                type.name(), table, sourceId, externalKey, revisionNo, previousId, parcelId,
                Db.timestamp(observedAt), Db.timestamp(ingestedAt),
                Db.timestamp(windowStart), Db.timestamp(windowEnd), description);
    }
}
