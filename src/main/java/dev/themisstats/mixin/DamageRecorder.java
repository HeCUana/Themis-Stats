package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * The shared damage bookkeeping.
 *
 * <p>Both damage mixins call this: the one on {@link LivingEntity} for every other living entity
 * and the one on {@link Player} for the players, because {@code Player} overrides
 * {@code actuallyHurt} and the override does not call the superclass version.</p>
 */
final class DamageRecorder {

    private DamageRecorder() {
    }

    /**
     * Records the damage that really left the normal health of the victim.
     *
     * @param victim the entity that was hurt
     * @param before the health before the damage
     * @param after  the health after the damage
     * @param source the damage source
     */
    static void record(LivingEntity victim, float before, float after, DamageSource source) {
        if (before <= 0.0F || after >= before) {
            // Nothing was actually taken from the normal health: the damage was absorbed, the
            // death was prevented by a totem, or the target was already dead.
            return;
        }
        float lost = before - after;
        String damageType = source.getMsgId();
        if (victim instanceof ServerPlayer serverVictim) {
            MetricHooks.record(serverVictim, Metric.DAMAGE_TAKEN, Math.round(lost), damageType);
        }
        if (source.getEntity() instanceof ServerPlayer attacker) {
            String victimType = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
            MetricHooks.record(attacker, Metric.DAMAGE_DEALT, Math.round(lost), victimType);
        }
    }
}
