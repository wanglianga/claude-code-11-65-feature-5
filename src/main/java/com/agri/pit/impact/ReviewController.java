package com.agri.pit.impact;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewCandidateService candidates;

    public ReviewController(ReviewCandidateService candidates) {
        this.candidates = candidates;
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return candidates.list(status);
    }

    @GetMapping("/candidates/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return candidates.get(id);
    }

    @PostMapping("/candidates/{id}/decisions")
    public Map<String, Object> decide(@PathVariable long id, @RequestBody DecisionRequest req) {
        return candidates.decide(id, req.action(), req.decidedBy(), req.note());
    }

    public record DecisionRequest(String action, String decidedBy, String note) {
    }
}
