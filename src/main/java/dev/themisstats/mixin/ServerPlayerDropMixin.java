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
 *
 * <p><b>How many items are counted.</b> {@code Player#drop(boolean)} drops a single item when the
 * flag is false (the hot key) and the whole stack when it is true (drop stack). The vanilla
 * implementation removes exactly one item from the selected slot in the first case
 * ({@code Inventory#removeFromSelected}), so counting the whole stack here would report items that
 * are still in the inventory. The count therefore follows the flag.</p>
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
        int count = random ? stack.getCount() : 1;
        if (count <= 0) {
            return;
        }
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        MetricHooks.record(self, Metric.DROPPED, count, itemId);
    }
}
