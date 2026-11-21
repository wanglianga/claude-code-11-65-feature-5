package com.agri.pit.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pointInTimeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agriculture drought claim point-in-time feature service")
                        .version("1.0.0")
                        .description("""
                                Feature catalog, point-in-time warehouse, backfill orchestrator, leakage detector,
                                scoring snapshots, revision impact analysis, and a shadow-first release governance
                                layer (shadow scoring, temporal ordering rules, reversal attribution, release gates
                                and targeted rollbacks) for drought claim verification.

                                **Two clocks.** Every warehouse record stores `observedAt` (when the phenomenon happened)
                                and `ingestedAt` (when the platform could actually see it). Features are always selected
                                with `ingestedAt <= availabilityCeiling`, never by observation time alone.

                                **Three guarantees.**
                                1. A scene acquired before the hazard but ingested after the scoring cutoff is excluded
                                   from the original run and recorded as a leakage exclusion.
                                2. When two parcel boundary versions cover the same ground, each raster cell belongs to
                                   exactly one version; the case's locked version wins and a boundary-variant run sees the
                                   shared pixels excluded instead of double counting them.
                                3. A weather revision that arrives after settlement only creates a pending review
                                   candidate. The frozen snapshot and the settled payout are never rewritten.

                                **Shadow-first release governance.** Candidate definition versions are scored twice over
                                frozen case snapshots (mode SHADOW) without affecting production claims; over-threshold
                                diffs on a complete input chain are BUSINESS_DIFFERENCE for rule-owner review while a
                                broken temporal chain is a blocked TIME_TRAVEL_FAILURE. Release gates bind the approval to
                                a catalog fingerprint and invalidate themselves on any catalog change, and targeted
                                rollbacks disable reads of one definition version (optionally for specific cases),
                                terminating only the backfill shards that depend on it.
                                """)
                        .contact(new Contact().name("Claims platform"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Local instance")));
    }
}
