package com.agri.pit.runs;

import com.agri.pit.common.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drain loop for the run queue. Runs are drained in FIFO order; each run is claimed with a
 * conditional update so multiple instances cannot execute the same run twice.
 */
@Component
public class RunWorker {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final Db db;
    private final RunExecutor executor;
    private final boolean enabled;

    public RunWorker(Db db, RunExecutor executor,
                     @Value("${app.orchestrator.worker-enabled:true}") boolean enabled) {
        this.db = db;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.orchestrator.worker-poll-interval-ms:500}")
    public void drainQueue() {
        if (!enabled) {
            return;
        }
        for (int i = 0; i < 5; i++) {
            // Shadow scoring and batch-backfill shards are driven synchronously by the governance
            // services, which own their state transitions; the queue worker must not claim them.
            List<Long> runIds = db.jdbc().query("""
                            select id from feature_run
                             where status = 'QUEUED'
                               and trigger_type not in ('SHADOW_SCORING', 'BATCH_BACKFILL')
                             order by requested_at, id limit 1
                            """, (rs, row) -> rs.getLong(1));
            if (runIds.isEmpty()) {
                return;
            }
            long runId = runIds.get(0);
            int claimed = db.jdbc().update("""
                            update feature_run set status = 'RUNNING', started_at = now()
                             where id = ? and status = 'QUEUED'
                            """, runId);
            if (claimed == 0) {
                continue;
            }
            try {
                executor.executeClaimed(runId);
            } catch (Exception e) {
                log.error("run {} failed", runId, e);
                db.jdbc().update("""
                                update feature_run set status = 'FAILED', finished_at = now(), error_message = ?
                                 where id = ?
                                """, truncate(e.getMessage()), runId);
            }
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "unknown error";
        }
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
