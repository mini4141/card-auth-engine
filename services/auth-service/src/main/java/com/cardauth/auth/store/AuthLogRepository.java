package com.cardauth.auth.store;

import com.cardauth.auth.config.AppProperties;
import com.cardauth.domain.AuthorizationEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;

/** DynamoDB audit log keyed by card + time. */
@Repository
public class AuthLogRepository {
    private static final Logger log = LoggerFactory.getLogger(AuthLogRepository.class);

    private final DynamoDbTable<AuthLogItem> table;
    private final boolean createTable;

    public AuthLogRepository(DynamoDbEnhancedClient client, AppProperties props) {
        this.table = client.table(props.dynamo().table(), TableSchema.fromBean(AuthLogItem.class));
        this.createTable = props.dynamo().createTable();
    }

    @EventListener(ApplicationReadyEvent.class)
    void ensureTable() {
        if (!createTable) return;
        try {
            table.describeTable();
        } catch (ResourceNotFoundException e) {
            table.createTable();
            log.info("created DynamoDB table {}", table.tableName());
        }
    }

    public void save(AuthorizationEvent e, long latencyMs) {
        AuthLogItem i = new AuthLogItem();
        i.setPk("CARD#" + e.cardId());
        i.setSk("TS#" + e.ts() + "#" + e.authId());
        i.setAuthId(e.authId());
        i.setDecision(e.decision());
        i.setReason(e.reason());
        i.setAmount(e.amount());
        i.setMerchant(e.merchant());
        i.setStrategy(e.strategy());
        i.setLatencyMs(latencyMs);
        table.putItem(i);
    }

    public List<AuthLogItem> byCard(String cardId, int limit) {
        var req = QueryEnhancedRequest.builder()
                .queryConditional(QueryConditional.keyEqualTo(Key.builder().partitionValue("CARD#" + cardId).build()))
                .scanIndexForward(false).limit(limit).build();
        return table.query(req).items().stream().limit(limit).toList();
    }
}
