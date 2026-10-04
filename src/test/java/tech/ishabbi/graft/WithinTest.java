package tech.ishabbi.graft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WithinTest {

    @Test
    void parsesAndFormatsRoundTrip() {
        Within.Hop frame = Within.parse("frame=#pay");
        assertEquals(Within.Type.FRAME, frame.type());
        assertEquals("#pay", frame.css());
        assertEquals("frame=#pay", frame.format());
        assertEquals("shadow=my-host", Within.parse("shadow=my-host").format());
    }

    @Test
    void cssMayContainEquals() {
        Within.Hop hop = Within.parse("frame=iframe[name='pay']");
        assertEquals("iframe[name='pay']", hop.css());
        assertEquals("frame=iframe[name='pay']", hop.format());
    }

    @Test
    void trimsPrefixAndCss() {
        Within.Hop hop = Within.parse("  shadow  =  my-host  ");
        assertEquals(Within.Type.SHADOW, hop.type());
        assertEquals("my-host", hop.css());
    }

    @Test
    void rejectsMalformedHops() {
        for (String bad : new String[] {"iframe=#x", "Frame=#x", "#x", "frame=", "frame=   ", "", null}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Within.parse(bad), String.valueOf(bad));
            if (bad != null) assertTrue(e.getMessage().contains("\"" + bad + "\""), e.getMessage());
        }
    }

    @Test
    void parseAllAndFormatAll() {
        List<Within.Hop> hops = Within.parseAll("frame=#a", "shadow=b");
        assertEquals(List.of("frame=#a", "shadow=b"), Within.formatAll(hops));
        assertEquals(hops, Within.parseAll(List.of("frame=#a", "shadow=b")));
        assertThrows(UnsupportedOperationException.class, () -> hops.add(Within.parse("frame=x")));
    }

    @Test
    void nullAndEmptyGiveEmptyLists() {
        assertEquals(List.of(), Within.parseAll((String[]) null));
        assertEquals(List.of(), Within.parseAll());
        assertEquals(List.of(), Within.parseAll((List<String>) null));
        assertEquals(List.of(), Within.parseAll(List.of()));
    }
}
