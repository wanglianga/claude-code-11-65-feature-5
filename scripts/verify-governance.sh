#!/usr/bin/env bash
# End-to-end verification of the shadow-first release governance layer.
# Runs against a freshly seeded instance (default http://localhost:8080). It drives the three
# scripted demos and asserts the shadow isolation, release-gate and targeted-rollback guarantees.
# Exits non-zero on the first failed assertion.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
JQ="${JQ:-jq}"
fail=0

need() { command -v "$1" >/dev/null || { echo "missing dependency: $1"; exit 2; }; }
need curl
need "$JQ"

say() { printf '\n=== %s\n' "$*"; }
ok()  { printf '  PASS  %s\n' "$*"; }
bad() { printf '  FAIL  %s\n' "$*"; fail=1; }
expect_eq() { # actual expected label
  if [ "$1" = "$2" ]; then ok "$3 ($1)"; else bad "$3: expected [$2] got [$1]"; fi
}
json() { curl -fsS "$@"; }

say "waiting for service"
for i in $(seq 1 60); do
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -fsS "$BASE/actuator/health" >/dev/null || { echo "service is not reachable at $BASE"; exit 1; }

# ---------------------------------------------------------------------------
say "governance demo 1: shadow comparison never touches production claims"
SNAP_BEFORE=$(json "$BASE/api/cases/DROUGHT-2024-0001/snapshots" | "$JQ" 'length')
# The original point-in-time suite may already have produced review candidates; shadow scoring
# must not add any, so compare counts rather than assuming an empty table.
CANDIDATES_BEFORE=$(json "$BASE/api/demo/scenarios" | "$JQ" '.counts.candidates')
SHADOW=$(json -XPOST "$BASE/api/demo/governance/shadow" -H 'Content-Type: application/json')
RUN_COUNT=$(echo "$SHADOW" | "$JQ" '.shadowRuns | length')
expect_eq "$RUN_COUNT" "2" "one shadow run per frozen case"
TEMPORAL=$(echo "$SHADOW" | "$JQ" -r '[.shadowRuns[].temporalVerdict] | unique | join(",")')
expect_eq "$TEMPORAL" "PASS" "every shadow run passes the temporal ordering rules"
CHAIN=$(echo "$SHADOW" | "$JQ" -r '[.shadowRuns[].chainComplete] | unique | join(",")')
expect_eq "$CHAIN" "true" "input chains are complete"
CLASS=$(echo "$SHADOW" | "$JQ" -r '[.shadowRuns[].features[] | select(.featureCode=="NDVI_DROUGHT") | .classification] | unique | join(",")')
expect_eq "$CLASS" "BUSINESS_DIFFERENCE" "over-threshold diff on a complete chain is a business difference"
ROUTING=$(echo "$SHADOW" | "$JQ" -r '[.shadowRuns[].features[] | select(.featureCode=="NDVI_DROUGHT") | .attribution.reviewRouting] | unique | join(",")')
expect_eq "$ROUTING" "RULE_OWNER_REVIEW" "business difference is routed to the rule owner"
STATUS=$(json "$BASE/api/cases/DROUGHT-2024-0001" | "$JQ" -r '.summary.status')
case "$STATUS" in SCORED|PAID) ok "claim status unchanged ($STATUS)";; *) bad "claim status changed to $STATUS";; esac
SNAP_AFTER=$(json "$BASE/api/cases/DROUGHT-2024-0001/snapshots" | "$JQ" 'length')
expect_eq "$SNAP_AFTER" "$SNAP_BEFORE" "no new snapshot was written by shadow scoring"
CANDIDATES_AFTER=$(json "$BASE/api/demo/scenarios" | "$JQ" '.counts.candidates')
expect_eq "$CANDIDATES_AFTER" "$CANDIDATES_BEFORE" "shadow scoring created no review candidate"

# ---------------------------------------------------------------------------
say "governance demo 2: release gates block bad definitions and invalidate on catalog change"
GATES=$(json -XPOST "$BASE/api/demo/governance/gates" -H 'Content-Type: application/json')
CLEAN=$(echo "$GATES" | "$JQ" -r '.cleanGateApproval.status // empty')
expect_eq "$CLEAN" "APPROVED" "threshold- and temporal-passing definition is promoted"
TT_REFUSAL=$(echo "$GATES" | "$JQ" -r '.timeTravelApprovalAttempt.errorCode // empty')
expect_eq "$TT_REFUSAL" "GATE_CHECKS_FAILED" "post-cutoff effective definition fails the gate checks"
TT_TEMPORAL=$(echo "$GATES" | "$JQ" -r '.timeTravelGate.temporalPassed')
expect_eq "$TT_TEMPORAL" "false" "the time-travel gate records temporalPassed=false"
INV_STATUS=$(echo "$GATES" | "$JQ" -r '.invalidatedGate.status // empty')
expect_eq "$INV_STATUS" "INVALIDATED" "catalog mutation while open invalidates the gate"
INV_REFUSAL=$(echo "$GATES" | "$JQ" -r '.invalidatedApprovalAttempt.errorCode // empty')
expect_eq "$INV_REFUSAL" "GATE_INVALIDATED" "an invalidated gate cannot be approved"

# ---------------------------------------------------------------------------
say "governance demo 3: targeted rollback stops only dependent backfill shards"
RB=$(json -XPOST "$BASE/api/demo/governance/rollback" -H 'Content-Type: application/json')
BATCH_STATUS=$(echo "$RB" | "$JQ" -r '.batch.status')
expect_eq "$BATCH_STATUS" "PARTIALLY_TERMINATED" "batch is partially terminated, not fully aborted"
SHARD_STATUSES=$(echo "$RB" | "$JQ" -c '[.batch.shards[] | {feature: .featureCode, status: .status}]')
echo "  shards: $SHARD_STATUSES"
echo "$RB" | "$JQ" -e '.batch.shards[] | select(.shardNo==1) | .status=="SUCCEEDED"' >/dev/null \
  && ok "finished shard keeps its succeeded result" || bad "finished shard was not preserved"
echo "$RB" | "$JQ" -e '.batch.shards[] | select(.shardNo==2) | .status=="TERMINATED_BY_ROLLBACK"' >/dev/null \
  && ok "in-flight shard on the rolled-back version is terminated" || bad "in-flight shard was not terminated"
echo "$RB" | "$JQ" -e '.batch.shards[] | select(.shardNo==3) | .status=="SUCCEEDED"' >/dev/null \
  && ok "other-definition-version shard keeps running and succeeds" || bad "independent shard was affected"
echo "$RB" | "$JQ" -e '.batch.shards[] | select(.shardNo==4) | .status=="QUARANTINED"' >/dev/null \
  && ok "still-queued shard is quarantined before reading the disabled version" || bad "queued shard was not quarantined"
TERM_RUN=$(echo "$RB" | "$JQ" -r '.batch.shards[] | select(.shardNo==2) | .featureRunId')
RUN_STATUS=$(json "$BASE/api/runs/$TERM_RUN" | "$JQ" -r '.run.status')
expect_eq "$RUN_STATUS" "TERMINATED" "the terminated shard's feature run is marked TERMINATED"
SNAP_FINAL=$(json "$BASE/api/cases/DROUGHT-2024-0002/snapshots" | "$JQ" 'length')
expect_eq "$SNAP_FINAL" "1" "the frozen snapshot of the targeted case is untouched"

# ---------------------------------------------------------------------------
printf '\n'
if [ "$fail" -eq 0 ]; then
  echo "ALL GOVERNANCE CHECKS PASSED"
  exit 0
else
  echo "SOME GOVERNANCE CHECKS FAILED"
  exit 1
fi
