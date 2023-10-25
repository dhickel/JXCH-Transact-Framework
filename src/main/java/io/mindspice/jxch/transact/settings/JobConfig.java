package io.mindspice.jxch.transact.settings;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.File;
import java.io.IOException;


public class JobConfig {
    // Wallets
    public int feeWalletId;
    public int mintWalletId;
    public int didWalletId;
    public int fundWalletId;
    public boolean isTestnet = false;
    public boolean mintFromDid;
    public String royaltyTarget;
    public int royaltyPercentage = 0;
    public volatile int minFeePerCost;
    public volatile int maxFeePerCost;
    public String changeTarget;
    public volatile int maxRetries = 100;
    public volatile int feeIncInterval = 10;
    public volatile int retryWaitInterval = 45000;
    public volatile int queueMaxWaitSec = 120;
    public volatile int queueCheckInterval;
    public volatile int maxConfirmWait = -1;
    public volatile int jobSize;
    public volatile long maxMemPoolCost = 550000000000L;


    public static JobConfig loadConfig(String configPath) throws IOException {
        YAMLMapper mapper = new YAMLMapper();
        return  mapper.readValue(new File(configPath), JobConfig.class);
    }
}
