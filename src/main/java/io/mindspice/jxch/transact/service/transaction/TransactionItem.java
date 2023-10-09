package io.mindspice.jxch.transact.service.transaction;

import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.wallet.Addition;

import java.util.UUID;


public record TransactionItem(
        Addition addition,
        String uuid,
        Coin coin

) {
    public TransactionItem(Addition addition) {
        this(addition, UUID.randomUUID().toString(), null);
    }

    public TransactionItem(Addition addition, String uuid) {
        this(addition, uuid, null);
    }

    public TransactionItem withCoin(Coin coin) {
        return new TransactionItem(
                this.addition,
                this.uuid,
                coin
        );
    }
}
