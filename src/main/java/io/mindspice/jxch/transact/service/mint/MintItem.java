package io.mindspice.jxch.transact.service.mint;

import io.mindspice.jxch.rpc.schemas.wallet.nft.MetaData;

import java.util.UUID;


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
