package io.mindspice.jxch.transact.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.MempoolItem;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.util.ChiaUtils;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;


public abstract class TJob {

    public enum State {
        INIT,
        AWAITING_SYNC,
        AWAITING_CONFIRMATION,
        RETRYING,
        STARTED,
        EXCEPTION,
        SUCCESS,
        FAILED
    }


    protected final JobConfig config;
    protected final TLogger tLogger;
    protected final WalletAPI walletAPI;
    protected final FullNodeAPI nodeAPI;
    protected final String jobId = UUID.randomUUID().toString();
    protected final Set<Coin> excludedCoins = ExcludedCoinRepo.getSharedExcluded();
    protected volatile int startHeight;
    protected volatile State state = State.INIT;
    protected TransactionState tState;

    public static Supplier<RPCException> dataExcept(String msg) {
        return () -> new RPCException("Required RPC call: " + msg + " returned Optional.empty");
    }

    public TJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        this.config = config;
        this.tLogger = tLogger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public State getState() {
        return state;
    }

    public Set<Coin> getExcludedCoins() {
        return excludedCoins;
    }

    public String getJobId() {
        return jobId;
    }

    // Main loop, will keep trying until a successful mint, or until max reties are hit,
    //  recalculating the fee every iteration incrementing additionally as per config
    public boolean transactionLoop(TransactionState tState) throws Exception {
        this.tState = tState;
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: Starting Mint: " +
                " | BundleCost: " + tState.bundleCost +
                " | FeePerCost: " + tState.feePerCost +
                " | TotalCost: " + tState.feeAmount);

        for (int i = 0; i < config.maxRetries; ++i) {
            tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                    " | Action: LoopIteration: " + i);

            // Spin until sync
            while (!walletAPI.getSyncStatus().data().orElseThrow(dataExcept("WalletAPI.getSyncStatus")).synced()) {
                state = State.AWAITING_SYNC;
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Failed iteration: " + i + "/" + config.maxRetries +
                        " | Reason: Wallet  not Synced" +
                        " | Retrying in " + config.retryWaitInterval + "ms");
                Thread.sleep(config.retryWaitInterval);
            }
            state = i == 0 ? State.STARTED : State.RETRYING;

            if (i != 0 && tState.feePerCost < config.maxFeePerCost
                    && (i % config.feeIncInterval == 0 || tState.needReplaceFee)) {

                if (tState.needReplaceFee) {
                    tState.feePerCost = Math.min(tState.feePerCost + 5, config.maxFeePerCost);
                    tState.feeAmount = tState.bundleCost * tState.feePerCost;
                    tState.needReplaceFee = false;
                } else {
                    long currFeePerCost = getFeePerCostNeeded(tState.bundleCost);
                    long baseFpc = Math.max(Math.max(currFeePerCost, 5), config.minFeePerCost);
                    long incValue = (i / config.feeIncInterval);
                    long incFpc = baseFpc + incValue;
                    tState.feePerCost = Math.min(incFpc, config.maxFeePerCost);
                    tState.feeAmount = tState.feePerCost * tState.bundleCost;
                }

                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: FeeReCalc" +
                        " | FeePerCost: " + tState.feePerCost +
                        " | TotalFee: " + tState.feeAmount);

                if (tState.feeAmount != 0) {
                    SpendBundle feeBundle = getFeeBundle(tState.feeCoin, tState.feeAmount);
                    tState.aggBundle = walletAPI.aggregateSpends(List.of(tState.transactionBundle, feeBundle))
                            .data().orElseThrow(dataExcept("WalletAPI.aggregateSpends"));
                }
            }

            tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                    " | Action: PushingTransaction");
            var pushResponse = nodeAPI.pushTx(tState.aggBundle);

            if (!pushResponse.success()) {
                // Consider transaction a success if the coin id related to it is spent this means the transaction
                // submission from a past iteration was successful and not recognized due to network delay or the coin
                // was spent elsewhere as the result of user error.
                if ((pushResponse.error().contains("DOUBLE_SPEND"))) {
                    if (i == 0) {
                        tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                                " | Job: " + jobId + " Failed (DOUBLE_SPEND) on first iteration." +
                                " | Note:  Double spend can be due to a past successful transaction being " +
                                "re-submitted, but this would never occur on a first iteration" +
                                " | Fee: " + tState.feeAmount +
                                " | Item UUIDs: " + tState.itemIds);
                        throw new IllegalStateException("Double spend on first iteration");
                    }
                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Performed a DOUBLE_SPEND, job consider successful. This error can be ignored, " +
                            "but could result in a failed job if the coin was spent elsewhere due to user error.");

                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Job: " + jobId + " Successful (DOUBLE_SPEND)" +
                            " | Fee: " + tState.feeAmount +
                            " | Item UUIDs: " + tState.itemIds);
                    state = State.SUCCESS;

                    return true;
                } else if (pushResponse.error().contains("INVALID_FEE_TOO_CLOSE_TO_ZERO")) {
                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Failed iteration: " + i + "/" + config.maxRetries +
                            " | Reason: INVALID_FEE_TOO_CLOSE_TO_ZERO " +
                            " | Current Fee Per Cost: " + tState.feePerCost +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                    Thread.sleep(config.retryWaitInterval);
                    continue;
                }
                tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                        " | Failed iteration: " + i + "/" + config.maxRetries +
                        " | Reason: Unknown error on push " +
                        " | Error:" + pushResponse.error() +
                        " | Current Fee Per Cost: " + tState.feePerCost +
                        " | Retrying in " + config.retryWaitInterval + "ms");
            }

            String bundleName = pushResponse.data().orElseThrow(dataExcept("pushResponse")).spendBundleName();

            tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                    " | Spendbundle Name: " + bundleName);

            boolean txFound = checkMempoolForTx(bundleName);

            int waitReps = 0;
            while (waitReps < 10 && !txFound) {
                Thread.sleep(5000);
                waitReps++;
                txFound = checkMempoolForTx(bundleName);
                tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                        " | Transaction State: Awaiting mempool detection" +
                        " | Wait Iteration: " + waitReps +
                        " | TransactionId: " + bundleName +
                        " | Note: This can happen occasionally but if occurring often may be an issue " +
                        "with your node and/or node resources");
            }

            if (txFound) {
                state = State.AWAITING_CONFIRMATION;
                tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                        " | Transaction State: In Mempool" +
                        " | Transaction Id: " + bundleName);

                boolean completed = waitForTxConfirmation(bundleName, tState.jobCoins.get(0));
                if (completed) {
                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Transaction State: Successful" +
                            " | Transaction Id: " + bundleName +
                            " | Fee: " + tState.feeAmount +
                            " | Item UUIDs: " + tState.itemIds);
                    state = State.SUCCESS;
                    return true;
                } else {
                    tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                            " | Transaction State: Failed" +
                            " | Transaction Id: " + bundleName +
                            " | Iteration: " + i + "/" + config.maxRetries +
                            " | Reason: Tx dropped from mempool or confirm wait wait meet" +
                            " | Current Fee Per Cost: " + tState.feePerCost +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                }
            } else {
                tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                        " | Transaction State: Failed to locate tx in mempool" +
                        " | Iteration: " + i + "/" + config.maxRetries +
                        " | Current Fee Per Cost: " + tState.feePerCost +
                        " | Retrying in " + config.retryWaitInterval + "ms");
            }
            state = State.RETRYING;
            Thread.sleep(config.retryWaitInterval);
        }
        return false;
    }

    protected boolean checkMempoolForTx(String sbHash) throws Exception {

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: checkingMempoolForTransaction");
        return nodeAPI.getAllMempoolItems().data()
                .orElseThrow(dataExcept("NodeAPI.getAllMempoolItems"))
                .entrySet().stream()
                .anyMatch(e -> e.getValue().spendBundleName().equals(sbHash));
    }

    protected SpendBundle getFeeBundle(Coin feeCoin, long feeAmount) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeeBundle");
        JsonNode feeBundleReq = new RequestUtils.SignedTransactionBuilder()
                .addAddition(config.changeTarget, feeCoin.amount() - feeAmount) // return amount not used for fee
                .addCoin(feeCoin)
                .addFee(feeAmount)
                .build();

        return walletAPI.createSignedTransaction(feeBundleReq).data().
                orElseThrow(dataExcept("WalletAPI.createSignedTransaction")).spendBundle();
    }

    protected long getSpendCost(SpendBundle spend) throws Exception {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingSpendBundleCost");
        return nodeAPI.getSpendBundleInclusionCost(spend).data()
                .orElseThrow(dataExcept("NodeApi.getSpendBundleInclusionCost")).cost();
    }

    protected long getFeePerCostNeeded(long cost) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeePerCostNeeded");
        Map<String, MempoolItem> mempool =
                nodeAPI.getAllMempoolItems().data().orElseThrow(dataExcept("NodeAPI.getAllMempoolItems"));

        long totalMemCost = mempool.values().stream()
                .mapToLong(MempoolItem::cost)
                .sum();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | totalMemCost: " + totalMemCost);

        long feeNeeded = 0;
        if (totalMemCost + (cost * 1.05) > config.maxMemPoolCost) { // add a 5% buffer for bundle
            var sortedMempool = mempool.values().stream()
                    .filter(i -> i.fee() > 0)
                    .sorted(Comparator.comparing(MempoolItem::fee))
                    .toList();

            var memSum = 0L;
            for (MempoolItem item : sortedMempool) {
                if (memSum < (cost * 1.05)) { // 5% extra  for a buffer
                    memSum += item.cost();
                    if (item.cost() > 0) {
                        feeNeeded = item.fee() / item.cost();
                    }
                }
            }
        }

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | TotalMemPoolCost: " + totalMemCost +
                " | FeeNeeded:" + feeNeeded);
        return feeNeeded;
    }

    protected Coin getFeeCoin(long amount, List<Coin> excludedCoins) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: gettingFeeCoin");

        var jsonNode = new RequestUtils.SpendableCoinBuilder()
                .setMinCoinAmount(amount)
                .setExcludedCoins(excludedCoins)
                .setWalletId(config.feeWalletId)
                .build();

        return walletAPI.getSpendableCoins(jsonNode)
                .data()
                .orElseThrow(dataExcept("WalletApi.getSpendableCoins"))
                .confirmedRecords()
                .stream().sorted(Comparator.comparing(c -> c.coin().amount()))
                .toList()
                .get(0).coin();
    }

    protected boolean waitForTxConfirmation(String txId, Coin txParentCoin) throws Exception {
        long waitStartTime = Instant.now().getEpochSecond();
        while (true) {
            if (config.maxConfirmWait > 0) {
                long nowTime = Instant.now().getEpochSecond();
                if (nowTime - waitStartTime > config.maxConfirmWait) {

                    if (tState.feePerCost != config.maxFeePerCost) {
                        tState.needReplaceFee = true;
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | Re-submitting due to max confirm wait(" + config.maxConfirmWait + "s)");
                        return false;
                    }
                }
            }
            tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                    " | Action: waitForConfirmation");
            Thread.sleep(30000);
            /* getTxStatus returns true if tx is no longer in the mempool
             Once we know it's not in the mempool, it needs to be confirmed
             the actual coin has been spent to confirm transaction as successful */
            ;
            if (!checkMempoolForTx(txId)) {
                Thread.sleep(10000); // Give the node a little wait time to update to be safe
                var mintCoinRecord = nodeAPI.getCoinRecordByName(ChiaUtils.getCoinId(txParentCoin));
                return mintCoinRecord.data().orElseThrow(dataExcept("NodeAPi.getCoinRecordsByName")).spent();
            }
        }
    }
}
