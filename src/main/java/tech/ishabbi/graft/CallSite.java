package tech.ishabbi.graft;

import java.security.CodeSource;
import java.util.Objects;

/** Captures where a locator constant was declared, so the heal report can say what file to edit. */
public final class CallSite {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private CallSite() {}

    public static String capture() {
        return WALKER.walk(frames -> frames
                .filter(f -> !isGraft(f.getDeclaringClass())
                        && !f.getClassName().startsWith("java.")
                        && !f.getClassName().startsWith("jdk."))
                .findFirst()
                .map(f -> f.getClassName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")")
                .orElse("<unknown>"));
    }

    /**
     * Graft's own frames are recognised by where they were loaded from, not by package name, so
     * callers that share the {@code tech.ishabbi.graft} prefix (Graft's tests, examples) still count.
     * When Graft is shaded into the caller's own jar, every frame shares that location, so
     * {@code origin} degrades to {@code <unknown>} (report-only: it labels reports and stored rows).
     */
    private static boolean isGraft(Class<?> c) {
        CodeSource graft = codeSource(CallSite.class);
        if (graft == null) return c.getName().startsWith("tech.ishabbi.graft");
        return Objects.equals(graft.getLocation(), locationOf(c));
    }

    private static java.net.URL locationOf(Class<?> c) {
        CodeSource cs = codeSource(c);
        return cs == null ? null : cs.getLocation();
    }

    private static CodeSource codeSource(Class<?> c) {
        try {
            return c.getProtectionDomain().getCodeSource();
        } catch (SecurityException e) {
            return null;
        }
    }
}
