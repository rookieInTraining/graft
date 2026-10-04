package tech.ishabbi.graft.selenium;

import tech.ishabbi.graft.LocatorSuggestion;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Rectangle;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeAnchoredLocatorTest {

    /** UiAutomator2 style: a form, and a recycler list whose rows repeat the same resource-ids. */
    static final String ANDROID = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy index="0" class="hierarchy" rotation="0" width="1080" height="2400">
              <android.widget.FrameLayout index="0" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
                <android.widget.LinearLayout index="0" resource-id="com.app:id/login_form" class="android.widget.LinearLayout" bounds="[0,100][1080,600]">
                  <android.widget.EditText index="0" resource-id="com.app:id/email" class="android.widget.EditText" text="" bounds="[0,100][1080,200]" />
                  <android.widget.ImageView index="1" content-desc="Close" class="android.widget.ImageView" bounds="[0,250][100,350]" />
                  <android.widget.Button index="2" resource-id="com.app:id/submit" class="android.widget.Button" text="Sign in" bounds="[0,400][1080,500]" />
                </android.widget.LinearLayout>
                <androidx.recyclerview.widget.RecyclerView index="1" resource-id="com.app:id/list" class="androidx.recyclerview.widget.RecyclerView" bounds="[0,700][1080,1100]">
                  <android.view.ViewGroup index="0" class="android.view.ViewGroup" bounds="[0,700][1080,900]">
                    <android.widget.TextView index="0" resource-id="com.app:id/title" class="android.widget.TextView" text="Apples" bounds="[0,700][500,800]" />
                    <android.widget.Button index="1" resource-id="com.app:id/buy" class="android.widget.Button" text="Buy" bounds="[500,700][1080,800]" />
                  </android.view.ViewGroup>
                  <android.view.ViewGroup index="1" class="android.view.ViewGroup" bounds="[0,900][1080,1100]">
                    <android.widget.TextView index="0" resource-id="com.app:id/title" class="android.widget.TextView" text="Pears" bounds="[0,900][500,1000]" />
                    <android.widget.Button index="1" resource-id="com.app:id/buy" class="android.widget.Button" text="Buy" bounds="[500,900][1080,1000]" />
                  </android.view.ViewGroup>
                </androidx.recyclerview.widget.RecyclerView>
                <android.widget.FrameLayout index="2" resource-id="com.app:id/card" class="android.widget.FrameLayout" bounds="[0,1200][100,1300]">
                  <android.widget.ImageView index="0" content-desc="Card icon" class="android.widget.ImageView" bounds="[0,1200][100,1300]" />
                </android.widget.FrameLayout>
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    /** No resource-ids or descriptions to anchor on: one noisy id, one unique text, a duplicated text. */
    static final String ANDROID_BARE = """
            <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
            <hierarchy index="0" class="hierarchy" rotation="0">
              <android.widget.FrameLayout index="0" class="android.widget.FrameLayout" bounds="[0,0][1080,2400]">
                <android.widget.Button index="0" resource-id="com.app:id/view12345" class="android.widget.Button" text="Pay now" bounds="[0,0][500,100]" />
                <android.widget.Button index="1" class="android.widget.Button" text="OK" bounds="[0,200][500,300]" />
                <android.widget.Button index="2" class="android.widget.Button" text="OK" bounds="[0,400][500,500]" />
              </android.widget.FrameLayout>
            </hierarchy>
            """;

    static final String IOS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <AppiumAUT>
              <XCUIElementTypeApplication type="XCUIElementTypeApplication" name="Shop" label="Shop" x="0" y="0" width="390" height="844">
                <XCUIElementTypeWindow type="XCUIElementTypeWindow" x="0" y="0" width="390" height="844">
                  <XCUIElementTypeButton type="XCUIElementTypeButton" name="close" label="Close" x="10" y="20" width="40" height="40"/>
                  <XCUIElementTypeTable type="XCUIElementTypeTable" name="results" x="0" y="100" width="390" height="400">
                    <XCUIElementTypeCell type="XCUIElementTypeCell" x="0" y="100" width="390" height="100">
                      <XCUIElementTypeButton type="XCUIElementTypeButton" label="Buy" x="300" y="120" width="60" height="40"/>
                    </XCUIElementTypeCell>
                    <XCUIElementTypeCell type="XCUIElementTypeCell" x="0" y="200" width="390" height="100">
                      <XCUIElementTypeButton type="XCUIElementTypeButton" label="Buy" x="300" y="220" width="60" height="40"/>
                    </XCUIElementTypeCell>
                  </XCUIElementTypeTable>
                </XCUIElementTypeWindow>
              </XCUIElementTypeApplication>
            </AppiumAUT>
            """;

    /** iOS with nothing named above the targets. */
    static final String IOS_BARE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <AppiumAUT>
              <XCUIElementTypeWindow type="XCUIElementTypeWindow" x="0" y="0" width="390" height="844">
                <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" label="Welcome back" x="0" y="0" width="200" height="30"/>
                <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" label="Hello" x="0" y="50" width="200" height="30"/>
                <XCUIElementTypeStaticText type="XCUIElementTypeStaticText" label="Hello" x="0" y="90" width="200" height="30"/>
              </XCUIElementTypeWindow>
            </AppiumAUT>
            """;

    private static Rectangle r(int x, int y, int w, int h) { return new Rectangle(x, y, h, w); }

    private static LocatorSuggestion suggest(String xml, Rectangle rect, String cls) {
        return NativeAnchoredLocator.suggest(xml, rect, cls);
    }

    @Test
    void androidUniqueResourceIdGivesId() {
        assertEquals(LocatorSuggestion.of("id", "com.app:id/email"),
                suggest(ANDROID, r(0, 100, 1080, 100), "android.widget.EditText"));
    }

    @Test
    void androidUniqueContentDescGivesAccessibilityId() {
        assertEquals(LocatorSuggestion.of("accessibilityId", "Close"),
                suggest(ANDROID, r(0, 250, 100, 100), "android.widget.ImageView"));
    }

    @Test
    void androidDuplicatedResourceIdIsAnchoredOnAUniqueAncestor() {
        LocatorSuggestion s = suggest(ANDROID, r(500, 900, 580, 100), "android.widget.Button");
        assertEquals(LocatorSuggestion.of("xpath",
                "//*[@resource-id='com.app:id/list']/android.view.ViewGroup[2]/android.widget.Button"), s);
        assertEquals(1, count(ANDROID, s.value()));
        assertTrue(selects(ANDROID, s.value(), "bounds", "[500,900][1080,1000]"));
        // first row: the same shape, only the row position differs
        assertEquals("//*[@resource-id='com.app:id/list']/android.view.ViewGroup[1]/android.widget.Button",
                suggest(ANDROID, r(500, 700, 580, 100), "android.widget.Button").value());
    }

    @Test
    void androidNoisyIdIsSkippedInFavourOfUniqueText() {
        assertEquals(LocatorSuggestion.of("text", "Pay now"),
                suggest(ANDROID_BARE, r(0, 0, 500, 100), "android.widget.Button"));
    }

    @Test
    void androidDuplicateTextWithoutAnAnchorGivesNull() {
        assertNull(suggest(ANDROID_BARE, r(0, 200, 500, 100), "android.widget.Button"));
        assertTrue(NativeAnchoredLocator.analyze(ANDROID_BARE, r(0, 200, 500, 100), "android.widget.Button").targetFound(),
                "nothing unique is an answer, not a miss");
    }

    @Test
    void boundsAndClassDisambiguateAContainerFromItsSameBoundsChild() {
        assertEquals(LocatorSuggestion.of("id", "com.app:id/card"),
                suggest(ANDROID, r(0, 1200, 100, 100), "android.widget.FrameLayout"));
        assertEquals(LocatorSuggestion.of("accessibilityId", "Card icon"),
                suggest(ANDROID, r(0, 1200, 100, 100), "android.widget.ImageView"));
    }

    @Test
    void withoutAClassTheDeepestNodeWithTheBoundsWins() {
        assertEquals(LocatorSuggestion.of("accessibilityId", "Card icon"), suggest(ANDROID, r(0, 1200, 100, 100), null));
    }

    @Test
    void iosUniqueNameGivesAccessibilityId() {
        assertEquals(LocatorSuggestion.of("accessibilityId", "close"),
                suggest(IOS, r(10, 20, 40, 40), "XCUIElementTypeButton"));
    }

    @Test
    void iosAnchoredXPathUsesTypeSteps() {
        LocatorSuggestion s = suggest(IOS, r(300, 220, 60, 40), "XCUIElementTypeButton");
        assertEquals(LocatorSuggestion.of("xpath", "//*[@name='results']/XCUIElementTypeCell[2]/XCUIElementTypeButton"), s);
        assertEquals(1, count(IOS, s.value()));
        assertTrue(selects(IOS, s.value(), "y", "220"));
    }

    @Test
    void iosUniqueLabelGivesText() {
        assertEquals(LocatorSuggestion.of("text", "Welcome back"),
                suggest(IOS_BARE, r(0, 0, 200, 30), "XCUIElementTypeStaticText"));
        assertNull(suggest(IOS_BARE, r(0, 50, 200, 30), "XCUIElementTypeStaticText"));
    }

    @Test
    void aTargetThatIsNotInThePageSourceGivesNull() {
        assertNull(suggest(ANDROID, r(1, 2, 3, 4), "android.widget.Button"));
        assertFalse(NativeAnchoredLocator.analyze(ANDROID, r(1, 2, 3, 4), "android.widget.Button").targetFound());
    }

    @Test
    void malformedXmlGivesNullNotAnException() {
        assertNull(suggest("<hierarchy><oops", r(0, 0, 1, 1), "x"));
        assertNull(suggest("", r(0, 0, 1, 1), "x"));
        assertNull(suggest(null, r(0, 0, 1, 1), "x"));
    }

    @Test
    void doctypesAndExternalEntitiesAreRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE h [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<hierarchy><android.widget.Button class=\"android.widget.Button\" text=\"&x;\" bounds=\"[0,0][1,1]\"/></hierarchy>";
        assertFalse(NativeAnchoredLocator.analyze(xxe, r(0, 0, 1, 1), "android.widget.Button").targetFound());
    }

    @Test
    void textXPathIsTheOneLocatorBuilderReplays() {
        assertEquals("//*[@text='a' or @label='a' or @name='a' or text()[normalize-space()='a']]",
                LocatorBuilder.textXPath("a"));
    }

    // -- helpers: evaluate with the JDK, independently of the code under test

    private static Document parse(String xml) {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static NodeList eval(String xml, String xpath) {
        try {
            return (NodeList) XPathFactory.newInstance().newXPath().evaluate(xpath, parse(xml), XPathConstants.NODESET);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static int count(String xml, String xpath) { return eval(xml, xpath).getLength(); }

    private static boolean selects(String xml, String xpath, String attr, String value) {
        NodeList nodes = eval(xml, xpath);
        return nodes.getLength() == 1
                && value.equals(((org.w3c.dom.Element) nodes.item(0)).getAttribute(attr));
    }
}
