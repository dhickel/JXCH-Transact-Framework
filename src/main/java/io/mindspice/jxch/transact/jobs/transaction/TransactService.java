package io.mindspice.jxch.transact.jobs.transaction;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.rpc.schemas.wallet.Addition;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;
import io.mindspice.jxch.transact.util.Pair;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;


public abstract class TransactService implements Runnable{
    private final ScheduledExecutorService executor;
    private final JobConfig config;
    private final TLogger tLogger;
    private final FullNodeAPI nodeAPI;
    private final WalletAPI walletAPI;
    private final boolean isCat;

    private final ConcurrentLinkedQueue<Addition> transactionQueue = new ConcurrentLinkedQueue<>();

    private boolean stopped = true;
    private ScheduledFuture<?> taskRef;
    private long lastTime;

    public TransactService(ScheduledExecutorService executor, JobConfig config, TLogger tLogger,
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

    public boolean submit(Addition item) {
        if (stopped) { return false; }
        transactionQueue.add(item);
        return true;
    }



    // Override to handle what to do with failed mints
    protected abstract void onFail(List<Addition> transactionItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    protected abstract void onFinish(List<Addition> transactionItems);


    @Override
    public void run() {
        if (transactionQueue.isEmpty()) {
            if (stopped) { terminate(); } else { return; }
        }

        long nowTime = Instant.now().getEpochSecond();
        if (transactionQueue.size() >= config.jobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
            lastTime = nowTime;

            TransactionJob transactionJob = new TransactionJob(config, tLogger, nodeAPI, walletAPI, isCat);
            List<Addition> transactionItems = IntStream.range(0, Math.min(config.jobSize, transactionQueue.size()))
                    .mapToObj(i -> transactionQueue.poll())
                    .filter(Objects::nonNull).toList();
            try {
                Pair<Boolean, List<Addition>> transactionResult = executor.submit(transactionJob).get();
                if (transactionResult.first()) {
                    onFinish(transactionResult.second());
                } else {
                    onFail(transactionResult.second());
                }
            } catch (Exception e) {
                tLogger.log(this.getClass(), TLogLevel.APP_ERROR,
                            "TransactionJob: " + transactionJob.getJobId() + " Failed" +
                                   " | Exception: " + e.getMessage() +
                                   " | Trace: " + Arrays.toString(e.getStackTrace()));
                onFail(transactionItems);
            }
        }
    }

}
