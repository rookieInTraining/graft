package tech.rookieintraining.graft.cache;

import tech.rookieintraining.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tech.rookieintraining.graft.cache.LearnedLocatorStore.ForgetResult.DELETED;
import static tech.rookieintraining.graft.cache.LearnedLocatorStore.ForgetResult.KEPT;

@EnabledIfEnvironmentVariable(named = "REDIS_URL", matches = ".+")
class RedisLocatorStoreTest {

    @Test
    void learnIsVisibleAndAnOlderMissDoesNotDeleteANewerRow() throws Exception {
        String namespace = "graft-test-" + System.nanoTime();
        try (RemoteCache cache = RedisLocatorStore.open(System.getenv("REDIS_URL"))) {
            assertTrue(cache.ping());
            LearnedLocatorStore store = cache.store(namespace);
            String key = "by:By.id: login-old|button";
            store.learn(key, LocatorSuggestion.of("testId", "signin-btn"), "Origin.java:1", "SELENIUM");
            String learnedAt = store.get(key).orElseThrow().learnedAt();

            assertEquals(KEPT, store.forget(key, "2020-01-01T00:00:00Z"));
            assertTrue(store.get(key).isPresent());
            assertEquals(DELETED, store.forget(key, learnedAt));
            assertTrue(store.get(key).isEmpty());
        }
    }
}
