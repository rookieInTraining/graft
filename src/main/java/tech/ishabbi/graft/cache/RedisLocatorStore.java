package tech.ishabbi.graft.cache;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import tech.ishabbi.graft.LocatorSuggestion;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis implementation used only by {@link CacheServer}. Loaded reflectively so the rest of
 * Graft does not require Jedis on the classpath.
 *
 * <p>{@code learnedAt} is written at a fixed width so the compare-and-delete Lua script can
 * order instants with a string compare. The same rule as {@link ConditionalForget}.
 */
public final class RedisLocatorStore implements LearnedLocatorStore {

    static final DateTimeFormatter LEARNED_AT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private static final String FORGET = """
            local raw = redis.call('GET', KEYS[1])
            if not raw then return 0 end
            local ok, decoded = pcall(cjson.decode, raw)
            if not ok or type(decoded) ~= 'table' or not decoded['learnedAt'] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            if decoded['learnedAt'] > ARGV[1] then return 2 end
            redis.call('DEL', KEYS[1])
            return 1
            """;

    private final JedisPooled jedis;
    private final String namespace;

    private RedisLocatorStore(JedisPooled jedis, String namespace) {
        this.jedis = jedis;
        this.namespace = namespace;
    }

    public static RemoteCache open(String redisUrl) {
        JedisPooled jedis = new JedisPooled(URI.create(redisUrl));
        return new RemoteCache() {
            @Override
            public LearnedLocatorStore store(String namespace) {
                if (!CachePaths.namespaceOk(namespace)) {
                    throw new IllegalArgumentException("Invalid namespace: " + namespace);
                }
                return new RedisLocatorStore(jedis, namespace);
            }

            @Override
            public boolean ping() {
                try {
                    return "PONG".equalsIgnoreCase(jedis.ping());
                } catch (RuntimeException e) {
                    return false;
                }
            }

            @Override
            public void close() {
                jedis.close();
            }
        };
    }

    @Override
    public Optional<StoredEntry> get(String key) {
        String raw = jedis.get(CachePaths.redisKey(namespace, key));
        if (raw == null) return Optional.empty();
        return Optional.of(CacheJson.parseEntry(raw));
    }

    @Override
    public void learn(String key, LocatorSuggestion suggestion, String origin, String framework) {
        if (suggestion == null) return;
        String learnedAt = LEARNED_AT.format(Instant.now());
        JsonObject o = JsonParser.parseString(CacheJson.entry(
                new StoredEntry(suggestion, origin == null ? "" : origin, learnedAt, framework))).getAsJsonObject();
        jedis.set(CachePaths.redisKey(namespace, key), o.toString());
    }

    @Override
    public ForgetResult forget(String key, String ifLearnedAt) {
        String cutoff = LEARNED_AT.format(Instant.parse(ifLearnedAt));
        Object result = jedis.eval(FORGET, List.of(CachePaths.redisKey(namespace, key)), List.of(cutoff));
        long code = ((Number) result).longValue();
        if (code == 0) return ForgetResult.ABSENT;
        if (code == 2) return ForgetResult.KEPT;
        return ForgetResult.DELETED;
    }

    @Override
    public Map<String, StoredEntry> snapshot() {
        String prefix = CachePaths.redisPrefix(namespace);
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(prefix + "*").count(200);
        Map<String, StoredEntry> out = new LinkedHashMap<>();
        do {
            ScanResult<String> scan = jedis.scan(cursor, params);
            for (String redisKey : scan.getResult()) {
                if (!redisKey.startsWith(prefix)) continue;
                String raw = jedis.get(redisKey);
                if (raw == null) continue;
                StoredEntry entry = CacheJson.parseEntry(raw);
                if (entry.suggestion() != null) out.put(redisKey.substring(prefix.length()), entry);
            }
            cursor = scan.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return out;
    }
}
