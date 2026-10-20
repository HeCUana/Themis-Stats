package dev.themisstats.bot;

import carpet.helpers.EntityPlayerActionPack;
import carpet.patches.EntityPlayerMPFake;
import dev.themisstats.mixin.EntityPlayerActionPackAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the action plan of a Carpet fake.
 *
 * <p>The action pack is Carpet's internal state, so it is read through a Mixin accessor that was
 * written against the verified 1.4.194 sources. The mod only ever <em>reads</em> the plan: it
 * never starts, stops or changes an action, and it never takes over the fake.</p>
 *
 * <p>When a field cannot be observed in the running version, it is reported as "not observable in
 * this version" instead of being guessed.</p>
 */
public final class CarpetActionAdapter {

    private CarpetActionAdapter() {
    }

    /**
     * @return a human readable description of the configured actions, never {@code null}
     */
    public static List<String> describe(EntityPlayerActionPack pack) {
        List<String> result = new ArrayList<>();
        if (pack == null) {
            return result;
        }
        Map<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> actions;
        try {
            actions = ((EntityPlayerActionPackAccessor) pack).themisstats$actions();
        } catch (RuntimeException | LinkageError e) {
            result.add("动作计划：当前版本不可观察");
            return result;
        }
        if (actions == null || actions.isEmpty()) {
            result.add("动作计划：未配置动作");
            return result;
        }
        for (Map.Entry<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> entry
                : actions.entrySet()) {
            EntityPlayerActionPack.Action action = entry.getValue();
            StringBuilder line = new StringBuilder("动作计划：");
            line.append(describeType(entry.getKey()));
            line.append(' ').append(describeAction(action));
            result.add(line.toString());
        }
        return result;
    }

    private static String describeType(EntityPlayerActionPack.ActionType type) {
        return switch (type) {
            case USE -> "使用物品";
            case ATTACK -> "攻击";
            case JUMP -> "跳跃";
            case DROP_ITEM -> "丢弃物品";
            case DROP_STACK -> "丢弃整组";
            case SWAP_HANDS -> "交换主副手";
        };
    }

    private static String describeAction(EntityPlayerActionPack.Action action) {
        if (action == null) {
            return "（未知）";
        }
        if (action.limit == 1) {
            return "（执行一次）";
        }
        if (action.limit > 1) {
            return "（最多 " + action.limit + " 次，每 " + action.interval + " tick）";
        }
        if (action.interval <= 1) {
            return "（持续执行）";
        }
        return "（每 " + action.interval + " tick 一次）";
    }

    /**
     * @return whether the object is a Carpet fake whose action plan can be observed
     */
    public static boolean isObservable(Object player) {
        return player instanceof EntityPlayerMPFake;
    }
}
