package com.northwater;

import java.io.Serializable;

/** A transfer the customer has reviewed but not posted yet. */
public final class Pending implements Serializable {
    public final String nonce;
    public final String kind;
    public final String scope;
    public final int fromId;
    public final Integer toId;
    public final String payee;
    public final long amountCents;
    public final String memo;

    public Pending(
            String nonce,
            String kind,
            String scope,
            int fromId,
            Integer toId,
            String payee,
            long amountCents,
            String memo) {
        this.nonce = nonce;
        this.kind = kind;
        this.scope = scope;
        this.fromId = fromId;
        this.toId = toId;
        this.payee = payee;
        this.amountCents = amountCents;
        this.memo = memo;
    }
}
