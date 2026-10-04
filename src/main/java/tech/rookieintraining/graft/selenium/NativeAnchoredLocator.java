package tech.rookieintraining.graft.selenium;

import tech.rookieintraining.graft.LocatorSuggestion;
import org.openqa.selenium.Rectangle;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proposes a verified-unique locator for a native (Appium) element from the app's page source,
 * with no driver involved: find the node by its bounds and class, then try, in order, its own
 * {@code resource-id} / {@code content-desc} (Android) or {@code name} (iOS), an XPath anchored on
 * the nearest ancestor with a unique such attribute, and finally its {@code text} (Android) or
 * {@code label} (iOS). Every candidate is evaluated against the parsed page source and accepted
 * only if it selects exactly the target node.
 */
final class NativeAnchoredLocator {

    /** {@code targetFound} separates "not in the page source" (callers may fall back) from "nothing unique". */
    record Result(boolean targetFound, LocatorSuggestion suggestion) {}

    private static final Pattern NOISE = Pattern.compile("^[:\\d]|\\d{4,}");
    private static final Pattern BOUNDS = Pattern.compile("\\[(-?\\d+),(-?\\d+)]\\[(-?\\d+),(-?\\d+)]");
    private static final Result UNPARSEABLE = new Result(false, null);

    private NativeAnchoredLocator() {}

    static LocatorSuggestion suggest(String pageSourceXml, Rectangle target, String targetClass) {
        return analyze(pageSourceXml, target, targetClass).suggestion();
    }

    static Result analyze(String pageSourceXml, Rectangle target, String targetClass) {
        if (pageSourceXml == null || pageSourceXml.isBlank() || target == null) return UNPARSEABLE;
        try {
            Document doc = parse(pageSourceXml);
            Element node = findTarget(doc.getDocumentElement(), target, targetClass);
            if (node == null) return UNPARSEABLE;
            return new Result(true, locate(doc, node));
        } catch (Exception e) {
            return UNPARSEABLE;   // malformed XML, DTD, or an XPath the page source's names make invalid
        }
    }

    private static LocatorSuggestion locate(Document doc, Element node) throws Exception {
        boolean android = node.hasAttribute("bounds");
        XPath xp = XPathFactory.newInstance().newXPath();

        // 1. the node's own identifier
        if (android) {
            String id = identifier(node, "resource-id");
            if (id != null && isTarget(doc, xp, "//*[@resource-id=" + LocatorBuilder.xpathLiteral(id) + "]", node)) {
                return LocatorSuggestion.of("id", id);
            }
            String desc = identifier(node, "content-desc");
            if (desc != null && isTarget(doc, xp, "//*[@content-desc=" + LocatorBuilder.xpathLiteral(desc) + "]", node)) {
                return LocatorSuggestion.of("accessibilityId", desc);
            }
        } else {
            String name = identifier(node, "name");
            if (name != null && isTarget(doc, xp, "//*[@name=" + LocatorBuilder.xpathLiteral(name) + "]", node)) {
                return LocatorSuggestion.of("accessibilityId", name);
            }
        }

        // 2. a path down from the nearest ancestor that has a unique identifier of its own
        for (Node p = node.getParentNode(); p instanceof Element anc; p = p.getParentNode()) {
            String anchor = anchorXPath(doc, xp, anc, android);
            if (anchor == null) continue;
            String candidate = anchor + pathDown(anc, node);
            if (isTarget(doc, xp, candidate, node)) return LocatorSuggestion.of("xpath", candidate);
        }

        // 3. visible text, checked against the very XPath the learned tier replays
        String text = blankToNull(node.getAttribute(android ? "text" : "label"));
        if (text != null && isTarget(doc, xp, LocatorBuilder.textXPath(text), node)) {
            return LocatorSuggestion.of("text", text);
        }
        return null;
    }

    /** {@code //*[@attr=X]} for an ancestor's own unique, non-noisy identifier; {@code null} if it has none. */
    private static String anchorXPath(Document doc, XPath xp, Element anc, boolean android) throws Exception {
        for (String attr : android ? new String[] {"resource-id", "content-desc"} : new String[] {"name"}) {
            String v = identifier(anc, attr);
            if (v == null) continue;
            String own = "//*[@" + attr + "=" + LocatorBuilder.xpathLiteral(v) + "]";
            if (isTarget(doc, xp, own, anc)) return own;
        }
        return null;
    }

    /** {@code /step/step} from {@code anc} down to {@code node}; {@code [n]} only among same-tag siblings. */
    private static String pathDown(Element anc, Element node) {
        List<String> steps = new ArrayList<>();
        for (Node n = node; n != anc; n = n.getParentNode()) {
            Element e = (Element) n;
            int same = 0, position = 0;
            for (Node s = e.getParentNode().getFirstChild(); s != null; s = s.getNextSibling()) {
                if (s instanceof Element se && se.getTagName().equals(e.getTagName())) {
                    same++;
                    if (se == e) position = same;
                }
            }
            steps.add(0, e.getTagName() + (same > 1 ? "[" + position + "]" : ""));
        }
        return "/" + String.join("/", steps);
    }

    private static boolean isTarget(Document doc, XPath xp, String expr, Element target) throws Exception {
        NodeList hits = (NodeList) xp.evaluate(expr, doc, XPathConstants.NODESET);
        return hits.getLength() == 1 && hits.item(0) == target;
    }

    /** The attribute's value unless blank or a generated id; an Android id's package prefix is not judged. */
    private static String identifier(Element e, String attr) {
        String v = blankToNull(e.getAttribute(attr));
        if (v == null) return null;
        int idx = v.indexOf(":id/");
        String judged = idx >= 0 ? v.substring(idx + 4) : v;
        return judged.isBlank() || NOISE.matcher(judged).find() ? null : v;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    // -- finding the target

    private static Element findTarget(Element root, Rectangle t, String cls) {
        List<Element> byBounds = new ArrayList<>();
        List<Integer> depths = new ArrayList<>();
        collect(root, 0, t, byBounds, depths);
        if (byBounds.isEmpty()) return null;

        List<Integer> pool = new ArrayList<>();
        if (cls != null) {
            for (int i = 0; i < byBounds.size(); i++) if (hasClass(byBounds.get(i), cls)) pool.add(i);
        }
        if (pool.isEmpty()) for (int i = 0; i < byBounds.size(); i++) pool.add(i);

        int best = pool.get(0);
        for (int i : pool) if (depths.get(i) > depths.get(best)) best = i;
        return byBounds.get(best);
    }

    private static void collect(Element e, int depth, Rectangle t, List<Element> out, List<Integer> depths) {
        if (matchesBounds(e, t)) {
            out.add(e);
            depths.add(depth);
        }
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) collect(ce, depth + 1, t, out, depths);
        }
    }

    private static boolean hasClass(Element e, String cls) {
        return cls.equals(e.getTagName()) || cls.equals(e.getAttribute("class")) || cls.equals(e.getAttribute("type"));
    }

    private static boolean matchesBounds(Element e, Rectangle t) {
        if (e.hasAttribute("bounds")) {   // Android: [x1,y1][x2,y2]
            Matcher m = BOUNDS.matcher(e.getAttribute("bounds"));
            return m.matches()
                    && Integer.parseInt(m.group(1)) == t.x && Integer.parseInt(m.group(2)) == t.y
                    && Integer.parseInt(m.group(3)) == t.x + t.width && Integer.parseInt(m.group(4)) == t.y + t.height;
        }
        if (e.hasAttribute("x") && e.hasAttribute("width")) {   // iOS: x, y, width, height
            try {
                return Math.round(Double.parseDouble(e.getAttribute("x"))) == t.x
                        && Math.round(Double.parseDouble(e.getAttribute("y"))) == t.y
                        && Math.round(Double.parseDouble(e.getAttribute("width"))) == t.width
                        && Math.round(Double.parseDouble(e.getAttribute("height"))) == t.height;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        var builder = f.newDocumentBuilder();
        builder.setErrorHandler(null);   // no "[Fatal Error]" on stderr for a malformed source
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
