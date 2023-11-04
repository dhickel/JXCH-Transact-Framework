package io.mindspice.jxch.transact.service.mint;

import com.fasterxml.jackson.databind.JsonNode;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.ApiResponse;
import io.mindspice.jxch.rpc.schemas.custom.NftBundle;
import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.rpc.schemas.object.SpendBundle;
import io.mindspice.jxch.rpc.schemas.wallet.nft.MetaData;
import io.mindspice.jxch.rpc.util.ChiaUtils;
import io.mindspice.jxch.rpc.util.RPCException;
import io.mindspice.jxch.rpc.util.RequestUtils;
import io.mindspice.jxch.rpc.util.bech32.AddressUtil;
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


public class MintJob extends TJob implements Callable<Pair<Boolean, List<MintItem>>> {
    private final List<MintItem> mintItems;

    public MintJob(JobConfig config, TLogger tLogger, FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(config, tLogger, nodeAPI, walletAPI);
        mintItems = new CopyOnWriteArrayList<>();
    }

    public void addMintItem(List<MintItem> mintItems) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        this.mintItems.addAll(mintItems);
    }

    public void addMintItem(MintItem mintItem) {
        if (state != State.INIT) { throw new IllegalStateException("Cannot add items after starting."); }
        this.mintItems.add(mintItem);
    }

    @Override
    public Pair<Boolean, List<MintItem>> call() throws Exception {
        List<String> mintIds = mintItems.stream().map(MintItem::uuid).toList();
        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId +
                " | Started Mint Job for NFT UUIDs: " + mintIds);
        startHeight = nodeAPI.getHeight().data().orElseThrow(dataExcept("NodeAPI.getHeight"));

        try {
            Pair<NftBundle, Coin> mintData;
            try {
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Acquiring excluded coins semaphore");
                ExcludedCoinRepo.getSemaphore().acquire();
                mintData = getMintBundle();
                excludedCoins.add(mintData.second());
            } finally {
                ExcludedCoinRepo.getSemaphore().release();
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Released excluded coins semaphore");
            }

            List<String> nftList = mintData.first().nftIdList();
            SpendBundle nftSpendBundle = mintData.first().spendBundle();
            Coin mintCoin = mintData.second();

            long bundleCost = getSpendCost(nftSpendBundle);
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
                ExcludedCoinRepo.getSemaphore().release();
                tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId
                        + " | Released excluded coins semaphore");
            }

            tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId + " | Fee coin selected: "
                    + ChiaUtils.getCoinId(feeCoin));

            SpendBundle aggBundle;
            if (feeAmount != 0) {
                SpendBundle feeBundle = getFeeBundle(feeCoin, feeAmount);
                aggBundle = walletAPI.aggregateSpends(List.of(nftSpendBundle, feeBundle))
                        .data().orElseThrow(dataExcept("WalletAPI.aggregateSpends"));
            } else {
                aggBundle = nftSpendBundle;
            }

            state = State.STARTED;

            tState = new TransactionState(
                    mintItems.stream().map(MintItem::uuid).toList(),
                    bundleCost,
                    feePerCost,
                    feeAmount,
                    feeCoin,
                    nftSpendBundle,
                    aggBundle,
                    List.of(mintCoin)
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
            return new Pair<>(success, success ? getReturn(nftList) : mintItems);

        } catch (Exception ex) {
            tLogger.log(this.getClass(), TLogLevel.FAILED, "Job: " + jobId +
                    " | Exception: " + ex.getMessage() +
                    " | Failed UUIDs: " + mintIds, ex);
            state = State.EXCEPTION;
            if (tState != null) {
                excludedCoins.remove(tState.feeCoin);
                tState.jobCoins.forEach(excludedCoins::remove);
            }
            throw ex;
        }
    }

    private Pair<NftBundle, Coin> getMintBundle() throws Exception {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingMintBundle");
        var metaData = new ArrayList<MetaData>();
        var targets = new ArrayList<String>();
        var total = 0;

        for (var item : mintItems) {
            metaData.add(item.metaData());
            String targetAddress;
            if (item.targetAddress().substring(0, 3).contains("xch")) {
                targetAddress = item.targetAddress();
            } else {
                targetAddress = AddressUtil.encode(config.isTestnet ? "txch" : "xch", item.targetAddress());
            }
            targets.add(targetAddress);
            total++;
        }

        Coin mintCoin = getFundingCoin(total);
        tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId + " | Funding coin selected: "
                + ChiaUtils.getCoinId(mintCoin));
        RequestUtils.BulkMintBuilder bulkMintbuilder = new RequestUtils.BulkMintBuilder()
                .setMintTotal(total)
                .addTargetAddress(targets)
                .addMetaData(metaData)
                .addXchCoin(mintCoin.puzzleHash())
                .setReusePuzHash(true)
                .setChangeTarget(config.changeTarget)
                .setWalletId(config.mintWalletId);
        if (config.mintFromDid) {
            bulkMintbuilder.mintFromDid(true);
            Coin didCoin = getDidCoin();
            tLogger.log(this.getClass(), TLogLevel.INFO, "Job: " + jobId + " | Did coin selected: "
                    + ChiaUtils.getCoinId(didCoin));
            bulkMintbuilder.addDidCoin(didCoin);
        }
        if (config.royaltyTarget != null && !config.royaltyTarget.isEmpty()) {
            bulkMintbuilder.setRoyaltyAddress(config.royaltyTarget);
            bulkMintbuilder.setRoyaltyPercentage(config.royaltyPercentage);
        }

        JsonNode bulkMintReq = bulkMintbuilder.build();
        ApiResponse<NftBundle> nftBundle = walletAPI.nftMintBulk(bulkMintReq);

        if (!nftBundle.success()) {
            throw new IllegalStateException("Failed To Get Spend Bundle Via RPC: " + nftBundle.error());
        }
        return new Pair<>(nftBundle.data().orElseThrow(dataExcept("WalletAPI.nftBulkMint")), mintCoin);
    }

    private Coin getFundingCoin(int amount) throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingFundingCoin");
        var jsonNode = new RequestUtils.SpendableCoinBuilder()
                .setMinCoinAmount(amount)
                .setWalletId(config.fundWalletId)
                .build();

        return walletAPI.getSpendableCoins(jsonNode)
                .data()
                .orElseThrow(dataExcept("WalletAPI.getSpendableCoins"))
                .confirmedRecords()
                .stream().sorted(Comparator.comparing(c -> c.coin().amount()))
                .toList()
                .get(0).coin();
    }

    private Coin getDidCoin() throws RPCException {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GetDIDCoin:didGetDID");

        String didCoinId = walletAPI.didGetDID(config.didWalletId)
                .data()
                .orElseThrow(dataExcept("WalletAPI.didGetDID"))
                .coinId();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingDIDCoin:didGetInfo");

        var currDidCoin = walletAPI.didGetInfo(didCoinId).data()
                .orElseThrow(dataExcept("WalletAPI.didGetInfo")).latestCoin();

        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Job: " + jobId +
                " | Action: GettingDIDCoin:getCoinRecordsByName");
        var coinReq = nodeAPI.getCoinRecordByName(currDidCoin);
        return coinReq.data().orElseThrow(dataExcept("WalletAPI.getCoinRecordsByName")).coin();
    }

    private List<MintItem> getReturn(List<String> nftIds) {
        List<MintItem> rtnList = new ArrayList<>(mintItems.size());
        for (int i = 0; i < mintItems.size(); ++i) {
            rtnList.add(mintItems.get(i).withNftId(nftIds.get(i)));
        }
        return rtnList;
    }
}
