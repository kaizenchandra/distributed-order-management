package dev.oms.infra;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class BacklogMetrics {
    final JdbcTemplate db;
    final String service;
    final AtomicLong outbox = new AtomicLong(),
            oldest = new AtomicLong(),
            review = new AtomicLong(),
            gaps = new AtomicLong();

    public BacklogMetrics(
            JdbcTemplate db, MeterRegistry metrics, @Value("${spring.application.name}") String service) {
        this.db = db;
        this.service = service;
        metrics.gauge("oms.outbox.pending", outbox);
        metrics.gauge("oms.outbox.oldest.seconds", oldest);
        metrics.gauge("oms.saga.manual.review", review);
        metrics.gauge("oms.projection.gaps", gaps);
    }

    @Scheduled(fixedDelay = 15000)
    public void sample() {
        try {
            outbox.set(
                    db.queryForObject("select count(*) from outbox where published_at is null", Long.class));
            oldest.set(
                    db.queryForObject(
                            "select coalesce(extract(epoch from now()-min(created_at)),0)::bigint from outbox"
                                    + " where published_at is null",
                            Long.class));
            if (service.equals("saga-orchestrator"))
                review.set(
                        db.queryForObject(
                                "select count(*) from saga_instances where state='MANUAL_REVIEW'", Long.class));
            if (service.equals("order-query-service"))
                gaps.set(
                        db.queryForObject(
                                "select count(*) from projection_events e join order_read_models r on"
                                        + " e.order_id=r.order_id where e.version>r.version",
                                Long.class));
        } catch (org.springframework.dao.DataAccessException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Backlog metrics unavailable");
        }
    }
}
