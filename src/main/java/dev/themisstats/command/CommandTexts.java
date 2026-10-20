package dev.themisstats.command;

import dev.themisstats.metric.Metric;
import dev.themisstats.util.Numbers;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * The Chinese texts of the mod.
 *
 * <p>All messages of Themis Stats are written in Chinese, because the mod is built for Chinese
 * server owners. The texts are kept in one place so that they can be translated later without
 * touching the command logic.</p>
 */
public final class CommandTexts {

    private CommandTexts() {
    }

    public static MutableComponent title(String text) {
        return Component.literal("§8§m                                     §r §b" + text);
    }

    public static MutableComponent line(String text) {
        return Component.literal("§7" + text);
    }

    public static MutableComponent value(String label, String value) {
        return Component.literal("§7" + label + "§f: §a" + value);
    }

    public static MutableComponent error(String text) {
        return Component.literal("§c" + text);
    }

    public static MutableComponent hint(String text) {
        return Component.literal("§8" + text);
    }

    public static MutableComponent ok(String text) {
        return Component.literal("§a" + text);
    }

    /** Formats a metric value with its unit. */
    public static String format(Metric metric, long value) {
        return switch (metric) {
            case ONLINE -> Numbers.duration(value);
            case ELYTRA_DISTANCE -> Numbers.distance(value);
            default -> Numbers.full(value);
        };
    }

    /** Formats a metric value in the shortened display form. */
    public static String shortFormat(Metric metric, long value) {
        return switch (metric) {
            case ONLINE -> Numbers.duration(value);
            case ELYTRA_DISTANCE -> Numbers.distance(value);
            default -> Numbers.abbreviated(value);
        };
    }

    public static MutableComponent dim(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_GRAY);
    }
}
