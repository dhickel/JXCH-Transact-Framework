package io.mindspice.jxch.transact.service.mint;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.util.Util;
import io.mindspice.jxch.transact.service.TService;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;


public abstract class MintService extends TService<MintItem> implements Runnable {

    protected volatile Future<MintReturn> currentJob;

    public MintService(ScheduledExecutorService scheduledExecutor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(scheduledExecutor, config, tLogger, nodeAPI, walletAPI);
    }

    public void start() {
        stopped = false;
        taskRef = executor.scheduleAtFixedRate(
                this,
                10,
                config.queueCheckInterval,
                TimeUnit.SECONDS
        );
        lastTime = Instant.now().getEpochSecond();
    }

    @Override
    public boolean stopAndBlock() {
        stopped = true;
        while (!queue.isEmpty()) {
            if (currentJob != null) {
                try {
                    currentJob.get();  // This will block until the current job is finished
                    return true;
                } catch (InterruptedException | ExecutionException e) {
                    return false;
                }
            }
        }
        return true;
    }

    // Override to handle what to do with failed mints
    protected abstract void onFail(List<MintItem> mintItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(Map<MintItem, String> itemNftIdMap);

    public void run() {

        try {
            if (queue.isEmpty()) {
                if (stopped) { terminate(); } else { return; }
            }
            tLogger.log(this.getClass(), TLogLevel.DEBUG, "Checking Queue");

            long nowTime = Instant.now().getEpochSecond();
            if (queue.size() >= config.jobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
                lastTime = nowTime;

                MintJob mintJob = new MintJob(config, tLogger, nodeAPI, walletAPI);
                List<MintItem> mintItems = IntStream.range(0, Math.min(config.jobSize, queue.size()))
                        .mapToObj(i -> queue.poll())
                        .filter(Objects::nonNull).toList();

                mintJob.addMintItem(mintItems);
                try {
                    currentJob = executor.submit(mintJob);
                    MintReturn mintResult = currentJob.get();
                    if (mintResult.success()) {
                        onFinish(Util.mapMints(mintResult.mintItems(), mintResult.nftIds()));
                    } else {
                        onFail(mintResult.mintItems());
                    }
                } catch (Exception ex) {
                    tLogger.log(this.getClass(), TLogLevel.ERROR,
                            "MintJob: " + mintJob.getJobId() + " Failed" +
                                    " | Exception: " + ex.getMessage(), ex);
                    onFail(mintItems);
                }
            }
        } catch (Exception e) {
            tLogger.log(this.getClass(), TLogLevel.ERROR, "Exception running service task", e);
        }
    }
}


