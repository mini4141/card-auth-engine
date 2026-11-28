package com.cardauth.auth.store;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

/** auth_log(PK=CARD#id, SK=TS#iso#authId, decision, amount, merchant, strategy, latencyMs) */
@DynamoDbBean
public class AuthLogItem {
    private String pk;
    private String sk;
    private String authId;
    private String decision;
    private String reason;
    private long amount;
    private String merchant;
    private String strategy;
    private long latencyMs;

    @DynamoDbPartitionKey public String getPk() { return pk; }
    public void setPk(String pk) { this.pk = pk; }
    @DynamoDbSortKey public String getSk() { return sk; }
    public void setSk(String sk) { this.sk = sk; }
    public String getAuthId() { return authId; }
    public void setAuthId(String authId) { this.authId = authId; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
}
