package com.agri.pit.cases;

import com.agri.pit.common.ApiException;
import com.agri.pit.common.Db;
import com.agri.pit.common.Times;
import com.agri.pit.catalog.CatalogService;
import com.agri.pit.catalog.CatalogService.ApprovedFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CaseService {

    private final Db db;
    private final CatalogService catalog;

    public CaseService(Db db, CatalogService catalog) {
        this.db = db;
        this.catalog = catalog;
    }

    @Transactional
    public long createCase(CaseDtos.CreateCaseRequest req) {
        if (req.caseNo() == null || req.caseNo().isBlank()) {
            throw ApiException.badRequest("CASE_NO_REQUIRED", "caseNo is required");
        }
        if (req.hazardDate() == null || req.scoringCutoff() == null) {
            throw ApiException.badRequest("CASE_DATES_REQUIRED", "hazardDate and scoringCutoff are required");
        }
        Integer exists = db.jdbc().queryForObject(
                "select count(*) from claim_case where case_no = ?", Integer.class, req.caseNo());
        if (exists != null && exists > 0) {
            throw ApiException.conflict("CASE_EXISTS", "case already exists: " + req.caseNo());
        }
        ParcelRow parcel = resolveParcel(req);
        long boundaryVersionId = req.boundaryVersionId() != null
                ? requireBoundaryVersionForParcel(req.boundaryVersionId(), parcel.id())
                : selectBoundaryVersionAt(parcel.id(), req.scoringCutoff());
        List<ApprovedFeature> features = catalog.approvedFeatures();
        if (features.isEmpty()) {
            throw ApiException.unprocessable("NO_APPROVED_FEATURES",
                    "no approved feature definitions exist; approve the catalog before opening a case");
        }
        String crop = req.cropCode() != null && !req.cropCode().isBlank()
                ? req.cropCode() : parcel.cropCode();
        long caseId = db.insert("""
                        insert into claim_case
                            (case_no, policy_no, parcel_id, crop_code, hazard_type, hazard_date,
                             scoring_cutoff, boundary_version_id, status)
                        values (?, ?, ?, ?, 'DROUGHT', ?, ?, ?, 'OPEN')
                        """,
                req.caseNo().trim(), req.policyNo() == null ? "PENDING" : req.policyNo(),
                parcel.id(), crop, req.hazardDate(),
                Db.timestamp(req.scoringCutoff()), boundaryVersionId);
        for (ApprovedFeature feature : features) {
            db.jdbc().update("""
                            insert into claim_case_feature_lock (case_id, feature_definition_version_id)
                            values (?, ?)
                            """, caseId, feature.versionId());
        }
        db.jdbc().update("update claim_case set status = 'QUEUED' where id = ?", caseId);
        enqueueOriginalRun(caseId, req.scoringCutoff(), req.hazardDate(), boundaryVersionId,
                "case intake");
        return caseId;
    }

    /**
     * Insert the first scoring run directly rather than through RunService, which depends on this
     * service. The run is executed by the queue worker.
     */
    private void enqueueOriginalRun(long caseId, Instant cutoff, LocalDate hazardDate, long boundaryVersionId,
                                    String reason) {
        db.insert("""
                        insert into feature_run
                            (case_id, mode, trigger_type, status, verdict, data_policy, cutoff_at,
                             availability_ceiling, hazard_date, boundary_version_id, boundary_variant,
                             reason, requested_by)
                        values (?, 'ORIGINAL', 'CASE_SCORING', 'QUEUED', 'NOT_EVALUATED', 'AS_OF_CUTOFF',
                                ?, ?, ?, ?, false, ?, 'case-intake')
                        """,
                caseId, Db.timestamp(cutoff), Db.timestamp(cutoff), hazardDate, boundaryVersionId, reason);
    }

    private ParcelRow resolveParcel(CaseDtos.CreateCaseRequest req) {
        if (req.parcelCode() == null || req.parcelCode().isBlank()) {
            throw ApiException.badRequest("PARCEL_CODE_REQUIRED", "parcelCode is required");
        }
        List<ParcelRow> rows = db.jdbc().query("""
                        select id, parcel_code, crop_code from parcel where parcel_code = ?
                        """,
                (rs, i) -> new ParcelRow(rs.getLong("id"), rs.getString("parcel_code"),
                        rs.getString("crop_code")),
                req.parcelCode().trim().toUpperCase());
        if (rows.isEmpty()) {
            throw ApiException.notFound("PARCEL_NOT_FOUND", "parcel not found: " + req.parcelCode());
        }
        return rows.get(0);
    }

    private long selectBoundaryVersionAt(long parcelId, Instant cutoff) {
        List<Long> ids = db.jdbc().query("""
                        select id from parcel_boundary_version
                         where parcel_id = ? and ingested_at <= ?
                         order by version_no desc limit 1
                        """,
                (rs, i) -> rs.getLong(1), parcelId, Db.timestamp(cutoff));
        if (ids.isEmpty()) {
            throw ApiException.unprocessable("NO_BOUNDARY_AT_CUTOFF",
                    "no parcel boundary version was available at the scoring cutoff; provide boundaryVersionId "
                            + "only if the case truly locks a later survey (the leakage detector will flag it)");
        }
        return ids.get(0);
    }

    private long requireBoundaryVersionForParcel(long versionId, long parcelId) {
        List<Long> ids = db.jdbc().query("""
                        select id from parcel_boundary_version where id = ? and parcel_id = ?
                        """, (rs, i) -> rs.getLong(1), versionId, parcelId);
        if (ids.isEmpty()) {
            throw ApiException.badRequest("BOUNDARY_VERSION_NOT_FOR_PARCEL",
                    "boundary version " + versionId + " does not belong to parcel " + parcelId);
        }
        return ids.get(0);
    }

    public List<CaseDtos.CaseSummary> listCases() {        return db.jdbc().query("""
                        select c.id, c.case_no, c.policy_no, p.parcel_code, c.crop_code, c.hazard_type,
                               c.hazard_date, c.scoring_cutoff, c.boundary_version_id, b.version_no, c.status,
                               (select max(s.created_at) from scoring_snapshot s where s.case_id = c.id) as snapshot_at
                          from claim_case c
                          join parcel p on p.id = c.parcel_id
                          join parcel_boundary_version b on b.id = c.boundary_version_id
                         order by c.case_no
                        """,
                (rs, i) -> mapSummary(rs));
    }

    /**
     * Mark a scored case as settled/paid. Settlement only moves OPEN/SCORED → PAID; it never
     * rewrites the snapshot. Any later data revision must go through review candidates.
     */
    @Transactional
    public void settle(String caseNo, String settledBy, String note) {
        CaseRow row = requireCase(caseNo);
        if (!row.status().equals("SCORED") && !row.status().equals("OPEN")
                && !row.status().equals("UNDER_REVIEW")) {
            throw ApiException.conflict("CASE_NOT_SETTLEABLE",
                    "case " + caseNo + " is " + row.status() + " and cannot be settled");
        }
        Integer snapshots = db.jdbc().queryForObject(
                "select count(*) from scoring_snapshot where case_id = ?", Integer.class, row.id());
        if (snapshots == null || snapshots == 0) {
            throw ApiException.unprocessable("NO_SNAPSHOT",
                    "case " + caseNo + " has no frozen snapshot; score it before settling");
        }
        db.jdbc().update("""
                        update claim_case set status = 'PAID', settled_at = now(), settled_by = ?
                         where id = ?
                        """, settledBy == null || settledBy.isBlank() ? "claims-officer" : settledBy.trim(),
                row.id());
    }

    public CaseDtos.CaseDetail getCase(String caseNo) {
        CaseRow row = requireCase(caseNo);
        List<CaseDtos.LockedFeatureView> locked = db.jdbc().query("""
                        select fd.code, fv.version_no, fv.formula, fv.required, fd.unit
                          from claim_case_feature_lock l
                          join feature_definition_version fv on fv.id = l.feature_definition_version_id
                          join feature_definition fd on fd.id = fv.feature_definition_id
                         where l.case_id = ?
                         order by fd.code
                        """,
                (rs, i) -> new CaseDtos.LockedFeatureView(rs.getString("code"), rs.getInt("version_no"),
                        rs.getString("formula"), rs.getBoolean("required"), rs.getString("unit")),
                row.id());
        List<CaseDtos.SnapshotSummary> snapshots = db.jdbc().query("""
                        select id, created_at, inputs_hash, scoring_cutoff, feature_values
                          from scoring_snapshot where case_id = ? order by created_at desc
                        """,
                (rs, i) -> new CaseDtos.SnapshotSummary(rs.getLong("id"),
                        Times.format(Db.instant(rs, "created_at")), rs.getString("inputs_hash"),
                        Times.format(Db.instant(rs, "scoring_cutoff")),
                        db.mapper().convertValue(db.readTree(rs.getString("feature_values")),
                                new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {
                                })),
                row.id());
        List<CaseDtos.RunSummary> runs = db.jdbc().query("""
                        select r.id, r.mode, r.status, r.verdict, r.cutoff_at, r.availability_ceiling,
                               b.version_no, r.boundary_variant, r.requested_at, r.finished_at,
                               r.result_snapshot_id, r.parent_run_id, r.reason, r.error_message
                          from feature_run r
                          left join parcel_boundary_version b on b.id = r.boundary_version_id
                         where r.case_id = ? order by r.requested_at desc, r.id desc
                        """,
                (rs, i) -> new CaseDtos.RunSummary(rs.getLong("id"), rs.getString("mode"),
                        rs.getString("status"), rs.getString("verdict"), Times.format(Db.instant(rs, "cutoff_at")),
                        Times.format(Db.instant(rs, "availability_ceiling")),
                        rs.getObject("version_no") == null ? null : "v" + rs.getInt("version_no"),
                        rs.getBoolean("boundary_variant"), Times.format(Db.instant(rs, "requested_at")),
                        Times.format(Db.instant(rs, "finished_at")),
                        (Long) rs.getObject("result_snapshot_id"), (Long) rs.getObject("parent_run_id"),
                        rs.getString("reason"), rs.getString("error_message")),
                row.id());
        return new CaseDtos.CaseDetail(toSummary(row), locked, snapshots, runs);
    }

    public CaseRow requireCase(String caseNo) {
        List<CaseRow> rows = db.jdbc().query("""
                        select c.id, c.case_no, c.policy_no, c.parcel_id, p.parcel_code, c.crop_code,
                               c.hazard_date, c.scoring_cutoff, c.boundary_version_id, b.version_no,
                               b.geometry_geojson, b.area_ha, b.observed_at as boundary_observed_at,
                               b.ingested_at as boundary_ingested_at, c.status
                          from claim_case c
                          join parcel p on p.id = c.parcel_id
                          join parcel_boundary_version b on b.id = c.boundary_version_id
                         where c.case_no = ?
                        """,
                (rs, i) -> new CaseRow(
                        rs.getLong("id"), rs.getString("case_no"), rs.getString("policy_no"),
                        rs.getLong("parcel_id"), rs.getString("parcel_code"), rs.getString("crop_code"),
                        rs.getObject("hazard_date", LocalDate.class), Db.instant(rs, "scoring_cutoff"),
                        rs.getLong("boundary_version_id"), rs.getInt("version_no"),
                        db.readTree(rs.getString("geometry_geojson")), rs.getBigDecimal("area_ha").doubleValue(),
                        Db.instant(rs, "boundary_observed_at"), Db.instant(rs, "boundary_ingested_at"),
                        rs.getString("status")),
                caseNo.trim().toUpperCase());
        if (rows.isEmpty()) {
            throw ApiException.notFound("CASE_NOT_FOUND", "case not found: " + caseNo);
        }
        return rows.get(0);
    }

    private CaseDtos.CaseSummary mapSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        OffsetDateTime snapshotAt = rs.getObject("snapshot_at", OffsetDateTime.class);
        return new CaseDtos.CaseSummary(rs.getLong("id"), rs.getString("case_no"),
                rs.getString("policy_no"), rs.getString("parcel_code"), rs.getString("crop_code"),
                rs.getString("hazard_type"), rs.getObject("hazard_date", LocalDate.class),
                Times.format(Db.instant(rs, "scoring_cutoff")), rs.getLong("boundary_version_id"),
                rs.getInt("version_no"), rs.getString("status"),
                snapshotAt == null ? null : Times.format(snapshotAt.toInstant()));
    }

    private CaseDtos.CaseSummary toSummary(CaseRow row) {
        Instant latest = db.jdbc().query("""
                        select max(created_at) as latest from scoring_snapshot where case_id = ?
                        """, rs -> rs.next() ? Db.instant(rs, "latest") : null, row.id());
        return new CaseDtos.CaseSummary(row.id(), row.caseNo(), row.policyNo(), row.parcelCode(),
                row.cropCode(), "DROUGHT", row.hazardDate(), Times.format(row.scoringCutoff()),
                row.boundaryVersionId(), row.boundaryVersionNo(), row.status(),
                Times.format(latest));
    }

    public record ParcelRow(long id, String parcelCode, String cropCode) {
    }

    public record CaseRow(long id, String caseNo, String policyNo, long parcelId, String parcelCode,
                          String cropCode, LocalDate hazardDate, Instant scoringCutoff,
                          long boundaryVersionId, int boundaryVersionNo,
                          com.fasterxml.jackson.databind.JsonNode boundaryGeometry, double boundaryAreaHa,
                          Instant boundaryObservedAt, Instant boundaryIngestedAt, String status) {
    }
}
