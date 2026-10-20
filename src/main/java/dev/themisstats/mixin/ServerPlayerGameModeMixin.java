package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mining: counts the blocks a player actually broke.
 *
 * <p><b>Why this hook.</b> {@code ServerPlayerGameMode#destroyBlock(BlockPos)} is only reached from
 * the mining path of the client interaction ({@code destroyAndAck}, and the delayed destroy of
 * {@code tick()}). Commands, explosions, pistons and natural growth never go through it, so they
 * are never attributed to a player.</p>
 *
 * <p><b>When it counts.</b> The block state is captured before the call and compared afterwards.
 * The block only counts when it really changed, so a removal that was rejected (for example by a
 * protection mod) and a click on an already empty position do not count. A single behaviour is
 * therefore counted exactly once, even though vanilla can call this method twice for one
 * interaction (the instant-mine path plus the delayed destroy).</p>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerPlayer player;

    @Shadow
    protected ServerLevel level;

    @Unique
    private BlockState themisstats$beforeState;

    @Unique
    private BlockPos themisstats$beforePos;

    @Inject(method = "destroyBlock", at = @At("HEAD"))
    private void themisstats$beforeDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        themisstats$beforePos = pos;
        themisstats$beforeState = level.getBlockState(pos);
    }

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void themisstats$afterDestroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState before = themisstats$beforeState;
        themisstats$beforeState = null;
        themisstats$beforePos = null;
        if (before == null || !Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        BlockState after = level.getBlockState(pos);
        if (after.getBlock() == before.getBlock()) {
            // The block was not actually removed: the break was rejected or the position was
            // already empty. Neither counts.
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();
        MetricHooks.record(player, Metric.MINING, 1L, blockId);
    }
}
