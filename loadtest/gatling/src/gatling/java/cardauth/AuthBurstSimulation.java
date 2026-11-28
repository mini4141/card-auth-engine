package cardauth;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fires USERS concurrent authorisations of AMOUNT yen at one card (injected within RAMP_SECONDS, 0 = all at once) using one strategy.
 * Parameters come from environment variables. Writes a small JSON summary to RESULT_FILE:
 * approved_count, declined_count, overspend_amount (approved * amount - limit, floored at 0).
 */
public class AuthBurstSimulation extends Simulation {

    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }

    final String baseUrl = env("BASE_URL", "http://localhost:8080");
    final String strategy = env("STRATEGY", "redis-lua");
    final String cardId = env("CARD_ID", "");
    final int users = Integer.parseInt(env("USERS", "1000"));
    final long amount = Long.parseLong(env("AMOUNT", "1000"));
    final long limit = Long.parseLong(env("LIMIT", "100000"));
    // Docker Desktop on Windows refuses part of a 1,000-connection SYN burst, so by default users are injected over RAMP_SECONDS.
    // MODE=burst (default): USERS requests at once. MODE=steady: RATE new requests/s for DURATION seconds.
    final String mode = env("MODE", "burst");
    final int rate = Integer.parseInt(env("RATE", "100"));
    final int duration = Integer.parseInt(env("DURATION", "20"));
    final int rampSeconds = Integer.parseInt(env("RAMP_SECONDS", "3"));
    final long p99Max = Long.parseLong(env("P99_MAX_MS", "60000"));
    final String resultFile = env("RESULT_FILE", "build/bench-result.json");

    final LongAdder approved = new LongAdder();
    final LongAdder declined = new LongAdder();
    final Map<String, Boolean> instances = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
    final AtomicLong firstStart = new AtomicLong(Long.MAX_VALUE);
    final AtomicLong lastEnd = new AtomicLong(0);

    static long pct(java.util.List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p / 100.0 * sorted.size()) - 1));
    }

    HttpProtocolBuilder protocol = http.baseUrl(baseUrl).acceptHeader("application/json").contentTypeHeader("application/json").shareConnections();

    ScenarioBuilder scn = scenario("authorise-" + strategy).exec(
            http("POST /authorizations [" + strategy + "]")
                    .post("/authorizations")
                    .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                    .header("X-Strategy", strategy)
                    .body(StringBody("{\"cardId\":\"" + cardId + "\",\"amount\":" + amount + ",\"merchant\":\"GatlingMart\",\"mcc\":\"5411\"}"))
                    .check(status().is(200))
                    .check(responseTimeInMillis().transform(rtI -> {
                        long rt = rtI;
                        long now = System.currentTimeMillis();
                        latencies.add(rt);
                        firstStart.accumulateAndGet(now - rt, Math::min);
                        lastEnd.accumulateAndGet(now, Math::max);
                        return rtI;
                    }))
                    .check(header("X-Instance").transform(i -> { instances.put(i, true); return i; }).optional())
                    .check(jsonPath("$.decision").transform(d -> {
                        if ("APPROVED".equals(d)) approved.increment(); else declined.increment();
                        return d;
                    })));

    {
        setUp(scn.injectOpen(injection())).protocols(protocol)
                .assertions(
                        global().failedRequests().count().is(0L),
                        global().responseTime().percentile(99).lt((int) p99Max));
    }

    io.gatling.javaapi.core.OpenInjectionStep injection() {
        if ("steady".equals(mode)) return constantUsersPerSec(rate).during(java.time.Duration.ofSeconds(duration));
        return rampSeconds <= 0 ? atOnceUsers(users) : rampUsers(users).during(java.time.Duration.ofSeconds(rampSeconds));
    }

    long completedUsers() { return "steady".equals(mode) ? (long) rate * duration : users; }

    @Override
    public void after() {
            try {
                long a = approved.sum();
                long over = Math.max(0, a * amount - limit);
                var sorted = new ArrayList<>(latencies);
                Collections.sort(sorted);
                double secs = Math.max(0.001, (lastEnd.get() - firstStart.get()) / 1000.0);
                String json = String.format(java.util.Locale.ROOT,
                        "{\"strategy\":\"%s\",\"mode\":\"%s\",\"users\":%d,\"amount\":%d,\"limit\":%d,\"approved_count\":%d,\"declined_count\":%d,\"overspend_amount\":%d,\"instances_seen\":%d,\"p50_ms\":%d,\"p95_ms\":%d,\"p99_ms\":%d,\"throughput_rps\":%.1f,\"completed\":%d}",
                        strategy, mode, completedUsers(), amount, limit, a, declined.sum(), over, instances.size(),
                        pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.size() / secs, sorted.size());
                Path p = Path.of(resultFile);
                if (p.getParent() != null) Files.createDirectories(p.getParent());
                Files.writeString(p, json);
                System.out.println("BENCH_RESULT " + json);
            } catch (Exception e) { throw new RuntimeException(e); }
    }
}
