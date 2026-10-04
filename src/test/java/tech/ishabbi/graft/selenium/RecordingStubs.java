package tech.ishabbi.graft.selenium;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.NoSuchShadowRootException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Stubs that record every context call ({@code defaultContent}, {@code frame}, finds,
 * {@code getShadowRoot}) in one shared log, for checking the exact calls {@link ContextResolver}
 * and the healer make. Finds are lookup tables keyed by {@code By.toString()}; the driver's table
 * is per frame (the name of the iframe element last switched into, {@code top} at the top);
 * {@code parentFrame} returns to the frame switched from.
 */
final class RecordingStubs {

    private RecordingStubs() {}

    static final class Driver implements WebDriver, JavascriptExecutor {
        final List<String> log = new ArrayList<>();
        final Map<String, Map<String, WebElement>> frames = new HashMap<>();
        String frame = "top";
        final Deque<String> parents = new ArrayDeque<>();
        /** Answers executeScript; the default throws, as a driver without JS would. */
        Function<String, Object> js = script -> { throw new UnsupportedOperationException("no JS in stub"); };
        /** The arguments of the executeScript call being answered. */
        Object[] args = new Object[0];

        Driver put(String frameName, By by, WebElement el) {
            frames.computeIfAbsent(frameName, k -> new HashMap<>()).put(by.toString(), el);
            return this;
        }

        @Override public WebElement findElement(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElement(this);
            log.add("find(" + frame + ", " + by + ")");
            WebElement e = frames.getOrDefault(frame, Map.of()).get(by.toString());
            if (e == null) throw new NoSuchElementException("no such element in " + frame + ": " + by);
            return e;
        }
        @Override public List<WebElement> findElements(By by) {
            WebElement e = frames.getOrDefault(frame, Map.of()).get(by.toString());
            return e == null ? List.of() : List.of(e);
        }
        @Override public Object executeScript(String script, Object... args) {
            this.args = args;
            return js.apply(script);
        }
        @Override public Object executeAsyncScript(String script, Object... args) { return null; }
        @Override public void get(String url) {}
        @Override public String getCurrentUrl() { return ""; }
        @Override public String getTitle() { return ""; }
        /** The native page source; the default is empty (unparseable). */
        java.util.function.Supplier<String> pageSource = () -> "";
        @Override public String getPageSource() { return pageSource.get(); }
        @Override public void close() {}
        @Override public void quit() {}
        @Override public Set<String> getWindowHandles() { return Set.of(); }
        @Override public String getWindowHandle() { return ""; }
        @Override public Navigation navigate() { throw new UnsupportedOperationException(); }
        @Override public Options manage() { throw new UnsupportedOperationException(); }

        @Override public TargetLocator switchTo() {
            return new TargetLocator() {
                @Override public WebDriver defaultContent() {
                    log.add("defaultContent");
                    frame = "top";
                    parents.clear();
                    return Driver.this;
                }
                @Override public WebDriver frame(WebElement el) {
                    log.add("frame(" + el + ")");
                    parents.push(frame);
                    frame = ((Element) el).name;
                    return Driver.this;
                }
                @Override public WebDriver frame(int index) { throw new UnsupportedOperationException(); }
                @Override public WebDriver frame(String nameOrId) { throw new UnsupportedOperationException(); }
                @Override public WebDriver parentFrame() {
                    log.add("parentFrame");
                    frame = parents.isEmpty() ? "top" : parents.pop();
                    return Driver.this;
                }
                @Override public WebDriver window(String nameOrHandle) { throw new UnsupportedOperationException(); }
                @Override public WebDriver newWindow(org.openqa.selenium.WindowType typeHint) { throw new UnsupportedOperationException(); }
                @Override public WebElement activeElement() { throw new UnsupportedOperationException(); }
                @Override public Alert alert() { throw new UnsupportedOperationException(); }
            };
        }
    }

    /** A shadow root: a plain SearchContext, as Selenium's own ShadowRoot is. */
    static final class Shadow implements SearchContext {
        final String name;
        final List<String> log;
        final Map<String, WebElement> elements = new HashMap<>();

        Shadow(String name, List<String> log) { this.name = name; this.log = log; }

        Shadow put(By by, WebElement el) { elements.put(by.toString(), el); return this; }

        @Override public WebElement findElement(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElement(this);
            log.add("find(" + name + ", " + by + ")");
            WebElement e = elements.get(by.toString());
            if (e == null) throw new NoSuchElementException("no such element in " + name + ": " + by);
            return e;
        }
        @Override public List<WebElement> findElements(By by) {
            WebElement e = elements.get(by.toString());
            return e == null ? List.of() : List.of(e);
        }
        @Override public String toString() { return name; }
    }

    static final class Element implements WebElement {
        final String name;
        final List<String> log;
        final Map<String, String> attrs = new HashMap<>();
        final Map<String, WebElement> children = new HashMap<>();
        Shadow shadow;

        Element(String name, List<String> log) { this.name = name; this.log = log; }

        Element attr(String k, String v) { attrs.put(k, v); return this; }

        Element child(By by, WebElement el) { children.put(by.toString(), el); return this; }

        Shadow attachShadow() { shadow = new Shadow(name + "#shadow", log); return shadow; }

        @Override public org.openqa.selenium.SearchContext getShadowRoot() {
            log.add("getShadowRoot(" + name + ")");
            if (shadow == null) throw new NoSuchShadowRootException("no shadow root on " + name);
            return shadow;
        }
        @Override public WebElement findElement(By by) {
            if (by instanceof HealingBy || by instanceof AlumniumBy) return by.findElement(this);
            log.add("find(" + name + ", " + by + ")");
            WebElement e = children.get(by.toString());
            if (e == null) throw new NoSuchElementException("no such element in " + name + ": " + by);
            return e;
        }
        @Override public List<WebElement> findElements(By by) { return List.of(); }
        @Override public String getAttribute(String n) { return attrs.get(n); }
        @Override public String getDomAttribute(String n) { return attrs.get(n); }
        @Override public String getText() { return attrs.getOrDefault("text", ""); }
        @Override public String getTagName() { return "button"; }
        @Override public void click() {}
        @Override public void submit() {}
        @Override public void sendKeys(CharSequence... k) {}
        @Override public void clear() {}
        @Override public boolean isSelected() { return false; }
        @Override public boolean isEnabled() { return true; }
        @Override public boolean isDisplayed() { return true; }
        @Override public Point getLocation() { return new Point(0, 0); }
        @Override public Dimension getSize() { return new Dimension(1, 1); }
        Rectangle rect = new Rectangle(0, 0, 1, 1);
        Element rect(Rectangle r) { rect = r; return this; }
        @Override public Rectangle getRect() { return rect; }
        @Override public String getCssValue(String p) { return ""; }
        @Override public <X> X getScreenshotAs(OutputType<X> t) { throw new UnsupportedOperationException(); }
        @Override public String toString() { return name; }
    }
}
