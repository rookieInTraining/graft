package tech.ishabbi.graft.cache;

/** A Redis connection opened by {@link RedisLocatorStore#open}. Does not mention Jedis. */
public interface RemoteCache extends AutoCloseable {

    LearnedLocatorStore store(String namespace);

    boolean ping();

    @Override
    void close();
}
