package io.mindspice.settings;

public class MintConfig {
    public int didWalletId;
    public int feeWalletId;
    public int mintWalletId;
    public int fundWallet;
    public int minFeePerCost;
    public int maxFeePerCost;
    public String mintChangeTarget;
    public long mempoolMaxCost = 10000000000L;
    public int maxRetries = 100;
    public int feeIncInterval = 10;
    public int retryWaitInterval = 45000;
    public boolean incFeeOnFail = true;
    public int queueMaxWaitSec = 120;
    public int queueCheckInterval;
    public int mintJobSize;
}
