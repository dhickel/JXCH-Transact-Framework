
# ***Disclaimer***
***JXCH-Transact-Framework  is an unofficial third party library and has no relation to, or endorsement by Chia Network, Inc***


# **About**

This library aims to provide a lightweight framework for bulk NFT minting and distribution of CAT tokens and XCH, without imposing any 
specific workflow or concrete implementation outside the core mint/transaction logic.

The feature of the framework include:

- Bulk minting of NFTs dynamically to specified addresses.
- Bulk transactions, with the ability to send multiple CAT tokens and XCH from the same wallet.
- Tracking of in-use coins across instances to avoid double-spends.
- Robust logging independent of any specific logging framework.
- Robust tracking of mint/transactions at various levels via UUIDs.
- Adaptive fee handling and customization to ensure the lowest fee needed is used.
- Customization of fee bounds and incrementation.

<br>

# **Requirements/Dependencies**

While the only dependency the library has is a custom version of the JXCH-RPC-Library and its few transitive dependencies, it does 
require currently require a custom endpoint, this can be added manually or can be found in the forked version of the chia client below, 
which also includes some helpful custom endpoints for getting the sender address of a CAT token, and an endpoint to get the proper XCH address of an NFT.
You will also need to use the ```custom-main``` branch of the JXCH-RPC-Library as it implements the aggregate_spends endpoint below.

### [JXCH-RPC-Library custom-main branch](https://github.com/mindspice/JXCH-RPC-Library/tree/custom-main)


### [Custom Chia Fork](https://github.com/mindspice/chia-blockchain/tree/2.x-main-custom-endpoints)



If not wanting to use the custom fork this is function you need to add to you wallet_rpc_api.py, this is used to aggregated the fee onto 
the returned nft bulk mint spendbundle bundle.

```python

    # At top under get routes
    "/aggregate_spends": self.aggregate_spends,
    
    #..... rest of code

    async def aggregate_spends(self, request) -> EndpointResult:

        if request["spends"] is None:
            return {"success": False, "error": "No spends"}

        spend_bundles = [SpendBundle.from_json_dict(spend) for spend in request['spends']]
        if len(spend_bundles) < 2:
            return {"success": False, "error": "Must include 2 or more spends"}

        agg_bundle = SpendBundle.aggregate(spend_bundles)
        return {"success": True, "agg_bundle": agg_bundle}

```


<br>



# **Usage**

## Overview

The framework includes 2 "job" classes, 2 "service" classes and 2 "item" classes each with an implementation for minting and an 
implementation for transacting.

### MintService/TransactionService
Both MintService and TransactionService extend the super class TService, these class are the core persistent class that will run for the 
duration of your service, they contain a queue, a reference to an executor, references to the respective wallet/node API that will be 
used and the loaded configuration that will be used. 

These classes handle the queueing of Transaction/Mint Items and launching a new Mint/Transaction job once either a size or time limit is 
meet with the items housed in the queue. Both MintService and TransactionService are abstract classes and require you to implement the 
methods ```onFinish(List<Item> finishedItemsWithIds)``` and ```onFail(List<Item> failedItems```. The methods are used to handle what 
happens once mint/transactions are completed, or what to do when they fail. You could say add failed items back to the queue and/or log 
them, add them to a failed queue to resubmit later etc...

MintService and TransactionServices also have the methods ```start```
 ```stopAndBlock()``` ```terminate()``` ```Submit(List<T>)``` ```Submit(T)``` ```IsRunning()``` ```size()```
<br>

The submit methods are for adding items to the service queue and the others for control of the service.

<br>

### MintItem/TransactionItem
These are simple record classes and hold the information needed to mint/transact, these objects are submitted to the services for 
inclusion into a job. Once a job is complete they will be return to the ```onFinish()``` method of the service with either the NFT Id, 
or the created coin id. They each have options UUID field that can be used for management/logging, if one is not specified a random uuid 
is generated.

***A custom uuid can be used to group mints, while each item is a single mint, you can share a uuid between them and use it later 
in the ```onFinish``` method.***

```java
public record MintItem(
        String targetAddress,
        MetaData metaData,
        String uuid,
        String nftId
) {

    public MintItem(String targetAddress, MetaData metaData) {
        this(targetAddress, metaData, UUID.randomUUID().toString(), null);
    }

    public MintItem(String targetAddress, MetaData metaData, String uuid) {
        this(targetAddress, metaData, uuid, null);
    }

    public MintItem withNftId(String nftId) {
        return new MintItem(
                this.targetAddress,
                this.metaData,
                this.uuid,
                nftId
        );
    }
}

public record TransactionItem(
        Addition addition,
        String uuid,
        Coin coin

) {
 public TransactionItem(Addition addition) {
  this(addition, UUID.randomUUID().toString(), null);
 }

 public TransactionItem(Addition addition, String uuid) {
  this(addition, uuid, null);
 }

 public TransactionItem withCoin(Coin coin) {
  return new TransactionItem(
          this.addition,
          this.uuid,
          coin
  );
 }

}

```


### MintJob/TransactionJob
Both MintJob and TransactionJob extend the super class TJob, these classes handle the actual process of a mint or transaction.
Once either the queue hits a specific size or if the time limit specified in the config file for the service is reached Mint/Transaction 
Items will be added to a job and sent to the executor passed to the service to run. The job is executed and returned as a future, the 
job classes handle all the raw logic of rpc calls, coin selection, crafting the bundles, incrementing fees as needed. No real 
interaction should need to occur with them, as it is all handled internally.

they do include a ```getState()``` method that will return the current state of the job:
```java
    public enum State {
        INIT,
        AWAITING_SYNC,
        AWAITING_CONFIRMATION,
        RETRYING,
        STARTED,
        EXCEPTION,
        SUCCESS,
        FAILED
    }

```






### ExcludedCoinRepo
This singleton is shared between all job classes and acts as a repository for coins in uses, the prohibits and services using the same 
wallets from selecting the same coins causing double_spends and other issues. All services share it even if they are using different 
wallets and coins are removed from it on finish/fail/exceptional conditions. It also includes a semaphore that is aquired for all coin 
selections to avoid race conditions during selection.



### TransactionState
This is also another internal class used by Jobs, each job has their own instance and it is used to hold data internally to make passing 
it around cleaner.




## Job Configs (Important)

Each job needs to be passed a configuration file that defines the wallet ids, return address and various settings/bounds for the job to 
respect. Job configs can be loaded with the ```loadConfig(String configPath``` of ```JobConfig.class``` both TransactionService and 
MintService use the job config class.


An overview of a job config is:
```yaml
didWalletId: 2      # Wallet of the DID to use if using a DID (mint only)
feeWalletId: 1      # Wallet to take fee from
mintWalletId: 3     # Wallet to mint from (mint only)
fundWalletId: 1     # Wallet for funding (XCH wallet for minting/XCH transaction, CAT wallet for CAT transactions
mintFromDid: true   # Whether to mint from did or not (mint only)
isTestnet: false    # Set to true if using testnet 
royaltyTarget: "xch190t02wyv9sj6gqu524nqa68vdgataxx4wm998x2f8v8k5scylc7qyv3zaj" # Address for nft royalties, must XCH encoded not hex
royaltyPercentage: 1000 # Percent amount for royalties each 100 = 1%, 1000 = 10%, 10_000 = 100%
minFeePerCost: 0    # Min fee to use, if greater than 0 and less than 5, 5 will be used as only 5 FPC and above is honored by the chia client
maxFeePerCost: 7    # Max fee to use, once fee pre cost is hit the job class will not increase
changeTarget: "0xb2fd22349ec56011c41495c5dc6b24fcecbbfcdbb22b4fbea6a4795030d1e7bd"    # This need to be a hex 0x<puzzle_hash> not xch encoded prefixed addressed
maxRetries: 100             # Max amount of times to retry a job before quitting (onFail will be called)
feeIncInterval: 5           # How many retries between fee incrementation
retryWaitInterval: 45000    # How long to wait before retrying in ms
queueMaxWaitSec: 600        # How long to wait before starting a job regardless of queue size
queueCheckInterval: 30      # How often to check the queue in seconds
jobSize: 50                 # Target size for a job, if a service's queue has equal or more items a job will be launched with this many items
debugSpendbundle: true      # Log spendbundle to debug
maxConfirmWait: -1          # If not set to -1, this is the max time that a job will wait for a confirmation before resubmitting the transaction with a higher fee
```



## Node/Wallet API Configs
The framework is an extension of the JXCH-RPC-Library and depends on it for core functionality, service classes expect a FullNodeApi and 
WalletAPI reference to be passed them, which are instances from the JXCH-RPC-Library. You will need to define a config for them that 
points to yours certs and addresses and instance them for use int transacting and mint. Info on doing this can be found on the 
RPC-Library repo page, it's super simple to set up.

https://github.com/mindspice/JXCH-RPC-Library#usage




## TLogger
Each service also expect and instance of TLogger, this is just a simple interface with some methods to override to implement logging. 
The framework makes robust use of logging to provide what coins are being used, successful mint/transactions and failed transactions, to 
ensure that there is a proper trail to audit if any mishaps happen. It also include verbose debug logging for diagnosing any issues. 
This is all done agnostic to any logging framework. Just implement the TLogger class and direct the logging methods to your logger of 
choice.




# Example

The following is a quick high level example of setting up the framework.


### Implement a service class

```java
public class MyMintService extends MintService {
    private final OkraNFTAPI nftApi;
    private final Supplier<RPCException> chiaExcept =
            () -> new RPCException("Required Chia RPC call returned Optional.empty");

    private final List<MintItem> failedMints = new CopyOnWriteArrayList<>();
    
    public MyMintService(ScheduledExecutorService scheduledExecutor, JobConfig config, TLogger tLogger,
            FullNodeAPI nodeAPI, WalletAPI walletAPI, MyDataBase myDataBase) {
        super(scheduledExecutor, config, tLogger, nodeAPI, walletAPI);
    }

    @Override
    protected void onFail(List<MintItem> failList) {
        failedMints.addAll(failList);
        myDataBaseClass.recordFailedMint(failList);
    }

    public void reSubmitFailedMints() {
        submit(failedMints);
        failedMints.clear();

    }

    public int failedMintCount() {
        return failedMints.size();
    }

    @Override
    protected void onFinish(List<MintItem> mints) {
        mints.forEach(m -> myDataBase.insertNewMintId(c.nftId));
    }
}

```




### Implement the logger

```java

public class MyLogger implements TLogger {
    private static final Logger MINT_LOG = LoggerFactory.getLogger("MINT_LOGGER");
    private static final Logger FAILED_LOG = LoggerFactory.getLogger("FAILED_LOGGER"); // Separate log for failure
    @Override
    public void log(Class<?> aClass, TLogLevel tLogLevel, String s) {
        String msg = String.format("%s - %s", aClass.getName(), s);
        switch (tLogLevel) {
            case ERROR -> MINT_LOG.error(msg);
            case INFO -> MINT_LOG.info(msg);
            case WARNING -> MINT_LOG.warn(msg);
            case FAILED -> FAILED_LOG.error(msg);
            case DEBUG -> MINT_LOG.debug(msg);
        }
    }

    @Override
    public void log(Class<?> aClass, TLogLevel tLogLevel, String s, Exception e) {
        String msg = String.format("%s - %s", aClass.getName(), s);
        switch (tLogLevel) {
            case ERROR -> MINT_LOG.error(msg, e);
            case INFO -> MINT_LOG.info(msg, e);
            case WARNING -> MINT_LOG.warn(msg, e);
            case FAILED -> FAILED_LOG.error(msg, e);
            case DEBUG -> MINT_LOG.debug(msg, e);
        }
    }
}
```

### Declare your rpc client apis and start your service

```java
// These are from the JXCH-RPC-Library
NodeConfig myNodeConfig = NodeConfig.loadConfig("/path/to/config/config.yaml");
RPCClient rpcClient = new RPCClient(myNodeConfig);
FullNodeAPI nodeAPI = new FullNodeAPI(rpcClient);
WallerAPI walletAPI = new WalletAPI(rpcClient);


JobConfig myConfig = JobConfig.loadConfig("/path/to/myconfig.yaml");
MyLogger myLogger = new myLogger();
ScheduledExecutorService myExecutor = Executors.newScheduledThreadPool(1);

MyMintService myService = new MyMintService(myExecutor, myConfig, myLogger,nodeAPI, walletAPI)
myService.start();


// You can now craft your mint items and submit them to the service,
// or implement it in your api to accept mints from elsewhere

myService.submit(mintItem);
myService.submit(mintItemsList);

```

# More Examples

A current in-use full implementation of the framework can be found in the repository below, this is from the Outer Fields project and is 
used for minting card packs and handling reward transactions.

### Mint Service
https://github.com/Outer-Fields/item-server/blob/master/src/main/java/io/mindspice/itemserver/services/CardMintService.java

### Transaction Service
https://github.com/Outer-Fields/item-server/blob/master/src/main/java/io/mindspice/itemserver/services/TokenService.java


# Notes

***Transaction Item additions a hex puzzlehash, this is the 0x form not the XCH form.***

***NFT address must be XCH or TXCH, but hex puzzlehash can be used as well and will be encoded internally***

***While the framework itself avoids race conditions with coins, you must ensure coins can not be spent elsewhere outside the 
services, as double_spends that happen not on the first iteration of a job are consider successful since the coin was not spent when the 
job started and if spent after it was started and the spendbundle submitted have to be re result of a successful mint/transaction***

***Occasionally a CATs wallets seem to have issues with not properly track coins, resulting in failed transactions due to double_spend 
on the first iteration. This is a bug in the wallet, and should go away if you delete the wallet database and resync***

***If using a standalone wallet, it is recommended to pair it with a trusted node. This help with performance and should help offset any 
issue like the one mentioned prior***

