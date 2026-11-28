package com.cardauth.auth.card;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "cards")
public class Card {
    @Id private String id;
    private String holder;
    private long creditLimit;
    @Version private long version;

    protected Card() {}
    public Card(String id, String holder, long creditLimit) { this.id = id; this.holder = holder; this.creditLimit = creditLimit; }

    public String getId() { return id; }
    public String getHolder() { return holder; }
    public long getCreditLimit() { return creditLimit; }
    public void setCreditLimit(long l) { this.creditLimit = l; }
}
