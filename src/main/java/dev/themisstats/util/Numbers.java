package dev.themisstats.util;

import java.util.Locale;

/**
 * Number formatting.
 *
 * <p>Two representations exist and they are never mixed up:</p>
 * <ul>
 *   <li><b>Display</b> - a shortened form such as {@code 1.2万} for the sidebar and for chat
 *       messages. It is only ever used for presentation.</li>
 *   <li><b>Full</b> - the exact 64 bit value, with thousands separators. This is what the
 *       database, the ranking, the gap to the neighbour and the export use.</li>
 * </ul>
 */
public final class Numbers {

    private Numbers() {
    }

    /** Formats a value with thousands separators, without any abbreviation. */
    public static String full(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * Formats a value for display, abbreviating large numbers.
     *
     * <p>The abbreviation is Chinese ("万", "亿") because the interface of this mod is Chinese.</p>
     */
    public static String abbreviated(long value) {
        long absolute = Math.abs(value);
        if (absolute < 10_000L) {
            return full(value);
        }
        if (absolute < 100_000_000L) {
            return trim(value / 10_000.0D) + "万";
        }
        if (absolute < 10_000_000_000L) {
            return trim(value / 100_000_000.0D) + "亿";
        }
        return trim(value / 100_000_000_000.0D) + "万亿";
    }

    private static String trim(double value) {
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        formatted = formatted.replaceAll("0+$", "");
        formatted = formatted.replaceAll("\\.$", "");
        return formatted;
    }

    /**
     * Formats a duration in milliseconds as {@code 3 小时 12 分钟}.
     */
    public static String duration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        long seconds = millis / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0) {
            return hours + " 小时 " + minutes + " 分钟";
        }
        if (minutes > 0) {
            return minutes + " 分钟 " + remainingSeconds + " 秒";
        }
        return remainingSeconds + " 秒";
    }

    /**
     * Formats a distance that is stored in thousandths of a block.
     */
    public static String distance(long milliBlocks) {
        if (Math.abs(milliBlocks) < 1000L) {
            return milliBlocks + "‰方块";
        }
        double blocks = milliBlocks / 1000.0D;
        return String.format(Locale.ROOT, "%,.1f 方块", blocks);
    }
}
