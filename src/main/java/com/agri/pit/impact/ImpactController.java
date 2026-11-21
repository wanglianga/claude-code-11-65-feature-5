package com.agri.pit.impact;

import com.agri.pit.common.Times;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/impact")
public class ImpactController {

    private final ImpactService impact;
    private final ReviewCandidateService candidates;

    public ImpactController(ImpactService impact, ReviewCandidateService candidates) {
        this.impact = impact;
        this.candidates = candidates;
    }

    @PostMapping("/analyses/by-revision/{revisionEventId}")
    public Map<String, Object> analyze(@PathVariable long revisionEventId) {
        ImpactService.AnalysisResult result = impact.analyze(revisionEventId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("analysisId", result.analysisId());
        body.put("revisionEventId", result.revisionEventId());
        body.put("sourceType", result.sourceType());
        body.put("externalKey", result.externalKey());
        body.put("revisionNo", result.revisionNo());
        body.put("ingestedAt", Times.format(result.ingestedAt()));
        body.put("summary", result.summary());
        body.put("cases", result.cases());
        return body;
    }

    @GetMapping("/analyses")
    public List<Map<String, Object>> list() {
        return impact.listAnalyses();
    }

    @GetMapping("/analyses/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return impact.getAnalysis(id);
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> listCandidates(@RequestParam(required = false) String status) {
        return candidates.list(status);
    }

    @GetMapping("/candidates/{id}")
    public Map<String, Object> candidate(@PathVariable long id) {
        return candidates.get(id);
    }

    /**
     * Queue a boundary-variant run for a case: same cutoff, alternative boundary version.
     * Demonstrates isolation when two boundary versions cover the same geography.
     */
    @PostMapping("/boundary-variant-runs")
    public Map<String, Object> boundaryVariant(@RequestBody BoundaryVariantRequest req) {
        long runId = candidates.queueBoundaryVariant(req.caseNo(), req.boundaryVersionId(), req.reason());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId);
        body.put("status", "QUEUED");
        return body;
    }

    public record BoundaryVariantRequest(String caseNo, long boundaryVersionId, String reason) {
    }
}
