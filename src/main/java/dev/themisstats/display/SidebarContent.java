package dev.themisstats.display;

import dev.themisstats.identity.Identity;
import dev.themisstats.metric.Metric;
import dev.themisstats.storage.StatsRepository;
import dev.themisstats.util.Numbers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds the visible content of a sidebar.
 *
 * <p>This is deliberately free of any game object, so that the rules that matter - the exact
 * 64 bit value in the text, the sort key that is independent of it, the line limit and the own
 * line for a viewer who is not in the top list - can be tested directly.</p>
 */
public final class SidebarContent {

    /** One rendered line. The sort key is only used for the order, never for the value. */
    public record Line(int sortKey, String text) {
    }

    /** The complete content of one sidebar. */
    public record Content(String header, List<Line> lines) {
    }

    /** How many leaderboard entries are shown. */
    public static final int MAX_LINES = 10;

    private SidebarContent() {
    }

    /**
     * @param metricId the metric that is shown
     * @param groupId  the identity group that is shown
     * @param entries  the board, already ordered and ranked
     * @param viewer   the player that looks at the sidebar, may be {@code null}
     * @return the content, or {@code null} when the metric or the group is not usable
     */
    public static Content build(String metricId, String groupId,
                                List<StatsRepository.BoardEntry> entries, UUID viewer) {
        Metric metric = Metric.byId(metricId);
        Identity identity = Identity.byId(groupId);
        if (metric == null || identity == Identity.UNKNOWN) {
            return null;
        }
        StringBuilder header = new StringBuilder();
        header.append("§8§m        §r §bThemis Stats §8| §f").append(metric.displayName());
        if (metric.isModeClassified()) {
            header.append(" §8(生存+冒险)");
        } else {
            header.append(" §8(").append(identity.displayName()).append("在线)");
        }

        List<Line> lines = new ArrayList<>();
        int sortKey = 1000;
        int index = 0;
        boolean viewerInList = false;
        long viewerValue = 0L;
        String viewerName = null;
        for (StatsRepository.BoardEntry entry : entries) {
            if (index >= MAX_LINES) {
                // The viewer may still be further down the board: remember its value for the own
                // line, but it is not part of the displayed list.
                if (viewer != null && viewer.equals(entry.uuid())) {
                    viewerValue = entry.value();
                    viewerName = entry.name();
                }
                continue;
            }
            lines.add(new Line(sortKey--, "§8#" + entry.rank() + " §f" + entry.name() + " §8- §a"
                    + Numbers.abbreviated(entry.value())));
            if (viewer != null && viewer.equals(entry.uuid())) {
                viewerInList = true;
                viewerValue = entry.value();
                viewerName = entry.name();
            }
            index++;
        }
        if (!viewerInList && viewer != null && viewerValue > 0) {
            lines.add(new Line(sortKey--, "§8" + (viewerName == null ? "你" : viewerName)
                    + " §7不在前 " + MAX_LINES + " 名 §a(" + Numbers.abbreviated(viewerValue) + ")"));
        }
        return new Content(header.toString(), lines);
    }
}
