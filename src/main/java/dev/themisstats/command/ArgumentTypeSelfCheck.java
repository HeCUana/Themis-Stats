package dev.themisstats.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.themisstats.ThemisStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.ArgumentUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A self check for the command tree.
 *
 * <p><b>Why this exists.</b> Minecraft serialises the whole command tree for every player that
 * joins ({@code Commands#sendCommands}). The serialiser looks up every argument type in
 * {@link ArgumentTypeInfos}, the registry of the argument types the vanilla client understands. A
 * custom Brigadier argument type that is not in that registry makes the serialisation throw, and
 * the player is disconnected while joining - the server looks fine and nobody can play.</p>
 *
 * <p>The check runs once, when the commands are registered, and reports every argument type that
 * the vanilla client would not understand. It is a warning and not a crash: a broken tree must not
 * stop the server from starting, but it has to be loud.</p>
 */
public final class ArgumentTypeSelfCheck {

    private ArgumentTypeSelfCheck() {
    }

    /**
     * Checks the command tree and logs the result.
     *
     * @param dispatcher the dispatcher the Themis Stats commands were added to
     */
    public static void verify(CommandDispatcher<CommandSourceStack> dispatcher) {
        if (dispatcher == null) {
            return;
        }
        try {
            if (!ArgumentTypeInfos.isClassRecognized(StringArgumentType.class)) {
                // The registry of the argument types is not bootstrapped yet. The check would
                // report every type as unknown, so it is postponed instead of crying wolf.
                ThemisStats.LOGGER.info("[ThemisStats] 命令树参数类型自检跳过：原版参数类型注册表尚未就绪");
                return;
            }
            Set<ArgumentType<?>> used = ArgumentUtils.findUsedArgumentTypes(dispatcher.getRoot());
            List<String> unknown = new ArrayList<>();
            for (ArgumentType<?> type : used) {
                if (!ArgumentTypeInfos.isClassRecognized(type.getClass())) {
                    unknown.add(type.getClass().getName() + " (" + type + ")");
                }
            }
            if (unknown.isEmpty()) {
                ThemisStats.LOGGER.info("[ThemisStats] 命令树参数类型自检通过，共 {} 种，全部为原版已注册类型",
                        used.size());
                return;
            }
            unknown.sort(String::compareTo);
            ThemisStats.LOGGER.error("[ThemisStats] 命令树里有原版未注册的参数类型：{}。"
                    + "原版在玩家进服时序列化命令树，未注册的类型会导致玩家无法进服，请改用原版参数类型。",
                    String.join("、", unknown));
        } catch (RuntimeException | LinkageError e) {
            // The self check must never be the reason the server does not start.
            ThemisStats.LOGGER.warn("[ThemisStats] 命令树参数类型自检没能完成：{}", e.toString());
        }
    }
}
