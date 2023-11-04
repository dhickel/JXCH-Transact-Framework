package io.mindspice.jxch.transact.service;

import io.mindspice.jxch.rpc.schemas.object.Coin;

import java.util.*;
import java.util.concurrent.Semaphore;


public class ExcludedCoinRepo {
    private static final ExcludedCoinRepo INSTANCE = new ExcludedCoinRepo();
    private final Set<Coin> excludedCoins = Collections.synchronizedSet(new HashSet<>());
    private final Semaphore semaphore = new Semaphore(1);

    public static Set<Coin> getSharedExcluded() {
        return INSTANCE.excludedCoins;
    }

    public static Semaphore getSemaphore() {
        return INSTANCE.semaphore;
    }
}
