package io.mindspice.jxch.transact.jobs.transaction;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.mindlib.data.tuples.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;


public abstract class TransactionService implements Runnable{
    protected final ScheduledExecutorService executor;
    protected final JobConfig config;
    protected final TLogger tLogger;
    protected final FullNodeAPI nodeAPI;
    protected final WalletAPI walletAPI;
    protected final boolean isCat;

    protected final ConcurrentLinkedQueue<TransactionItem> transactionQueue = new ConcurrentLinkedQueue<>();

    protected volatile boolean stopped = true;
    protected volatile ScheduledFuture<?> taskRef;
    protected volatile long lastTime;

    public TransactionService(ScheduledExecutorService executor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI, boolean isCat) {
        this.executor = executor;
        this.config = config;
        this.tLogger = tLogger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
        this.isCat = isCat;
    }

    public int stop() {
        stopped = true;
        return transactionQueue.size();
    }

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

    private void terminate() {
        taskRef.cancel(true);
    }

    public int size() {
        return transactionQueue.size();
    }

    public boolean submit(TransactionItem item) {
        if (stopped) { return false; }
        transactionQueue.add(item);
        return true;
    }

    public boolean submit(List<TransactionItem> items) {
        if (stopped) { return false; }
        transactionQueue.addAll(items);
        return true;
    }



    // Override to handle what to do with failed mints
    protected abstract void onFail(List<TransactionItem> transactionItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(List<TransactionItem> transactionItems);


    @Override
    public void run() {
        if (transactionQueue.isEmpty()) {
            if (stopped) { terminate(); } else { return; }
        }

        long nowTime = Instant.now().getEpochSecond();
        if (transactionQueue.size() >= config.jobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
            lastTime = nowTime;

            TransactionJob transactionJob = new TransactionJob(config, tLogger, nodeAPI, walletAPI, isCat);
            List<TransactionItem> transactionItems = IntStream.range(0, Math.min(config.jobSize, transactionQueue.size()))
                    .mapToObj(i -> transactionQueue.poll())
                    .filter(Objects::nonNull).toList();
            try {
                Pair<Boolean, List<TransactionItem>> transactionResult = executor.submit(transactionJob).get();
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
    }

}
