
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
- Robust tracking of mint/transactions at various levels via UUIDs
- Adaptive fee handling and customization to ensure the lowest fee needed is used.
- Customization of fee bounds and incrementation.

<br>

# **Requirements/Dependencies**

While the only dependency the library has is a custom version of the JXCH-RPC-Library and its few transitive dependencies, it does 
require a few new/edited RPC endpoints in the chia node/wallet to use. These can either be manually added on ones own chia installation 
or the forked repo provided can be used. The forked repo is always kept relatively up to date with the main Chia repo as it is the 
implementation used in the Outer Fields project.

## ***Custom JXCH-RPC-Library***
To use this framework you will need to use the custom branch of the JXCH-RPC-Library as it includes the new endpoints. It is the same as 
the main branch outside of the extra endpoints.

### [JXCH-RPC-Llibrary Branch Needed](https://github.com/mindspice/JXCH-RPC-Library/tree/custom-main)


## ***Custom Chia Fork***
You can find the custom chia fork needed in the following repository if you wish to use it. It is kept up to date and with a simple 
commit history to allow for easy diffing vs the main repository. Along with the new/edited endpoints needed for this framework it also 
includes a few other endpoints that may be of use to other project which including endpoints to get the sender address of a CAT token, 
and an endpoint to get the proper XCH address of an NFT. The endpoints added can also be access via the custom branch of the 
JXCH-RPC_library linked above


### [Repository Link](https://github.com/mindspice/chia-blockchain/tree/2.x-main-custom-endpoints)


## ***Custom Endpoints***

The following is the code needed for the custom endpoints, you can add this code to respective files if you wish to not use the above fork.


### WALLET


**<u>Spendbundle Aggregation</u>**
```python

    async def aggregate_spends(self, request) -> EndpointResult:

        if request["spends"] is None:
            return {"success": False, "error": "No spends"}

        spend_bundles = [SpendBundle.from_json_dict(spend) for spend in request['spends']]
        if len(spend_bundles) < 2:
            return {"success": False, "error": "Must include 2 or more spends"}

        agg_bundle = SpendBundle.aggregate(spend_bundles)
        return {"success": True, "agg_bundle": agg_bundle}

```


### Node




