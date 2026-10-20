package dev.themisstats.display;

import dev.themisstats.storage.StatsRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of the visible sidebar content.
 *
 * <p>The important property is that the business value never has to fit into the 32 bit score
 * that the vanilla sidebar uses, and that the sort key is completely independent of it.</p>
 */
class SidebarContentTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");

    private static List<StatsRepository.BoardEntry> entries() {
        List<StatsRepository.BoardEntry> list = new ArrayList<>();
        list.add(new StatsRepository.BoardEntry(ALICE, "Alice", 9_000_000_000L, 1));
        list.add(new StatsRepository.BoardEntry(BOB, "Bob", 500L, 2));
        return list;
    }

    @Test
    void aValueAboveThe32BitRangeIsShownInFull() {
        SidebarContent.Content content = SidebarContent.build("mining", "human", entries(), null);
        assertNotNull(content);
        String first = content.lines().get(0).text();
        // The value is abbreviated for the display, but it must never be truncated to 32 bits and
        // it must never be the score.
        assertTrue(first.contains("Alice"), first);
        assertTrue(first.contains("90亿") || first.contains("90,000") || first.contains("9.00亿"),
                "the large value must be shown: " + first);
    }

    @Test
    void theSortKeyIsIndependentOfTheValue() {
        SidebarContent.Content content = SidebarContent.build("mining", "human", entries(), null);
        List<SidebarContent.Line> lines = content.lines();
        // The score is only a sort key: it stays in a small range and decreases with the rank.
        assertTrue(lines.get(0).sortKey() > 0 && lines.get(0).sortKey() <= 1000);
        assertTrue(lines.get(0).sortKey() > lines.get(1).sortKey());
        // The huge value is nowhere near the sort key.
        assertTrue(lines.get(0).sortKey() < 2000, "the sort key must not carry the business value");
    }

    @Test
    void theRankIsShown() {
        SidebarContent.Content content = SidebarContent.build("mining", "human", entries(), null);
        assertTrue(content.lines().get(0).text().contains("#1"));
        assertTrue(content.lines().get(1).text().contains("#2"));
    }

    @Test
    void theHeaderStatesTheMetricAndTheModeScope() {
        SidebarContent.Content content = SidebarContent.build("mining", "human", entries(), null);
        assertTrue(content.header().contains("挖掘量"), content.header());
        assertTrue(content.header().contains("生存+冒险"), content.header());
    }

    @Test
    void theOnlineMetricIsNotLabelledAsAModeScope() {
        SidebarContent.Content content = SidebarContent.build("online", "bot", entries(), null);
        assertTrue(content.header().contains("统计在线时长"), content.header());
        assertTrue(content.header().contains("假人"), content.header());
        assertTrue(!content.header().contains("生存+冒险"),
                "the online metric must not be presented as a survival only value: " + content.header());
    }

    @Test
    void aViewerOutsideTheTopListGetsAnOwnLine() {
        List<StatsRepository.BoardEntry> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new StatsRepository.BoardEntry(UUID.nameUUIDFromBytes(("p" + i).getBytes()),
                    "Player" + i, 1000 - i, i + 1));
        }
        many.add(new StatsRepository.BoardEntry(BOB, "Bob", 7L, 13));
        SidebarContent.Content content = SidebarContent.build("mining", "human", many, BOB);
        assertEquals(SidebarContent.MAX_LINES + 1, content.lines().size(),
                "the own line is added below the top list");
        assertTrue(content.lines().get(content.lines().size() - 1).text().contains("Bob"));
    }

    @Test
    void aViewerInsideTheTopListGetsNoExtraLine() {
        SidebarContent.Content content = SidebarContent.build("mining", "human", entries(), ALICE);
        assertEquals(2, content.lines().size());
    }

    @Test
    void atMostTenEntriesAreShown() {
        List<StatsRepository.BoardEntry> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(new StatsRepository.BoardEntry(UUID.nameUUIDFromBytes(("q" + i).getBytes()),
                    "P" + i, 1000 - i, i + 1));
        }
        SidebarContent.Content content = SidebarContent.build("mining", "human", many, null);
        assertEquals(SidebarContent.MAX_LINES, content.lines().size(),
                "the vanilla sidebar only has room for a limited number of lines");
    }

    @Test
    void anUnknownMetricOrGroupProducesNoContent() {
        assertNull(SidebarContent.build("not_a_metric", "human", entries(), null));
        assertNull(SidebarContent.build("mining", "unknown", entries(), null));
    }
}
