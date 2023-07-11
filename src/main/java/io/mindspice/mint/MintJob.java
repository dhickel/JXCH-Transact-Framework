package io.mindspice.mint;

import com.fasterxml.jackson.databind.JsonNode;
import io.mindspice.http.FullNodeAPI;
import io.mindspice.http.WalletAPI;
import io.mindspice.logging.LogLevel;
import io.mindspice.logging.MintLogger;
import io.mindspice.schemas.ApiResponse;
import io.mindspice.schemas.custom.NftBundle;
import io.mindspice.schemas.object.Coin;
import io.mindspice.schemas.object.CoinRecord;
import io.mindspice.schemas.object.MempoolItem;
import io.mindspice.schemas.object.SpendBundle;
import io.mindspice.schemas.wallet.nft.MetaData;
import io.mindspice.settings.MintConfig;
import io.mindspice.util.ChiaUtils;
import io.mindspice.util.Pair;
import io.mindspice.util.RPCException;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;


public class MintJob implements Callable<Pair<Boolean, List<String>>> {
    private final WalletAPI walletAPI;
    private final FullNodeAPI nodeAPI;
    private final MintConfig config;
    private final List<MintItem> mintItems = new ArrayList<>();
    private final MintLogger logger;
    private final String jobId = UUID.randomUUID().toString();

    private final Supplier<RPCException> dataExcept =
            () -> new RPCException("Required RPC call returned Optional.empty");

    public MintJob(MintConfig config, MintLogger logger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        this.config = config;
        this.logger = logger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public void addMintItem(List<MintItem> mintItems) {
        this.mintItems.addAll(mintItems);
    }

    public void addMintItem(MintItem mintItem) {
        this.mintItems.add(mintItem);
    }

    @Override
    public Pair<Boolean, List<String>> call() throws Exception {
        List<String> mintIds = mintItems.stream().map(MintItem::uuid).toList();

        try {
            System.out.println("starting mint");
            logger.log(this.getClass(), LogLevel.MINT_INFO, "MintJob: " + jobId +
                    " | Started Mint for NFT UUIDs: " + mintIds);

            Pair<NftBundle, Coin> mintData = getSpendBundle();
            List<String> nftList = mintData.first().nftIdList();
            SpendBundle nftSpendBundle = mintData.first().spendBundle();
            Coin mintCoin = mintData.second();

            long bundleCost = getSpendCost(nftSpendBundle);
            long feePerCost = Math.min(getFeePerCostNeeded(bundleCost), config.maxFeePerCost);
            long feeAmount = feePerCost * bundleCost;

            // Get max so coin can be reused for all fee calculations
            Coin feeCoin = getFeeCoin(config.maxFeePerCost * bundleCost, mintCoin);
            SpendBundle feeBundle = getFeeBundle(feeCoin, feeAmount);

            var aggBundle = BLSUtil.aggregateFeeBundle(nftSpendBundle, feeBundle.spend_bundle);

            /* Main loop, will keep trying until a successful mint, or until max reties are hit,
               recalculating the fee every iteration incrementing additionally as per config */

            boolean incFee = false;
            for (int i = 0; i < config.maxRetries; ++i) {
                System.out.println("top of loop");
                System.out.println("fee per cost:" + feePerCost);
                System.out.println("Using fee " + feePerCost * bundleCost);

                if (i != 0 && !(feePerCost >= config.maxFeePerCost)) {
                    System.out.println("fee Re calc");

                    Long currFeePerCost = getFeePerCostNeeded(bundleCost);
                    if (currFeePerCost > feePerCost) {
                        if (i % config.feeIncInterval == 0 || incFee) {
                            feeAmount = currFeePerCost + (5L * (long) (i / config.feeIncInterval)) * bundleCost;
                            incFee = false;
                        } else {
                            feeAmount = currFeePerCost * bundleCost;
                        }
                    }

                    System.out.println(currFeePerCost);
                    feeBundle = getFeeBundle(feeCoin, feeAmount);
                    aggBundle = BLSUtil.aggregateFeeBundle(nftSpendBundle, feeBundle);
                }

                var pushResult = nodeAPI.pushTx(aggBundle);

                if (!pushResult.success()) {
                    // Consider mint a success if the coin id related to it is spent this means the mint submission
                    // from a past iteration was successful and not recognized due to network delay or the coin was spent
                    // elsewhere as the result of user error.
                    if ((pushResult.error().contains("DOUBLE_SPEND"))) {
                        logger.log(this.getClass(), LogLevel.MINT_ERROR, "MintJob: " + jobId +
                                " | Performed a DOUBLE_SPEND, mint consider successful. This error can be ignored, " +
                                "but could result in a failed mint if the coin was spent elsewhere.");

                        logger.log(this.getClass(), LogLevel.MINT_INFO, "MintJob: " + jobId +
                                " | MintJob: " + jobId + " Successful (DOUBLE_SPEND)" +
                                " | Fee: " + feeAmount +
                                " | Minted UUIDs: " + mintIds);

                        return new Pair<>(true, nftList);
                    } else if (pushResult.error().contains("INVALID_FEE_TOO_CLOSE_TO_ZERO")) {
                        logger.log(this.getClass(), LogLevel.MINT_ERROR, "MintJob: " + jobId +
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
                        checkMempoolForTx(pushResult.data().orElseThrow(dataExcept).spendBundleName());

                if (txResponse.first()) {
                    boolean completed = waitForTxConfirmation(txResponse.second(), mintCoin);
                    if (completed) {
                        logger.log(this.getClass(), LogLevel.MINT_INFO, "MintJob: " + jobId +
                                " | MintJob: " + jobId + " Successful (DOUBLE_SPEND)" +
                                " | Fee: " + feeAmount +
                                " | Minted UUIDs: " + mintIds);
                        return new Pair<>(true, nftList);
                    } else {
                        incFee = config.incFeeOnFail;
                        logger.log(this.getClass(), LogLevel.MINT_ERROR, "MintJob: " + jobId +
                                " | Failed iteration: " + i + "/" + config.maxRetries +
                                " | Reason: INVALID_FEE_TOO_CLOSE_TO_ZERO " +
                                " | Current Fee Per Cost: " + feePerCost +
                                " | Retrying in " + config.retryWaitInterval + "ms");
                    }
                } else {
                    logger.log(this.getClass(), LogLevel.MINT_ERROR, "MintJob: " + jobId +
                            " | Failed iteration: " + i + "/" + config.maxRetries +
                            " | Reason: Failed to locate tx in mempool " +
                            " | Current Fee Per Cost: " + feePerCost +
                            " | Retrying in " + config.retryWaitInterval + "ms");
                    Thread.sleep(config.retryWaitInterval);
                }
            }
        } catch (Exception e) {
            logger.log(this.getClass(), LogLevel.MINT_FATAL, "MintJob: " + jobId +
                    " | Exception: " + e.getMessage() +
                    " | Failed UUIDs: " + mintIds);
            throw e;
        }
        return new Pair<>(false, List.of());
    }

    public String getJobId() {
        return jobId;
    }

    private Pair<NftBundle, Coin> getSpendBundle() throws Exception {
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

        JsonNode bulkMintReq = new ChiaUtils.BulkMintBuilder()
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

    private Pair<Boolean, String> checkMempoolForTx(String sbHash) throws Exception {
        Optional<String> txHash = nodeAPI.getAllMempoolItems().data().orElseThrow(dataExcept)
                .entrySet().stream()
                .filter(e -> e.getValue().spendBundleName().equals(sbHash))
                .map(Map.Entry::getKey)
                .findFirst();
        return txHash.isPresent() ? new Pair<>(true, sbHash) : new Pair<>(false, "");
    }

    private boolean waitForTxConfirmation(String txId, Coin mintCoin) throws Exception {
        while (true) {
            System.out.println("waiting for mint");
            Thread.sleep(20000); // Todo maybe only recheck when height increases?
            /* getTxStatus returns true if tx is no longer in the mempool
             Once we know it's not in the mempool, it needs to be confirmed
             the actual coin has been spent to confirm mint as successful */
            if (txClearedMempool(txId)) {
                ApiResponse<List<CoinRecord>> recordReq = nodeAPI.getCoinRecordsByParentIds(
                        List.of(mintCoin.parentCoinInfo()),
                        0, //TODO, block height should be records at start to optimize
                        Integer.MAX_VALUE,
                        true
                );

                return recordReq.data().orElseThrow(dataExcept)
                        .stream()
                        .filter(CoinRecord::spent)
                        .anyMatch(cr -> cr.coin().amount() == mintCoin.amount());
            }
        }
    }

    private SpendBundle getFeeBundle(Coin feeCoin, long feeAmount) throws RPCException {
        JsonNode feeBundleReq = new ChiaUtils.SignedTransactionBuilder()
                .addAddition(config.mintChangeTarget, feeCoin.amount() - feeAmount) // return amount not used for fee
                .addCoin(feeCoin)
                .addFee(feeAmount)
                .build();

        return walletAPI.createSignedTransaction(feeBundleReq).data().orElseThrow(dataExcept).spendBundle();
    }

    private long getSpendCost(SpendBundle spend) throws Exception {
        return nodeAPI.getSpendBundleInclusionCost(spend).data().orElseThrow(dataExcept).cost();
    }

    private long getFeePerCostNeeded(long cost) throws RPCException {
        Map<String, MempoolItem> mempool =
                nodeAPI.getAllMempoolItems().data().orElseThrow(dataExcept);

        long totalMemCost = mempool.values().stream()
                .mapToLong(MempoolItem::cost)
                .sum();

        System.out.println("total_mem_cost: " + totalMemCost);

        var sortedMempool = mempool.values().stream()
                .filter(i -> i.fee() > 0)
                .sorted(Comparator.comparing(MempoolItem::fee))
                .toList();

        System.out.println("sorted mempool size: " + sortedMempool.size());
        // FIXME I think this is bugged, copied form old code, investigate.
        var memSum = 0L;
        var feeNeeded = 0L;
        for (MempoolItem item : sortedMempool) {
            if (memSum < (cost * 1.05)) { // 5% extra  for a buffer
                memSum += item.cost();
                System.out.println(item.fee());
                if (item.cost() > 0) {
                    feeNeeded = item.fee() / item.cost();
                }
            }
        }

        System.out.println("fee Needed:" + feeNeeded);

        if (feeNeeded < 5) {
            return config.minFeePerCost;
        } else {
            return roundFpc(feeNeeded);
        }
    }

    //todo test if there is an actual data object still returned when unfound
    private boolean txClearedMempool(String txId) throws RPCException {
        return nodeAPI.getMempoolItemByTxId(txId)
                .data()
                .isEmpty();
    }

    private Coin getFundingCoin(int amount) throws RPCException {
        var jsonNode = new ChiaUtils.SpendableCoinBuilder()
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

    private Coin getFeeCoin(long amount, Coin excludedCoin) throws RPCException {
        var jsonNode = new ChiaUtils.SpendableCoinBuilder()
                .setMinCoinAmount(amount)
                .setExcludedCoins(List.of(excludedCoin))
                .setWalletId(config.feeWalletId)
                .build();

        return walletAPI.getSpendableCoins(jsonNode)
                .data()
                .orElseThrow(dataExcept)
                .confirmedRecords()
                .stream().sorted().toList()
                .get(0).coin();
    }

    private Coin getDidCoin() throws RPCException {
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

    public static long roundFpc(long i) {
        var round = (i / 5) * 5;
        return (i - round > 2) ? round + 5 : round;
    }

}
