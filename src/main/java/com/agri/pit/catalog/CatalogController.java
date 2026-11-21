package com.agri.pit.catalog;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/features")
    public List<CatalogDtos.FeatureSummary> list() {
        return catalog.listFeatures();
    }

    @PostMapping("/features")
    public IdResponse createFeature(@jakarta.validation.Valid @RequestBody CatalogDtos.CreateFeatureRequest req) {
        long id = catalog.createFeature(req);
        return new IdResponse(id);
    }

    @GetMapping("/features/{code}")
    public CatalogDtos.FeatureDetail get(@PathVariable String code) {
        return catalog.getFeature(code);
    }

    @PostMapping("/features/{code}/versions")
    public IdResponse createVersion(@PathVariable String code,
                                    @jakarta.validation.Valid @RequestBody CatalogDtos.CreateVersionRequest req) {
        return new IdResponse(catalog.createVersion(code, req));
    }

    @PostMapping("/features/{code}/versions/{versionNo}/approve")
    public CatalogDtos.VersionView approve(@PathVariable String code,
                                           @PathVariable int versionNo,
                                           @RequestBody(required = false) CatalogDtos.ApproveVersionRequest req) {
        CatalogDtos.ApproveVersionRequest body = req == null
                ? new CatalogDtos.ApproveVersionRequest(null, null) : req;
        catalog.approve(code, versionNo, body.approvedBy(), body.notes());
        CatalogDtos.FeatureDetail detail = catalog.getFeature(code);
        return detail.versions().stream()
                .filter(v -> v.versionNo() == versionNo)
                .findFirst()
                .orElseThrow();
    }

    public record IdResponse(long id) {
    }
}
