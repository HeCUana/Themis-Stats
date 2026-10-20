package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement: counts the effective displacement of a player, classified by how it happened.
 *
 * <p>Only the movement types that are actually the player's own movement count: walking, swimming,
 * flying and riding. The types that describe an entity being pushed (piston, shulker box) are not
 * the player's movement and are therefore not counted.</p>
 *
 * <p>The distance is the real displacement of the entity, not the requested one, so a blocked
 * move counts nothing.</p>
 */
@Mixin(Entity.class)
public abstract class EntityMovementMixin {

    @Unique
    private Vec3 themisstats$positionBefore;

    @Inject(method = "move", at = @At("HEAD"))
    private void themisstats$beforeMove(MoverType moverType, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        themisstats$positionBefore = self.position();
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void themisstats$afterMove(MoverType moverType, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        Vec3 before = themisstats$positionBefore;
        themisstats$positionBefore = null;
        if (before == null || !(self instanceof ServerPlayer player)) {
            return;
        }
        if (!isPlayerMovement(moverType)) {
            return;
        }
        double distance = self.position().distanceTo(before);
        if (distance <= 0.0D) {
            return;
        }
        long milliBlocks = Math.round(distance * 1000.0D);
        if (milliBlocks > 0) {
            MetricHooks.record(player, Metric.MOVEMENT, milliBlocks, movementCategory(player));
        }
    }

    /**
     * @return whether the movement type describes the player's own movement
     */
    private static boolean isPlayerMovement(MoverType moverType) {
        // Only the entity's own movement counts. A push by a piston, a shulker box or a player
        // is not the player's movement and is therefore not counted.
        return moverType == MoverType.SELF;
    }

    /**
     * Classifies the movement the way the specification names it.
     */
    private static String movementCategory(ServerPlayer player) {
        if (player.isPassenger()) {
            return "ride";
        }
        if (player.isInWater()) {
            return "swim";
        }
        if (player.onGround()) {
            return "walk";
        }
        return "fly";
    }
}
