package io.mindspice.jxch.transact.jobs.mint;

import com.fasterxml.jackson.databind.JsonNode;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.ApiResponse;
import io.mindspice.jxch.rpc.schemas.custom.NftBundle;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.schemas.wallet.nft.MetaData;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.transact.jobs.Job;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;

import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.mindlib.data.Pair;

import java.util.*;
import java.util.concurrent.Callable;


public class MintJob extends Job implements Callable<Pair<Boolean, List<String>>> {
    private final List<MintItem> mintItems;

    public MintJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(config, tLogger, nodeAPI, walletAPI);
        mintItems = new ArrayList<>(config.jobSize);
    }

    public void addMintItem(List<MintItem> mintItems) {
        this.mintItems.addAll(mintItems);
    }

    public void addMintItem(MintItem mintItem) {
        this.mintItems.add(mintItem);
    }

    // TODO return the mint items instead of just ids. The items have the ids, and returning the items
    //  allows for simpler processing of failed mints so they can be readded to queue without holding
    //  a reference in MintService
    @Override
    public Pair<Boolean, List<String>> call() throws Exception {
        List<String> mintIds = mintItems.stream().map(MintItem::uuid).toList();
        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                " | Started Mint Job for NFT UUIDs: " + mintIds);
        startHeight = nodeAPI.getBlockChainState().data().orElseThrow(dataExcept).peak().height();

        try {

            Pair<NftBundle, Coin> mintData = getMintBundle();
            excludedCoins.add(mintData.second());
            List<String> nftList = mintData.first().nftIdList();
            SpendBundle nftSpendBundle = mintData.first().spendBundle();
            Coin mintCoin = mintData.second();

            long bundleCost = getSpendCost(nftSpendBundle);
            long feePerCost = Math.min(getFeePerCostNeeded(bundleCost), config.maxFeePerCost);
            long feeAmount = feePerCost * bundleCost;

            // Get max so coin can be reused for all fee calculations
            Coin feeCoin = getFeeCoin(bundleCost * config.maxFeePerCost, List.of(mintCoin));
            excludedCoins.add(feeCoin);
            SpendBundle feeBundle = getFeeBundle(feeCoin, feeAmount);

            SpendBundle aggBundle = walletAPI.aggregateSpends(List.of(nftSpendBundle, feeBundle))
                    .data().orElseThrow(dataExcept);


            /* Main loop, will keep trying until a successful mint, or until max reties are hit,
               recalculating the fee every iteration incrementing additionally as per config */

            boolean incFee = false;

            state = State.STARTED;
            for (int i = 0; i < config.maxRetries; ++i) {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: StartingMintIteration: " + i +
                        " | FeePerCost: " + feePerCost +
                        " | totalFee: " + feePerCost * bundleCost);

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
                    aggBundle = walletAPI.aggregateSpends(List.of(nftSpendBundle, feeBundle))
                            .data().orElseThrow(dataExcept);
                }

                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                        " | Action: PushingTransaction");
                var pushResponse = nodeAPI.pushTx(aggBundle);

                if (!pushResponse.success()) {
                    // Consider mint a success if the coin id related to it is spent this means the mint submission
                    // from a past iteration was successful and not recognized due to network delay or the coin was spent
                    // elsewhere as the result of user error.
                    if ((pushResponse.error().contains("DOUBLE_SPEND"))) {
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | Performed a DOUBLE_SPEND, mint consider successful. This error can be ignored, " +
                                "but could result in a failed mint if the coin was spent elsewhere.");

                        tLogger.log(this.getClass(), TLogLevel.INFO, "MintJob: " + jobId +
                                " | MintJob: " + jobId + " Successful (DOUBLE_SPEND)" +
                                " | Fee: " + feeAmount +
                                " | Minted UUIDs: " + mintIds);
                        state = State.SUCCESS;
                        return new Pair<>(true, nftList);
                    } else if (pushResponse.error().contains("INVALID_FEE_TOO_CLOSE_TO_ZERO")) {
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
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
                    boolean completed = waitForTxConfirmation(txResponse.second(), mintCoin);
                    if (completed) {
                        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                                " | MintJob: " + jobId + " Successful" +
                                " | Fee: " + feeAmount +
                                " | Minted UUIDs: " + mintIds);
                        state = State.SUCCESS;
                        return new Pair<>(true, nftList);
                    } else {
                        incFee = config.incFeeOnFail;
                        tLogger.log(this.getClass(), TLogLevel.ERROR, "Job: " + jobId +
                                " | Failed iteration: " + i + "/" + config.maxRetries +
                                " | Reason: Tx dropped from mempool without minting " +
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
            }
            state = State.RETRYING;
            Thread.sleep(config.retryWaitInterval);
        } catch (Exception ex) {
            tLogger.log(this.getClass(), TLogLevel.FATAL, "Job: " + jobId +
                    " | Exception: " + ex.getMessage() +
                    " | Failed UUIDs: " + mintIds, ex);
            state = State.EXCEPTION;
            throw ex;
        }
        tLogger.log(this.getClass(), TLogLevel.FATAL, "Job: " + jobId +
                " | Status: Total Failure" +
                " | Reason: All iteration failed.");
        state = State.FAILED;
        return new Pair<>(false, mintIds);
    }

    private Pair<NftBundle, Coin> getMintBundle() throws Exception {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingMintBundle");
        var metaData = new ArrayList<MetaData>();
        var targets = new ArrayList<String>();
        var total = 0;

        for (var item : mintItems) {
            metaData.add(item.metaData());
            targets.add(item.targetAddress());
            total++;
        }

        Coin mintCoin = getFundingCoin(total);
        Coin didCoin = getDidCoin();

        JsonNode bulkMintReq = new RequestUtils.BulkMintBuilder()
                .setMintTotal(total)
                .addTargetAddress(targets)
                .addMetaData(metaData)
                .addXchCoin(mintCoin.puzzleHash())
                .addDidCoin(didCoin)
                .setChangeTarget(config.mintChangeTarget)
                .setWalletId(config.mintWalletId)
                .build();

        ApiResponse<NftBundle> nftBundle = walletAPI.nftMintBulk(bulkMintReq);

        if (!nftBundle.success()) {
            throw new IllegalStateException("Failed To Get Spend Bundle Via RPC: " + nftBundle.error());
        }
        return new Pair<>(nftBundle.data().orElseThrow(dataExcept), mintCoin);
    }

    private Coin getFundingCoin(int amount) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingFundingCoin");
        var jsonNode = new RequestUtils.SpendableCoinBuilder()
                .setMinCoinAmount(amount)
                .setWalletId(config.fundWallet)
                .build();

        return walletAPI.getSpendableCoins(jsonNode)
                .data()
                .orElseThrow(dataExcept)
                .confirmedRecords()
                .stream().sorted()
                .toList()
                .get(0).coin();
    }

    private Coin getDidCoin() throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingDIDCoin");
        String coinId = walletAPI.didGetDID(config.didWalletId)
                .data()
                .orElseThrow(dataExcept)
                .coinId();

        var coinReq = walletAPI.getCoinRecordsByNames(
                List.of(coinId),
                0,
                Integer.MAX_VALUE,
                false
        );
        return coinReq.data().orElseThrow(dataExcept).get(0).coin();
    }
}
