package com.agri.pit.cases;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private final CaseService cases;
    private final com.agri.pit.runs.RunService runs;

    public CaseController(CaseService cases, com.agri.pit.runs.RunService runs) {
        this.cases = cases;
        this.runs = runs;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CaseDtos.CreateCaseRequest req) {
        long id = cases.createCase(req);
        return Map.of("id", id, "queued", true);
    }

    @GetMapping
    public List<CaseDtos.CaseSummary> list() {
        return cases.listCases();
    }

    @GetMapping("/{caseNo}")
    public CaseDtos.CaseDetail get(@PathVariable String caseNo) {
        return cases.getCase(caseNo);
    }

    @PostMapping("/{caseNo}/runs")
    public Map<String, Object> requestRun(@PathVariable String caseNo,
                                          @RequestBody(required = false) CaseRunRequest req) {
        long id = runs.queueRunForCase(caseNo, req == null ? null : req.reason());
        return Map.of("runId", id, "status", "QUEUED");
    }

    @GetMapping("/{caseNo}/trace")
    public List<com.agri.pit.runs.RunDtos.RunView> trace(@PathVariable String caseNo) {
        return runs.traceCase(caseNo);
    }

    @PostMapping("/{caseNo}/settle")
    public Map<String, Object> settle(@PathVariable String caseNo,
                                      @RequestBody(required = false) SettleRequest req) {
        cases.settle(caseNo, req == null ? null : req.settledBy(), req == null ? null : req.note());
        return Map.of("caseNo", caseNo, "status", "PAID");
    }

    public record CaseRunRequest(String reason) {
    }

    public record SettleRequest(String settledBy, String note) {
    }
}
