package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pickup: counts the items that really entered the player's inventory.
 *
 * <p>The count is the difference between the stack size before the pickup and the size that is
 * left over, so a partially filled inventory only counts what it really took. The size of the
 * dropped entity is never used as the count.</p>
 *
 * <p><b>Why the item is read before the pickup.</b> When the whole stack fits into the inventory,
 * vanilla empties the stack and then restores its count, so the item of the emptied stack is
 * {@code minecraft:air}. Reading the item afterwards would file every pickup under air, so the
 * name is taken before the inventory is touched.</p>
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {

    @Unique
    private int themisstats$countBefore;

    @Unique
    private String themisstats$itemBefore;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void themisstats$beforeTouch(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        ItemStack stack = self.getItem();
        themisstats$countBefore = stack.getCount();
        themisstats$itemBefore = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Inject(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z",
            shift = At.Shift.AFTER))
    private void themisstats$afterAdd(Player player, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        int before = themisstats$countBefore;
        String itemId = themisstats$itemBefore;
        themisstats$countBefore = 0;
        themisstats$itemBefore = null;
        if (!(player instanceof ServerPlayer serverPlayer) || before <= 0 || itemId == null) {
            return;
        }
        int leftover = self.getItem().getCount();
        int picked = before - leftover;
        if (picked <= 0) {
            return;
        }
        MetricHooks.record(serverPlayer, Metric.PICKED_UP, picked, itemId);
    }
}
