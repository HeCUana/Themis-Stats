package dev.themisstats.mixin;

import dev.themisstats.ThemisStatsRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Elytra distance: samples the player position once per tick.
 *
 * <p>The measurement itself lives in {@code dev.themisstats.metric.ElytraTracker}; this mixin only
 * provides the per tick call.</p>
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void themisstats$afterTick(CallbackInfo ci) {
        var services = ThemisStatsRuntime.services();
        if (services == null) {
            return;
        }
        if ((Object) this instanceof ServerPlayer player) {
            services.elytra().tick(player);
        }
    }
}
