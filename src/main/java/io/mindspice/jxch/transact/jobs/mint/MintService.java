package io.mindspice.jxch.transact.jobs.mint;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.jobs.TService;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.mindlib.data.tuples.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;


public abstract class MintService extends TService<MintItem> implements Runnable {

    protected volatile Future<Pair<Boolean, List<String>>> currentJob;

    public MintService(ScheduledExecutorService scheduledExecutor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(scheduledExecutor, config, tLogger, nodeAPI, walletAPI);
    }

    @Override
    public void start() {
        taskRef = executor.scheduleAtFixedRate(
                this,
                0,
                config.queueCheckInterval,
                TimeUnit.SECONDS
        );
        stopped = false;
        lastTime = Instant.now().getEpochSecond();
    }

    @Override
    public boolean stopAndBlock() {
        stopped = true;
        if (currentJob != null) {
            try {
                currentJob.get();  // This will block until the current job is finished
                return true;
            } catch (InterruptedException | ExecutionException e) {
                return false;
            }
        }
        return true;
    }

    // Override to handle what to do with failed mints
    protected abstract void onFail(List<MintItem> mintItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(Pair<List<MintItem>, List<String>> itemsAndNftIds);

    public void run() {

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
                Pair<Boolean, List<String>> mintResult = currentJob.get();
                if (mintResult.first()) {
                    onFinish(new Pair<>(mintItems, mintResult.second()));
                } else {
                    onFail(mintItems);
                }
            } catch (Exception ex) {
                tLogger.log(this.getClass(), TLogLevel.ERROR,
                        "MintJob: " + mintJob.getJobId() + " Failed" +
                                " | Exception: " + ex.getMessage(), ex);
                onFail(mintItems);
            }
        }
    }
}


