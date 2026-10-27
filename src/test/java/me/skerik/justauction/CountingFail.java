package me.skerik.justauction;

import java.util.concurrent.atomic.AtomicInteger;

/** Lets {@code skip} calls of a store method through, then fails the next {@code failures}. */
final class CountingFail {

    private CountingFail() {
    }

    static void install(TestKit.Env env, String method, int skip, int failures) {
        AtomicInteger calls = new AtomicInteger();
        env.store.interceptor = (name) -> {
            if (!name.equals(method)) return false;
            int n = calls.incrementAndGet();
            return n > skip && n <= skip + failures;
        };
    }
}
