package io.mindspice.jxch.transact.service.transaction;

import io.mindspice.jxch.rpc.schemas.object.Coin;

import java.util.List;


public record TransactionReturn(
        boolean success,
        List<TransactionItem> transactionItems,
        List<Coin> newCoins
) { }


