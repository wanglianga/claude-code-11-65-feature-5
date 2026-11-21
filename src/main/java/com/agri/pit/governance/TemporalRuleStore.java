package com.agri.pit.governance;

import com.agri.pit.common.Db;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Persists and reads {@link TemporalRuleSet.Finding} rows for shadow runs and release gates.
 */
@Component
public class TemporalRuleStore {

    private final Db db;

    public TemporalRuleStore(Db db) {
        this.db = db;
    }

    public void persistFindings(Long shadowRunId, Long gateId, List<TemporalRuleSet.Finding> findings) {
        for (TemporalRuleSet.Finding finding : findings) {
            db.insert("""
                            insert into temporal_order_finding
                                (shadow_run_id, gate_id, rule_code, severity, subject_type, subject_ref,
                                 expected_order, actual_order, message, passed, detail)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                            """,
                    shadowRunId, gateId, finding.ruleCode(), finding.severity(),
                    finding.subjectType(), finding.subjectRef(), finding.expectedOrder(),
                    finding.actualOrder(), finding.message(), finding.passed(),
                    db.jsonArg(finding.detail()));
        }
    }

    public List<GovernanceDtos.TemporalFindingView> findingsForShadow(long shadowRunId) {
        return db.jdbc().query("""
                        select id, rule_code, severity, subject_type, subject_ref, expected_order,
                               actual_order, message, passed
                          from temporal_order_finding where shadow_run_id = ? order by id
                        """,
                (rs, i) -> new GovernanceDtos.TemporalFindingView(rs.getLong("id"),
                        rs.getString("rule_code"), rs.getString("severity"), rs.getString("subject_type"),
                        rs.getString("subject_ref"), rs.getString("expected_order"),
                        rs.getString("actual_order"), rs.getString("message"), rs.getBoolean("passed")),
                shadowRunId);
    }

    public List<GovernanceDtos.TemporalFindingView> findingsForGate(long gateId) {
        return db.jdbc().query("""
                        select id, rule_code, severity, subject_type, subject_ref, expected_order,
                               actual_order, message, passed
                          from temporal_order_finding where gate_id = ? order by id
                        """,
                (rs, i) -> new GovernanceDtos.TemporalFindingView(rs.getLong("id"),
                        rs.getString("rule_code"), rs.getString("severity"), rs.getString("subject_type"),
                        rs.getString("subject_ref"), rs.getString("expected_order"),
                        rs.getString("actual_order"), rs.getString("message"), rs.getBoolean("passed")),
                gateId);
    }
}
