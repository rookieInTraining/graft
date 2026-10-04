package tech.rookieintraining.graft.cache;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Path encoding and the Redis key layout {@code graft:{namespace}:{locatorKey}}. */
public final class CachePaths {

    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CachePaths() {}

    public static boolean namespaceOk(String namespace) {
        return namespace != null && NAMESPACE.matcher(namespace).matches();
    }

    /** Percent-encode a path or query segment. Spaces are {@code %20}, not {@code +}. */
    public static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String decode(String raw) {
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    public static String redisKey(String namespace, String locatorKey) {
        return redisPrefix(namespace) + locatorKey;
    }

    public static String redisPrefix(String namespace) {
        return "graft:" + namespace + ":";
    }
}
