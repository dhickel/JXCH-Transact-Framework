package io.mindspice.jxch.transact.jobs;

import io.mindspice.jxch.rpc.http.FullNodeAPI;
import io.mindspice.jxch.rpc.http.WalletAPI;
import io.mindspice.jxch.transact.logging.TLogLevel;
import io.mindspice.jxch.transact.logging.TLogger;
import io.mindspice.jxch.transact.settings.JobConfig;

import java.util.List;
import java.util.concurrent.*;


public abstract class TService<T> {
    protected final ScheduledExecutorService executor;
    protected final JobConfig config;
    protected final TLogger tLogger;
    protected final FullNodeAPI nodeAPI;
    protected final WalletAPI walletAPI;

    protected volatile boolean stopped = true;
    protected volatile long lastTime;
    protected volatile ScheduledFuture<?> taskRef;

    protected final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();

    public TService(ScheduledExecutorService scheduledExecutor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI) {
        this.executor = scheduledExecutor;
        this.config = config;
        this.tLogger = tLogger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public abstract void start();

    public abstract boolean stopAndBlock();

    public int stop() {
        stopped = true;
        return queue.size();
    }

    public boolean isRunning() {
        return !stopped;
    }

    public void terminate() {
        if (taskRef != null) {
            taskRef.cancel(true);
        }
    }

    public int size() {
        return queue.size();
    }

    public boolean submit(T item) {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Received Items: " + item);
        if (stopped) { return false; }
        queue.add(item);
        return true;
    }

    public boolean submit(List<T> items) {
        tLogger.log(this.getClass(), TLogLevel.DEBUG, "Received Items: " + items);
        if (stopped) { return false; }
        queue.addAll(items);
        return true;
    }


}
