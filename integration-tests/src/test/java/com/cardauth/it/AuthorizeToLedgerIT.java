package com.cardauth.it;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** authorise -> Kafka -> settlement ledger row + DynamoDB audit item (one approval, one decline, one retry). */
class AuthorizeToLedgerIT {

    @BeforeAll
    static void up() { Stack.start(); }

    @Test
    void approvalFlowsThroughKafkaIntoLedgerAndAuditLog() throws Exception {
        String card = Stack.createCard(10_000).get("id").asText();
        assertEquals(10_000, Stack.get(Stack.base(0) + "/cards/" + card).get("available").asLong()); // F1: available = limit

        JsonNode ok = Stack.authorise(0, card, 4_000, "redis-lua", "key-1");
        JsonNode retry = Stack.authorise(1, card, 4_000, "redis-lua", "key-1"); // other instance, same idempotency key
        JsonNode declined = Stack.authorise(1, card, 9_000, "redis-lua", null);

        assertEquals("APPROVED", ok.get("decision").asText());
        assertEquals(ok.get("authId").asText(), retry.get("authId").asText()); // same decision, no second hold
        assertEquals("DECLINED", declined.get("decision").asText());
        assertEquals(6_000, Stack.get(Stack.base(0) + "/cards/" + card).get("available").asLong());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertEquals(1, Stack.queryLong("select count(*) from ledger_postings where card_id = '" + card + "'"));
            assertEquals(4_000, Stack.queryLong("select sum(amount) from ledger_postings where card_id = '" + card + "'"));
        });
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode log = Stack.get(Stack.base(0) + "/cards/" + card + "/authorizations");
            assertEquals(2, log.size()); // one APPROVED + one DECLINED item in DynamoDB
        });
        assertEquals(0, Stack.get(Stack.base(0) + "/cards/" + card).get("holds").asLong()); // hold captured by settlement
    }
}
