package io.mindspice.jxch.transact.service.transaction;

import com.fasterxml.jackson.databind.JsonNode;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.CoinRecord;
import io.mindspice.jxch.rpc.schemas.object.CoinSpend;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.util.ChiaUtils;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.service.TJob;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.service.mint.MintItem;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.mindlib.data.tuples.Pair;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;


public class TransactionJob extends TJob implements Callable<Pair<Boolean, List<TransactionItem>>> {
    private final List<TransactionItem> txItems;
    private final boolean isCat;

    private List<Coin> parentCoins;

    public TransactionJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI, boolean isCat) {
        super(config, tLogger, nodeAPI, walletAPI);
        this.isCat = isCat;
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
        startHeight = nodeAPI.getHeight().data().orElseThrow(dataExcept("NodeAPI.getHeight"));

        boolean incFee = false;

        try {
            Pair<SpendBundle, List<Coin>> assetData = getAssetBundle();
            excludedCoins.addAll(assetData.second());
            SpendBundle assetBundle = assetData.first();
            long bundleCost = getSpendCost(assetBundle);
            long feePerCost = Math.min(getFeePerCostNeeded(bundleCost), config.maxFeePerCost);
            long feeAmount = feePerCost * bundleCost;

            // Get max so coin can be reused for all fee calculations
            Coin feeCoin = getFeeCoin(bundleCost * config.maxFeePerCost, excludedCoins);
            excludedCoins.add(feeCoin);
            SpendBundle feeBundle = getFeeBundle(feeCoin, feeAmount);
            SpendBundle aggBundle = walletAPI.aggregateSpends(List.of(assetBundle, feeBundle))
                    .data().orElseThrow(dataExcept("WalletAPI.aggregateSpends"));

            tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                    " | Parent Coins: " + parentCoins.stream().map(ChiaUtils::getCoinId).toList() +
                    " | Fee Coin: " + ChiaUtils.getCoinId(feeCoin));

            state = State.STARTED;
            for (int i = 0; i < config.maxRetries; ++i) {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: transactionIteration: " + i);

                // Spin until sync
                while (!walletAPI.getSyncStatus().data().orElseThrow(dataExcept("WalletAPI.getSyncStatus")).synced()) {
                    state = State.AWAITING_SYNC;
                    tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                            " | Failed iteration: " + i + "/" + config.maxRetries +
                            " | Reason: Wallet " + config.mintWalletId + " not Synced" +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                    Thread.sleep(config.retryWaitInterval);
                }
                state = i == 0 ? State.STARTED : State.RETRYING;

                if (i != 0 && !(feePerCost >= config.maxFeePerCost)) {
                    if (i % config.feeIncInterval == 0 || incFee) {
                        long currFeePerCost = getFeePerCostNeeded(bundleCost);
                        long baseFpc = Math.max(currFeePerCost, 5);
                        long incValue = (i / config.feeIncInterval);
                        long incFpc = baseFpc + incValue;
                        feePerCost = Math.min(incFpc, config.maxFeePerCost);
                        feeAmount = feePerCost * bundleCost;
                        incFee = false;

                        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                                " | Action: FeeReCalc" +
                                " | FeePerCost: " + feePerCost +
                                " | totalFee: " + feeAmount);
                        feeBundle = getFeeBundle(feeCoin, feeAmount);
                        aggBundle = walletAPI.aggregateSpends(List.of(assetBundle, feeBundle))
                                .data().orElseThrow(dataExcept("WalletAPI.aggregateSpends"));
                    }
                }

                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: PushingTransaction");
                var pushResponse = nodeAPI.pushTx(aggBundle);

                if (!pushResponse.success()) {
                    // Consider transaction a success if a coin include in the spendbundle was sent
                    // from a past iteration was successful and not recognized due to network delay or the coin was spent
                    // elsewhere as the result of user error.
                    if ((pushResponse.error().contains("DOUBLE_SPEND"))) {
                        tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                                " | Performed a DOUBLE_SPEND, transaction consider successful. This error can be ignored, " +
                                "but could result in a failed transaction if the coin(s) was spent elsewhere.");

                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | TransactJob: " + jobId + " Successful (DOUBLE_SPEND)" +
                                " | Fee: " + feeAmount +
                                " | Additions: " + txItems);
                        state = State.SUCCESS;
                        return new Pair<>(true, getReturn(assetBundle.coinSpends().stream().map(CoinSpend::coin).toList()));
                    } else if (pushResponse.error().contains("INVALID_FEE_TOO_CLOSE_TO_ZERO")) {
                        tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                                " | Failed iteration: " + i + "/" + config.maxRetries +
                                " | Reason: INVALID_FEE_TOO_CLOSE_TO_ZERO " +
                                " | Current Fee Per Cost: " + feePerCost +
                                " | Retrying in " + config.retryWaitInterval + "ms");
                        Thread.sleep(config.retryWaitInterval);
                        incFee = config.incFeeOnFail;
                        continue;
                    }
                }

                Pair<Boolean, String> txResponse =
                        checkMempoolForTx(pushResponse.data().orElseThrow(dataExcept("checkMempoolForTx")).spendBundleName());

                int waitReps = 0;
                while (waitReps < 10 && !txResponse.first()) {
                    Thread.sleep(5000);
                    waitReps++;
                    txResponse = checkMempoolForTx(pushResponse.data().orElseThrow(dataExcept("checkMempoolForTx")).spendBundleName());
                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Transaction State: Awaiting mempool detection" +
                            " | Wait Iteration: " + waitReps +
                            " | Note: You \"should\" + not see this message, if this is happening often there may be issues" +
                            "with your node and/or node resources");
                }

                if (txResponse.first()) {
                    state = State.AWAITING_CONFIRMATION;
                    boolean completed = waitForTxConfirmation(txResponse.second(), excludedCoins.get(0));
                    if (completed) {
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | TransactJob: " + jobId + " Successful" +
                                " | Fee: " + feeAmount +
                                " | Additions: " + txItems);
                        state = State.SUCCESS;
                        return new Pair<>(true, getReturn(assetBundle.coinSpends().stream().map(CoinSpend::coin).toList()));
                    } else {
                        incFee = config.incFeeOnFail;
                        tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                                " | Failed iteration: " + i + "/" + config.maxRetries +
                                " | Reason: Tx dropped from mempool without sending " +
                                " | Current Fee Per Cost: " + feePerCost +
                                " | Retrying in " + config.retryWaitInterval + "ms");
                    }
                } else {
                    tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                            " | Failed iteration: " + i + "/" + config.maxRetries +
                            " | Reason: Failed to locate tx in mempool " +
                            " | Current Fee Per Cost: " + feePerCost +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                }
                state = State.RETRYING;
                Thread.sleep(config.retryWaitInterval);
            }
        } catch (Exception ex) {
            tLogger.log(this.getClass(), TLogLevel.FAILED, "Job: " + jobId +
                    " | Exception: " + ex.getMessage() +
                    " | Failed Transaction Items: " + txItems, ex);
            state = State.EXCEPTION;
            throw ex;
        }
        tLogger.log(this.getClass(), TLogLevel.FAILED, "Job: " + jobId +
                " | Status: Total Failure" +
                " | Reason: All iteration failed.");
        state = State.FAILED;
        return new Pair<>(true, txItems);
    }

    private Pair<SpendBundle, List<Coin>> getAssetBundle() throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle");
        long totalAmount = txItems.stream().mapToLong(i -> i.addition().amount()).sum();
        SpendBundle spendBundle = null;

        JsonNode coinReq = new RequestUtils.SpendableCoinBuilder()
                .setWalletId(config.fundWalletId)
                .setExcludedCoins(excludedCoins)
                .build();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle:getSpendableCoins");

        List<Coin> spendableCoins = walletAPI.getSpendableCoins(coinReq)
                .data().orElseThrow(dataExcept("WalletAPI.getSpendableCoins"))
                .confirmedRecords()
                .stream().map(CoinRecord::coin)
                .sorted(Comparator.comparingLong(Coin::amount).reversed())
                .toList();

        List<Coin> txCoins = new ArrayList<>();
        for (Coin coin : spendableCoins) {
            if (totalAmount > 0) {
                txCoins.add(coin);
                totalAmount -= coin.amount();
            } else { break; }
        }

        parentCoins = txCoins;
        excludedCoins.addAll(txCoins);

        JsonNode xchSpendRequest = new RequestUtils.SignedTransactionBuilder()
                .setWalletId(config.fundWalletId)
                .addAdditions(txItems.stream().map(TransactionItem::addition).toList())
                .addCoin(txCoins)
                .build();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: getAssetBundle:createSignedTransaction");

        spendBundle = walletAPI.createSignedTransaction(xchSpendRequest)
                .data().orElseThrow(dataExcept("WalletAPI.createSignedTransaction")).spendBundle();
        return new Pair<>(spendBundle, txCoins);
    }

    private List<TransactionItem> getReturn(List<Coin> coins) {
        List<TransactionItem> rtnList = new ArrayList<>(txItems.size());
        for (int i = 0; i < txItems.size(); ++i) {
            rtnList.add(txItems.get(i).withCoin(coins.get(i)));
        }
        return rtnList;
    }
}
