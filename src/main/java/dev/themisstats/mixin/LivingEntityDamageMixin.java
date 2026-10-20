package dev.themisstats.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Damage: counts the damage that was really deducted from the normal health.
 *
 * <p><b>Why the health difference and not the damage request.</b> Vanilla reduces the requested
 * damage by armour and resistances, then spends the absorption hearts before the normal health.
 * Only what is left actually lowers the health bar, and the health cannot go below zero, so an
 * oversized hit does not inflate the statistic. Absorption hearts and a totem of undying therefore
 * never count as damage, and overkill is capped by the health that was actually there.</p>
 *
 * <p>The same value is recorded twice: once as the damage the victim took and once as the damage
 * the attacker dealt. An attacker that cannot be attributed to a player is not recorded at all.</p>
 *
 * <p><b>Scope.</b> This mixin covers every living entity that is <em>not</em> a player. The players
 * are covered by {@link PlayerDamageMixin}, because {@code Player} overrides
 * {@code actuallyHurt} and the override does not call this one.</p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityDamageMixin {

    @Unique
    private float themisstats$healthBefore;

    @Inject(method = "actuallyHurt", at = @At("HEAD"))
    private void themisstats$beforeDamage(ServerLevel level, DamageSource damageSource, float amount,
                                           CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        themisstats$healthBefore = self.getHealth();
    }

    @Inject(method = "actuallyHurt", at = @At("RETURN"))
    private void themisstats$afterDamage(ServerLevel level, DamageSource damageSource, float amount,
                                          CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        float before = themisstats$healthBefore;
        themisstats$healthBefore = 0.0F;
        DamageRecorder.record(self, before, self.getHealth(), damageSource);
    }
}
