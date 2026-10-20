package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Dropping: counts the items the player actively dropped.
 *
 * <p>Only the deliberate drop of the hot key counts. The items a player loses when it dies are a
 * different thing and are not counted here, so the "dropped" statistic never mixes the two.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDropMixin {

    @Inject(method = "drop(Z)V", at = @At("HEAD"))
    private void themisstats$onDrop(boolean random, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        Inventory inventory = self.getInventory();
        ItemStack stack = inventory.getSelectedItem();
        if (stack.isEmpty()) {
            return;
        }
        int count = stack.getCount();
        if (count <= 0) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        MetricHooks.record(self, Metric.DROPPED, count, itemId);
    }
}
