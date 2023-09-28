package io.mindspice.jxch.transact.jobs.mint;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.mindspice.jxch.rpc.schemas.wallet.nft.MetaData;

import java.util.UUID;


public record MintItem(
        @JsonAlias({"target_address", "targetAddress"})
        @JsonProperty("target_address") String targetAddress,
        @JsonAlias({"meta_data", "metaData"})
        @JsonProperty("meta_data") MetaData metaData,
        @JsonProperty("uuid") String uuid
) {

        public MintItem(String targetAddress, MetaData metaData) {
                this(targetAddress, metaData, UUID.randomUUID().toString());
        }

        public MintItem {
                if (uuid == null) { uuid = UUID.randomUUID().toString(); }
        }
}
