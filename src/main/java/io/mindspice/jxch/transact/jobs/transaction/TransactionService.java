package io.mindspice.jxch.transact.jobs.transaction;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.jobs.TService;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.jxch.transact.util.Pair;

import java.sql.SQLOutput;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;


public abstract class TransactionService extends TService<TransactionItem> implements Runnable {

    protected final boolean isCat;
    protected volatile Future<Pair<Boolean, List<TransactionItem>>> currentJob;

    public TransactionService(ScheduledExecutorService executor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI, boolean isCat) {
        super(executor, config, tLogger, nodeAPI, walletAPI);
        this.isCat = isCat;
    }

    @Override
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
    protected abstract void onFail(List<TransactionItem> transactionItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(List<TransactionItem> transactionItems);

    @Override
    public void run() {

        try {
            if (queue.isEmpty()) {
                if (stopped) {
                    terminate();
                } else {
                    return;
                }
            }

            long nowTime = Instant.now().getEpochSecond();
            if (queue.size() >= config.jobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
                lastTime = nowTime;

                TransactionJob transactionJob = new TransactionJob(config, tLogger, nodeAPI, walletAPI, isCat);
                List<TransactionItem> transactionItems = IntStream.range(0, Math.min(config.jobSize, queue.size()))
                        .mapToObj(i -> queue.poll())
                        .filter(Objects::nonNull).toList();

                transactionJob.addTransaction(transactionItems);
                try {
                    currentJob = executor.submit(transactionJob);
                    Pair<Boolean, List<TransactionItem>> transactionResult = currentJob.get();
                    if (transactionResult.first()) {
                        onFinish(transactionResult.second());
                    } else {
                        onFail(transactionResult.second());
                    }
                } catch (Exception ex) {
                    tLogger.log(this.getClass(), TLogLevel.ERROR,
                            "TransactionJob: " + transactionJob.getJobId() + " Failed" +
                                    " | Exception: " + ex.getMessage(), ex);
                    onFail(transactionItems);
                }
            }
        } catch (Exception e) {
            tLogger.log(this.getClass(), TLogLevel.ERROR, "Exception running service task", e);
        }
    }
}
