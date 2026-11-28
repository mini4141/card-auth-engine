package com.cardauth.settlement;

import com.cardauth.domain.AuthorizationEvent;
import com.cardauth.domain.Json;
import com.cardauth.domain.SettlementEvent;
import com.cardauth.domain.Topics;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationListener {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationListener.class);

    private final LedgerService ledger;
    private final KafkaTemplate<String, String> kafka;

    public AuthorizationListener(LedgerService ledger, KafkaTemplate<String, String> kafka) {
        this.ledger = ledger; this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.AUTHORIZATIONS, groupId = "settlement")
    public void onAuthorization(String payload) {
        AuthorizationEvent e = Json.read(payload, AuthorizationEvent.class);
        if (!e.approved()) return;
        try {
            if (ledger.post(e)) {
                kafka.send(Topics.SETTLEMENTS, e.cardId(), Json.write(new SettlementEvent(e.authId(), e.cardId(), e.amount(), Instant.now())));
            }
        } catch (LedgerService.HoldGone gone) {
            log.warn("skipping {}: {}", e.authId(), gone.getMessage());
        }
    }
}
