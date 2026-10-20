package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Piglin barter: counts one successful exchange with a piglin.
 *
 * <p>The exchange is complete when the piglin gives its response item back. The attribution comes
 * from the player the items are actually thrown to. If there is no player to attribute the
 * exchange to, nothing is counted - the mod never guesses an owner for a barter that cannot be
 * attributed.</p>
 *
 * <p>Only the barter response counts. A piglin that simply throws a gold ingot it found on the
 * ground to a player is not a barter and is not counted.</p>
 */
@Mixin(PiglinAi.class)
public abstract class PiglinBarterMixin {

    /** Set when a barter response is about to be handed out. */
    @Unique
    private static boolean themisstats$barterPending;

    @Inject(method = "stopHoldingOffHandItem", at = @At("HEAD"))
    private static void themisstats$onBarterFinished(net.minecraft.server.level.ServerLevel level,
                                                     Piglin piglin, boolean completed, CallbackInfo ci) {
        if (!completed) {
            // The piglin was interrupted: the gold is simply dropped, no exchange happened.
            themisstats$barterPending = false;
            return;
        }
        ItemStack offhand = piglin.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
        themisstats$barterPending = !offhand.isEmpty();
    }

    @Inject(method = "throwItemsTowardPlayer", at = @At("HEAD"))
    private static void themisstats$onBarterResponse(Piglin piglin, Player player, List<ItemStack> items,
                                                      CallbackInfo ci) {
        if (!themisstats$barterPending) {
            return;
        }
        themisstats$barterPending = false;
        if (items == null || items.isEmpty() || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        MetricHooks.record(serverPlayer, Metric.PIGLIN_BARTERS, 1L, "minecraft:piglin");
    }
}
