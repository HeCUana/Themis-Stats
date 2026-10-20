package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crafting: counts the items the player actually took away from the crafting result.
 *
 * <p>The count is the size of the stack that was handed over, so a recipe that gives nine items
 * counts nine and a single stick counts one. This is a count of <em>items handed over</em>, not a
 * claim about how much the player produced.</p>
 */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {

    @Inject(method = "onTake", at = @At("HEAD"))
    private void themisstats$onTake(Player player, ItemStack itemStack, CallbackInfo ci) {
        if (!(player instanceof ServerPlayer serverPlayer) || itemStack.isEmpty()) {
            return;
        }
        int count = itemStack.getCount();
        if (count <= 0) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
        MetricHooks.record(serverPlayer, Metric.CRAFTED, count, itemId);
    }
}
