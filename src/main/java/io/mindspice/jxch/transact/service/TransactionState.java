package io.mindspice.jxch.transact.service;

import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;

import java.util.List;


public class TransactionState {
    public final List<String> itemIds;
    public final long bundleCost;
    public long feePerCost;
    public long feeAmount;
    public final Coin feeCoin;
    public boolean needReplaceFee = false;
    public final SpendBundle transactionBundle;
    public SpendBundle aggBundle;
    public final Coin confirmCoin;

    public TransactionState(List<String> itemIds, long bundleCost, long feePerCost, long feeAmount,
            Coin feeCoin, SpendBundle transactionBundle, SpendBundle aggBundle, Coin confirmCoin) {
        this.itemIds = itemIds;
        this.bundleCost = bundleCost;
        this.feePerCost = feePerCost;
        this.feeAmount = feeAmount;
        this.feeCoin = feeCoin;
        this.transactionBundle = transactionBundle;
        this.aggBundle = aggBundle;
        this.confirmCoin = confirmCoin;
    }
}
