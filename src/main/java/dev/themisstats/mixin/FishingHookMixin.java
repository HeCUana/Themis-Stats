package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fishing: counts one successful catch.
 *
 * <p>Two injection points, both of which are only reached when something was really caught:</p>
 * <ul>
 *   <li>the loot table roll - the bobber bit something and a catch was generated;</li>
 *   <li>the pull of a hooked entity - the player hooked an item or a mob.</li>
 * </ul>
 *
 * <p>An empty cast and an empty reel-in reach neither of them and are therefore not counted.</p>
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {

    @Inject(method = "retrieve", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/loot/LootTable;getRandomItems(Lnet/minecraft/world/level/storage/loot/LootParams;)Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
    private void themisstats$onCatch(ItemStack itemStack, CallbackInfoReturnable<Integer> cir) {
        themisstats$countOwner();
    }

    @Inject(method = "retrieve", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/FishingHook;pullEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void themisstats$onHooked(ItemStack itemStack, CallbackInfoReturnable<Integer> cir) {
        themisstats$countOwner();
    }

    private void themisstats$countOwner() {
        FishingHook self = (FishingHook) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        Player owner = self.getPlayerOwner();
        if (owner instanceof ServerPlayer serverPlayer) {
            MetricHooks.record(serverPlayer, Metric.FISHING, 1L, null);
        }
    }
}
