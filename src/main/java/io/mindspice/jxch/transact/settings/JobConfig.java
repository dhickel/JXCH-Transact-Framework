package io.mindspice.jxch.transact.settings;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.mindspice.jxch.rpc.NodeConfig;

import java.io.File;
import java.io.IOException;


public class JobConfig {
    public int didWalletId;
    public int feeWalletId;
    public int mintWalletId;
    public int assetWalletId;
    public int fundWallet;
    public int minFeePerCost;
    public int maxFeePerCost;
    public String mintChangeTarget;
    public int maxRetries = 100;
    public int feeIncInterval = 10;
    public int retryWaitInterval = 45000;
    public boolean incFeeOnFail = true;
    public int queueMaxWaitSec = 120;
    public int queueCheckInterval;
    public int jobSize;

    public static JobConfig loadConfig(String configPath) throws IOException {
        YAMLMapper mapper = new YAMLMapper();
        return  mapper.readValue(new File(configPath), JobConfig.class);
    }
}
