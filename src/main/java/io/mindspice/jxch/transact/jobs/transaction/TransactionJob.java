package io.mindspice.jxch.transact.jobs.transaction;

import com.fasterxml.jackson.databind.JsonNode;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.CoinRecord;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.schemas.wallet.Addition;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.jobs.Job;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;

import io.mindspice.mindlib.data.Pair;

import java.util.*;
import java.util.concurrent.Callable;


public class TransactionJob extends Job implements Callable<Pair<Boolean, List<Addition>>> {
    private final List<Addition> txItems;
    private final boolean isCat;

    public TransactionJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI, boolean isCat) {
        super(config, tLogger, nodeAPI, walletAPI);
        this.isCat = isCat;
        txItems = new ArrayList<>(config.jobSize);
    }

    public void addAddition(Addition addition) {
        txItems.add(addition);
    }

    public void addAddition(List<Addition> additions) {
        txItems.addAll(additions);
    }

    public void addExcludedCoin(Coin excluded) {
        excludedCoins.add(excluded);
    }

    public void addExcludedCoins(List<Coin> excluded) {
        excludedCoins.addAll(excluded);
    }

    @Override
    public Pair<Boolean, List<Addition>> call() throws Exception {
        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                " | Started Transaction Job for Additions: " + txItems);
        startHeight = nodeAPI.getBlockChainState().data().orElseThrow(dataExcept).peak().height();

        boolean incFee = false;
        boolean reSpend = false;

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
                    .data().orElseThrow(dataExcept);

            state = State.STARTED;
            for (int i = 0; i < config.maxRetries; ++i) {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: transactionIteration: " + i);

                // Spin until sync
                while (!walletAPI.getSyncStatus().data().orElseThrow(dataExcept).synced()) {
                    state = State.AWAITING_SYNC;
                    tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                            " | Failed iteration: " + i + "/" + config.maxRetries +
                            " | Reason: Wallet " + config.mintWalletId + " not Synced" +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                    Thread.sleep(config.retryWaitInterval);
                }
                state = i == 0 ? State.STARTED : State.RETRYING;

                if (i != 0 && !(feePerCost >= config.maxFeePerCost)) {
                    System.out.println("fee Re calc");

                    long currFeePerCost = getFeePerCostNeeded(bundleCost);
                    if (currFeePerCost > feePerCost) {
                        if (i % config.feeIncInterval == 0 || incFee) {
                            feeAmount = currFeePerCost + (5L * (long) (i / config.feeIncInterval)) * bundleCost;
                            incFee = false;
                        } else {
                            feeAmount = currFeePerCost * bundleCost;
                        }
                    }
                    tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                            " | Action: FeeReCalc" +
                            " | FeePerCost: " + feePerCost +
                            " | totalFee: " + feePerCost * bundleCost);
                    feeBundle = getFeeBundle(feeCoin, feeAmount);
                    aggBundle = walletAPI.aggregateSpends(List.of(assetBundle, feeBundle))
                            .data().orElseThrow(dataExcept);
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
                        return new Pair<>(true, txItems);
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
                        checkMempoolForTx(pushResponse.data().orElseThrow(dataExcept).spendBundleName());

                if (txResponse.first()) {
                    state = State.AWAITING_CONFIRMATION;
                    boolean completed = waitForTxConfirmation(txResponse.second(), excludedCoins.get(0));
                    if (completed) {
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | TransactJob: " + jobId + " Successful" +
                                " | Fee: " + feeAmount +
                                " | Additions: " + txItems);
                        state = State.SUCCESS;
                        return new Pair<>(true, txItems);
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
                    Thread.sleep(config.retryWaitInterval);
                }
            }
        } catch (Exception ex) {
            tLogger.log(this.getClass(), TLogLevel.FATAL, "Job: " + jobId +
                    " | Exception: " + ex.getMessage() +
                    " | Failed Transaction Items: " + txItems, ex);
            state = State.EXCEPTION;
            throw ex;
        }
        tLogger.log(this.getClass(), TLogLevel.FATAL, "Job: " + jobId +
                " | Status: Total Failure" +
                " | Reason: All iteration failed.");
        state = State.FAILED;
        return new Pair<>(false, txItems);
    }

    private Pair<SpendBundle, List<Coin>> getAssetBundle() throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingAssetBundle");
        long totalAmount = txItems.stream().mapToLong(Addition::amount).sum();
        SpendBundle spendBundle;

        JsonNode coinReq = new RequestUtils.SpendableCoinBuilder()
                .setWalletId(config.assetWalletId)
                .build();

        List<Coin> spendableCoins = walletAPI.getSpendableCoins(coinReq)
                .data().orElseThrow(dataExcept)
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

        if (isCat) {
            JsonNode catSpendReq = new RequestUtils.CatSpendBuilder()
                    .setWalletId(config.assetWalletId)
                    .setCoins(txCoins)
                    .setAdditions(txItems)
                    .setReusePuzzleHash(true)
                    .build();

            spendBundle = walletAPI.catSpendBundleOnly(catSpendReq)
                    .data().orElseThrow(dataExcept);
        } else {
            JsonNode xchSpendRequest = new RequestUtils.SignedTransactionBuilder()
                    .setWalletId(config.assetWalletId)
                    .addAdditions(txItems)
                    .addCoin(txCoins)
                    .build();

            spendBundle = walletAPI.createSignedTransaction(xchSpendRequest)
                    .data().orElseThrow(dataExcept).spendBundle();
        }
        return new Pair<>(spendBundle, txCoins);
    }
}
