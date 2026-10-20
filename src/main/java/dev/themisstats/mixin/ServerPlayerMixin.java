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
 * Deaths of players, and the kills that go with them.
 *
 * <p><b>Why a separate hook.</b> {@code ServerPlayer#die(DamageSource)} replaces
 * {@code LivingEntity#die(DamageSource)} completely and does not call it. A mixin on
 * {@code LivingEntity#die} therefore never sees a player death, and the death of a player and the
 * kill of that player are counted here instead - exactly once.</p>
 *
 * <p><b>Attribution.</b> The killer is the entity the server credits the kill to
 * ({@code getKillCredit()}), the same value vanilla uses for the kill score.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void themisstats$onPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (self.level().isClientSide()) {
            return;
        }
        // The death of the player itself.
        MetricHooks.record(self, Metric.DEATHS, 1L, damageSource.getMsgId());
        // The kill that goes with it.
        LivingEntity killer = self.getKillCredit();
        if (killer instanceof ServerPlayer killerPlayer) {
            String victimId = BuiltInRegistries.ENTITY_TYPE.getKey(self.getType()).toString();
            MetricHooks.record(killerPlayer, Metric.KILLS, 1L, victimId);
        }
    }
}
