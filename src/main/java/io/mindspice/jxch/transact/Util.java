package io.mindspice.jxch.transact;

import io.mindspice.jxch.rpc.schemas.object.Coin;
import io.mindspice.jxch.transact.service.mint.MintItem;
import io.mindspice.jxch.transact.service.transaction.TransactionItem;

import java.util.*;


public class Util {
    public static Map<TransactionItem, Coin> mapTransactions(List<TransactionItem> txList, List<Coin> coins) {
        Map<TransactionItem, Coin> mappedTransactions = new HashMap<>();
        for (var item : txList) {
            for (var coin : coins) {
                if (coin.puzzleHash().equals(item.addition().puzzleHash())
                        && coin.amount() == item.addition().amount()) {
                    mappedTransactions.put(item, coin);
                    coins.remove(coin);
                    break;
                }
            }
        }
        return mappedTransactions;
    }

    public static Map<MintItem, String> mapMints(List<MintItem> mintItems, List<String> nftIds) {
        Map<MintItem, String> mappedMints = new HashMap<>();
        for (int i = 0; i < mintItems.size(); ++i) {
            mappedMints.put(mintItems.get(i), nftIds.get(i));
        }
        return mappedMints;
    }

}
