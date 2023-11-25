package io.mindspice.jxch.transact.service.transaction;

import com.fasterxml.jackson.databind.JsonNode;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.CoinRecord;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.schemas.wallet.Addition;
import io.mindspice.jxch.rpc.schemas.wallet.SignedTransaction;
import io.mindspice.jxch.rpc.util.ChiaUtils;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.service.ExcludedCoinRepo;
import io.mindspice.jxch.transact.service.TJob;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.service.TransactionState;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.jxch.transact.util.Pair;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;


public class TransactionJob extends TJob implements Callable<Pair<Boolean, List<TransactionItem>>> {
    private final List<TransactionItem> txItems;

    private List<Coin> parentCoins;
    private List<Coin> createdCoins;

    public TransactionJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(config, tLogger, nodeAPI, walletAPI);
        txItems = new CopyOnWriteArrayList<>();
    }

    public void addTransaction(TransactionItem transactionItem) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        txItems.add(transactionItem);
    }

    public void addTransaction(List<TransactionItem> transactionItem) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        txItems.addAll(transactionItem);
    }

    public void addExcludedCoin(Coin excluded) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        excludedCoins.add(excluded);
    }

    public void addExcludedCoins(List<Coin> excluded) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        excludedCoins.addAll(excluded);
    }

    @Override
    public Pair<Boolean, List<TransactionItem>> call() throws Exception {
        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                " | Started Transaction Job for Additions: " + txItems);
        try {
            Pair<SpendBundle, List<Coin>> txData;
            try {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Acquiring excluded coins semaphore");
                ExcludedCoinRepo.getSemaphore().acquire();
                txData = getAssetBundle();
                excludedCoins.addAll(txData.second());
            } finally {
                ExcludedCoinRepo.getSemaphore().release();
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Released excluded coins semaphore");
            }

            SpendBundle assetBundle = txData.first();
            List<Coin> jobCoins = txData.second();
            long bundleCost = getSpendCost(assetBundle);
            long feePerCost = getFeePerCostNeeded(bundleCost);
            if (feePerCost > 0) { feePerCost = Math.max(Math.max(feePerCost, 5), config.minFeePerCost); }
            feePerCost = Math.min(feePerCost, config.maxFeePerCost);
            long feeAmount = feePerCost * bundleCost;

            // Get max so coin can be reused for all fee calculations

            Coin feeCoin;
            try {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Acquiring excluded coins semaphore");
                ExcludedCoinRepo.getSemaphore().acquire();
                feeCoin = getFeeCoin(bundleCost * config.maxFeePerCost, new ArrayList<>(excludedCoins));
                excludedCoins.add(feeCoin);
            } finally {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Released excluded coins semaphore");
                ExcludedCoinRepo.getSemaphore().release();
            }

            SpendBundle aggBundle;
            if (feeAmount != 0) {
                SpendBundle feeBundle = getFeeBundle(feeCoin, feeAmount);
                aggBundle = walletAPI.aggregateSpends(List.of(assetBundle, feeBundle))
                        .data().orElseThrow(dataExcept("WalletAPI.aggregateSpends"));
            } else {
                aggBundle = assetBundle;
            }

            tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                    " | Parent Coins: " + parentCoins.stream().map(ChiaUtils::getCoinId).toList() +
                    " | Fee Coin Parent: " + feeCoin.parentCoinInfo());

            state = State.STARTED;

            tState = new TransactionState(
                    txItems.stream().map(TransactionItem::uuid).toList(),
                    bundleCost,
                    feePerCost,
                    feeAmount,
                    feeCoin,
                    assetBundle,
                    aggBundle,
                    jobCoins
            );
            boolean success = transactionLoop(tState);
            if (!success) {
                tLogger.log(this.getClass(), TLogLevel.FAILED, "Job: " + jobId +
                        " | Status: Total Failure" +
                        " | Reason: All iteration failed.");
                state = State.FAILED;
            }
            excludedCoins.remove(tState.feeCoin);
            tState.jobCoins.forEach(excludedCoins::remove);
            return new Pair<>(success, success ? getReturn(createdCoins) : txItems);

        } catch (Exception ex) {
            tLogger.log(this.getClass(), TLogLevel.FAILED, "Job: " + jobId +
                    " | Exception: " + ex.getMessage() +
                    " | Failed Transaction Items: " + txItems, ex);
            state = State.EXCEPTION;
            if (tState != null) {
                excludedCoins.remove(tState.feeCoin);
                tState.jobCoins.forEach(excludedCoins::remove);
            }
            throw ex;
        }
    }

    private Pair<SpendBundle, List<Coin>> getAssetBundle() throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle");
        long totalAmount = txItems.stream().mapToLong(i -> i.addition().amount()).sum();

        JsonNode coinReq = new RequestUtils.SelectCoinBuilder()
                .setWalletId(config.fundWalletId)
                .setAmount(totalAmount)
                .setExcludedCoins(new ArrayList<>(excludedCoins))
                .build();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle.getSpendableCoins");

        List<Coin> txCoins = walletAPI.selectCoins(coinReq).data()
                .orElseThrow(dataExcept("WalletAPI.getSpendableCoins"));

//        long sumNeeded = totalAmount;
//        List<Coin> txCoins = new ArrayList<>();
//        for (Coin coin : spendableCoins) {
//            if (sumNeeded > 0) {
//                txCoins.add(coin);
//                sumNeeded -= coin.amount();
//            } else { break; }
//        }

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Asset coins selected: " + txCoins.stream().map(ChiaUtils::getCoinId).toList());

        List<Addition> finalAdditions = txItems.stream().map(TransactionItem::addition).collect(Collectors.toList());

        long changeAmount = txCoins.stream().mapToLong(Coin::amount).sum() - totalAmount;
        if (changeAmount != 0) {
            Addition changeAddition = new Addition(config.changeTarget, changeAmount);
            finalAdditions.add(changeAddition);
        }

        parentCoins = txCoins;
        excludedCoins.addAll(txCoins);

        JsonNode xchSpendRequest = new RequestUtils.SignedTransactionBuilder()
                .setWalletId(config.fundWalletId)
                .addAdditions(finalAdditions)
                .addCoin(txCoins)
                .build();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle.createSignedTransaction");

        SignedTransaction signedTransaction = walletAPI.createSignedTransaction(xchSpendRequest)
                .data().orElseThrow(dataExcept("WalletAPI.createSignedTransaction"));

        createdCoins = signedTransaction.additions();
        return new Pair<>(signedTransaction.spendBundle(), txCoins);
    }

    private List<TransactionItem> getReturn(List<Coin> coins) {
        List<TransactionItem> rtnList = new ArrayList<>(txItems.size());
        for (int i = 0; i < txItems.size(); ++i) {
            rtnList.add(txItems.get(i).withCoin(coins.get(i)));
        }
        return rtnList;
    }
}
