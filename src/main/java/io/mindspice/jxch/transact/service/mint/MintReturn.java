package io.mindspice.jxch.transact.service.mint;

import java.util.List;


public record MintReturn(
        boolean success,
        List<MintItem> mintItems,
        List<String> nftIds
) { }
