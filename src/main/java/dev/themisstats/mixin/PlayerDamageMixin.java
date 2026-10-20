package dev.themisstats.mixin;

import dev.themisstats.metric.DamageRecorder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Damage: the same bookkeeping as {@link LivingEntityDamageMixin}, but for the players.
 *
 * <p><b>Why a second mixin is needed.</b> {@code Player} overrides {@code actuallyHurt} and the
 * override does <em>not</em> call the superclass version. An injection into
 * {@code LivingEntity#actuallyHurt} therefore never runs for a player, no matter whether the
 * player is a human or a Carpet fake. Without this mixin the damage of a player would never be
 * counted.</p>
 */
@Mixin(Player.class)
public abstract class PlayerDamageMixin {

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
