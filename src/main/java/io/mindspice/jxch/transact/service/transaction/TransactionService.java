package io.mindspice.jxch.transact.service.transaction;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.service.TService;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.jxch.transact.util.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.IntStream;


public abstract class TransactionService extends TService<TransactionItem> implements Runnable {
    protected volatile Future<Pair<Boolean, List<TransactionItem>>> currentJob;

    public TransactionService(ScheduledExecutorService executor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        super(executor, config, tLogger, nodeAPI, walletAPI);
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
    // returns the original items, as well as their created coins
    protected abstract void onFinish(List<TransactionItem> txItemsWithCoins);

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

                List<TransactionItem> transactionItems = IntStream.range(0, Math.min(config.jobSize, queue.size()))
                        .mapToObj(i -> queue.poll())
                        .filter(Objects::nonNull).toList();

                TransactionJob transactionJob = new TransactionJob(config, tLogger, nodeAPI, walletAPI);
                transactionJob.addTransaction(transactionItems);
                try {
                    currentJob = executor.submit(transactionJob);
                    var txReturn = currentJob.get();
                    if (txReturn.first()) {
                        onFinish(txReturn.second());
                    } else {
                        onFail(txReturn.second());
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
