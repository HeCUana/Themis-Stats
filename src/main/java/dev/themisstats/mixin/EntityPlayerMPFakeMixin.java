package dev.themisstats.mixin;

import carpet.patches.EntityPlayerMPFake;
import dev.themisstats.ThemisStatsRuntime;
import dev.themisstats.session.SessionEndReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Observes the lifecycle of a Carpet fake so that the session end reason is accurate.
 *
 * <p>Carpet implements the fake lifecycle in three clearly separated places, and this mod reads
 * them instead of guessing from a disconnect message:</p>
 * <ul>
 *   <li>{@code die(DamageSource)} - the fake really died;</li>
 *   <li>{@code kill(ServerLevel)} - the fake was removed by an operator command
 *       ({@code /player kill}); Carpet does not run a death for it;</li>
 *   <li>{@code createShadow(...)} - the real player is replaced by their shadow, so the human
 *       session ends because the identity changed.</li>
 * </ul>
 *
 * <p>When none of these is observed, the session ends as "offline, reason unknown" - the mod
 * never claims that an administrator did something it could not see.</p>
 */
@Mixin(EntityPlayerMPFake.class)
public abstract class EntityPlayerMPFakeMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void themisstats$onFakeDeath(DamageSource cause, CallbackInfo ci) {
        var services = ThemisStatsRuntime.services();
        if (services != null) {
            ServerPlayer self = (ServerPlayer) (Object) this;
            services.sessions().markDeath(self);
            services.sessions().markEndReason(self, SessionEndReason.DEATH);
        }
    }

    @Inject(method = "kill(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"))
    private void themisstats$onFakeRemoved(ServerLevel level, CallbackInfo ci) {
        var services = ThemisStatsRuntime.services();
        if (services != null) {
            services.sessions().markEndReason((ServerPlayer) (Object) this, SessionEndReason.ADMIN_REMOVAL);
        }
    }

    @Inject(method = "createShadow", at = @At("HEAD"))
    private static void themisstats$onShadowCreated(MinecraftServer server, ServerPlayer player,
                                                     CallbackInfoReturnable<EntityPlayerMPFake> cir) {
        var services = ThemisStatsRuntime.services();
        if (services != null) {
            services.sessions().markEndReason(player, SessionEndReason.IDENTITY_CHANGED);
        }
    }
}
