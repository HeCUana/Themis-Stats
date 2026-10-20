package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Villager trades: counts one successfully executed trade.
 *
 * <p><b>Why this hook.</b> The injection sits exactly on the call that vanilla makes when a trade
 * was really executed ({@code Merchant#notifyTrade}). Reaching that call means the offer was
 * accepted and the payment was taken, so a trade that was merely prepared - for example by
 * putting the payment into the slot - is not counted.</p>
 *
 * <p><b>Unit.</b> One executed trade is one increment, even when the trade gives several items.
 * The interface says "trades", not "items received".</p>
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {

    @Inject(method = "onTake", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/trading/Merchant;notifyTrade(Lnet/minecraft/world/item/trading/MerchantOffer;)V"))
    private void themisstats$onTrade(Player player, ItemStack itemStack, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer) {
            MetricHooks.record(serverPlayer, Metric.VILLAGER_TRADES, 1L, null);
        }
    }
}
