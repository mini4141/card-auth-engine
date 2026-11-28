package com.cardauth.auth.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Available credit for the db-* strategies. The version column drives the optimistic strategy. */
@Entity
@Table(name = "card_balances")
public class CardBalance {
    @Id @Column(name = "card_id") private String cardId;
    private long available;
    @Version private long version;

    protected CardBalance() {}
    public CardBalance(String cardId, long available) { this.cardId = cardId; this.available = available; }

    public String getCardId() { return cardId; }
    public long getAvailable() { return available; }
    public void setAvailable(long a) { this.available = a; }
}
