package io.mindspice.mint;

import io.mindspice.http.FullNodeAPI;
import io.mindspice.http.WalletAPI;
import io.mindspice.logging.LogLevel;
import io.mindspice.logging.MintLogger;
import io.mindspice.settings.MintConfig;
import io.mindspice.util.Pair;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.stream.IntStream;


public abstract class MintService implements Runnable {
    private final ExecutorService executorService;
    private final MintConfig config;
    private final MintLogger logger;
    private final FullNodeAPI nodeAPI;
    private final WalletAPI walletAPI;
    private final ConcurrentLinkedQueue<MintItem> mintQueue = new ConcurrentLinkedQueue<>();
    private boolean stopped = false;

    public MintService(ExecutorService executorService, MintConfig config, MintLogger logger,
            WalletAPI walletAPI, FullNodeAPI nodeAPI) {
        this.executorService = executorService;
        this.config = config;
        this.logger = logger;
        this.nodeAPI = nodeAPI;
        this.walletAPI = walletAPI;
    }

    public int shutDown() {
        stopped = true;
        return mintQueue.size();
    }

    public int size() {
        return mintQueue.size();
    }

    public boolean submit(MintItem item) {
        if (stopped) { return false; }
        mintQueue.add(item);
        return true;
    }

    // Override to handle what to do with failed mints
    public abstract void onMintFail(List<MintItem> mintItems);

    // Override if you have actions that need performed on finished mints
    // returns the original items, as well as their on chain NFT Ids
    public abstract void onMintFinish(Pair<List<MintItem>, List<String>> itemsAndNftIds);

    public void run() {
        long lastTime = Instant.now().getEpochSecond();
        while (true) {

            if (mintQueue.isEmpty()) {
                if (stopped) { return; }
                try {
                    Thread.sleep(config.queueCheckInterval);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            long nowTime = Instant.now().getEpochSecond();
            if (mintQueue.size() >= config.mintJobSize || nowTime - lastTime >= config.queueMaxWaitSec) {
                lastTime = nowTime;

                MintJob mintJob = new MintJob(config, logger, nodeAPI, walletAPI);
                List<MintItem> mintItems = IntStream.range(0, Math.min(config.mintJobSize, mintQueue.size()))
                        .mapToObj(i -> mintQueue.poll())
                        .filter(Objects::nonNull).toList();
                try {
                    Pair<Boolean, List<String>> mintResult = executorService.submit(mintJob).get();
                    onMintFinish(new Pair<>(mintItems, mintResult.second()));
                } catch (Exception e) {
                    logger.log(this.getClass(), LogLevel.APP_ERROR,
                               "MintJob: " + mintJob.getJobId() + " Failed" +
                                       " | Exception: " + e.getMessage());
                    onMintFail(mintItems);
                }
            } else {
                try {
                    Thread.sleep(config.queueCheckInterval);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

}
