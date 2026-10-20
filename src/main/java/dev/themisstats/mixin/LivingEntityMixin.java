package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Kills of non-player entities.
 *
 * <p><b>Attribution.</b> The killer is the entity the server itself credits the kill to
 * ({@code LivingEntity#getKillCredit()}), which is the same value vanilla awards the kill score
 * with. This is the server's final attribution: an arrow shot by a player is credited to that
 * player, and a death that nobody caused is credited to nobody.</p>
 *
 * <p><b>Exactly once.</b> {@code die} is guarded by vanilla against being called twice for the
 * same death, so one death can never be counted twice - not once through the direct attack and
 * once through a statistic callback.</p>
 *
 * <p><b>Player deaths are handled elsewhere.</b> {@code ServerPlayer#die(DamageSource)} replaces
 * this method completely and does not call it, so a player death is counted by
 * {@code ServerPlayerMixin} and never twice.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void themisstats$onDie(DamageSource damageSource, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide() || self instanceof ServerPlayer) {
            return;
        }
        LivingEntity killer = self.getKillCredit();
        if (killer instanceof ServerPlayer killerPlayer) {
            String victimId = BuiltInRegistries.ENTITY_TYPE.getKey(self.getType()).toString();
            MetricHooks.record(killerPlayer, Metric.KILLS, 1L, victimId);
        }
    }
}
