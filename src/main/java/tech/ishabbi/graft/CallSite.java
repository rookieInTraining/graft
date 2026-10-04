package tech.ishabbi.graft;

/** Captures where a locator constant was declared, so the heal report can say what file to edit. */
public final class CallSite {

    private CallSite() {}

    public static String capture() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(f -> !f.getClassName().startsWith("tech.ishabbi.graft")
                        && !f.getClassName().startsWith("java.")
                        && !f.getClassName().startsWith("jdk."))
                .findFirst()
                .map(f -> f.getClassName() + "(" + f.getFileName() + ":" + f.getLineNumber() + ")")
                .orElse("<unknown>"));
    }
}
