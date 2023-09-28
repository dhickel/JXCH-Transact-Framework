package io.mindspice.jxch.transact.jobs.transaction;

import io.mindspice.jxch.rpc.schemas.wallet.Addition;
import io.mindspice.jxch.transact.jobs.mint.MintItem;

import java.util.UUID;


public record TransactionItem(
        Addition addition,
        String uuid

) {
    public TransactionItem(Addition addition) {
        this(addition, UUID.randomUUID().toString());
    }

    public TransactionItem {
        if (uuid == null) { uuid = UUID.randomUUID().toString(); }
    }
}
