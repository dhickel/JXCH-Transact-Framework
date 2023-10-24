package io.mindspice.jxch.transact.service;

import io.mindspice.jxch.rpc.schemas.object.Coin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;


public class ExcludedCoinRepo {
    private static final ExcludedCoinRepo INSTANCE = new ExcludedCoinRepo();
    private final List<Coin> excludedCoins = Collections.synchronizedList(new ArrayList<>());
    private final Semaphore semaphore = new Semaphore(1);

    public static List<Coin> getSharedExcluded() {
        return INSTANCE.excludedCoins;
    }

    public static Semaphore getSemaphore() {
        return INSTANCE.semaphore;
    }
}
