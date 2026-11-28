package com.cardauth.settlement;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Service
@RestController
@RequestMapping("/settlement/batches")
public class BatchService {
    private static final Logger log = LoggerFactory.getLogger(BatchService.class);

    private final JdbcTemplate jdbc;

    public BatchService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Aggregates ledger postings per card per day (re-runnable: upserts). */
    @PostMapping("/run")
    @Scheduled(cron = "${app.batch.cron:0 0 2 * * *}")
    public List<Map<String, Object>> run() {
        jdbc.update("""
                insert into settlement_batches (card_id, day, total)
                select card_id, (posted_at at time zone 'UTC')::date, sum(amount)
                from ledger_postings group by card_id, (posted_at at time zone 'UTC')::date
                on conflict (card_id, day) do update set total = excluded.total, created_at = now()""");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select card_id, day, total from settlement_batches order by day desc, card_id limit 100");
        log.info("settlement batch run: {} card-days", rows.size());
        return rows;
    }
}
