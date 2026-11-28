package com.cardauth.it;

import com.cardauth.auth.AuthServiceApplication;
import com.cardauth.domain.Json;
import com.cardauth.settlement.SettlementServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Real infrastructure (Postgres, Redis, Kafka, DynamoDB Local via Testcontainers) with TWO auth-service
 * instances and one settlement-service, each a separate Spring context on its own port.
 */
final class Stack {
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.6").withCommand("postgres", "-c", "max_connections=300");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.2").withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");
    static final GenericContainer<?> DYNAMO = new GenericContainer<>("amazon/dynamodb-local:2.6.1")
            .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb").withExposedPorts(8000);

    static ConfigurableApplicationContext auth1, auth2, settlement;
    static int port1, port2;
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static synchronized void start() {
        if (auth1 != null) return;
        PG.start(); REDIS.start(); KAFKA.start(); DYNAMO.start();
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        String[] common = {
                "spring.datasource.url=" + PG.getJdbcUrl(), "spring.datasource.username=" + PG.getUsername(),
                "spring.datasource.password=" + PG.getPassword(),
                "spring.data.redis.host=" + REDIS.getHost(), "spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "app.kafka.partitions=3", "server.port=0", "management.tracing.enabled=false",
                "logging.structured.format.console=", "logging.level.root=WARN", "logging.level.com.cardauth=INFO"};
        String[] authProps = concat(common, "app.dynamo.endpoint=http://" + DYNAMO.getHost() + ":" + DYNAMO.getMappedPort(8000),
                "app.reconcile.interval-ms=3600000");
        auth1 = new SpringApplicationBuilder(AuthServiceApplication.class).properties("spring.config.name=auth").run(args(authProps));
        auth2 = new SpringApplicationBuilder(AuthServiceApplication.class).properties("spring.config.name=auth").run(args(concat(authProps, "app.instance-id=auth-2")));
        settlement = new SpringApplicationBuilder(SettlementServiceApplication.class)
                .properties("spring.config.name=settlement").run(args(common));
        port1 = Integer.parseInt(auth1.getEnvironment().getProperty("local.server.port"));
        port2 = Integer.parseInt(auth2.getEnvironment().getProperty("local.server.port"));
    }

    // command-line args outrank the services' own yml placeholders
    private static String[] args(String[] props) {
        return java.util.Arrays.stream(props).map(p -> "--" + p).toArray(String[]::new);
    }

    private static String[] concat(String[] a, String... b) {
        String[] r = new String[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    static String base(int i) { return "http://localhost:" + (i % 2 == 0 ? port1 : port2); }

    static JsonNode createCard(long limit) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base(0) + "/cards"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"holder\":\"it\",\"limit\":" + limit + "}")).build(), HttpResponse.BodyHandlers.ofString());
        return Json.MAPPER.readTree(r.body());
    }

    static JsonNode authorise(int instance, String cardId, long amount, String strategy, String idemKey) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base(instance) + "/authorizations"))
                .header("Content-Type", "application/json").header("Idempotency-Key", idemKey == null ? UUID.randomUUID().toString() : idemKey)
                .header("X-Strategy", strategy)
                .POST(HttpRequest.BodyPublishers.ofString("{\"cardId\":\"" + cardId + "\",\"amount\":" + amount + ",\"merchant\":\"IT\",\"mcc\":\"5411\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        return Json.MAPPER.readTree(r.body());
    }

    static JsonNode get(String url) throws Exception {
        return Json.MAPPER.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body());
    }

    static long queryLong(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Stack() {}
}
