package dev.themisstats.mixin;

import dev.themisstats.metric.Metric;
import dev.themisstats.metric.MetricHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placement: counts one successful solid block placement.
 *
 * <p><b>Why this hook.</b> {@code BlockItem#place(BlockPlaceContext)} is the single point where a
 * player places a solid block. It returns {@code InteractionResult.SUCCESS} only when the block was
 * really placed, so a failed or rejected placement does not count.</p>
 *
 * <p><b>What does not count.</b> Piston movement, natural growth and fluid flows are not
 * {@code BlockItem} placements and therefore never reach this method. A bucket of water is a
 * {@code BucketItem}, not a {@code BlockItem}.</p>
 *
 * <p><b>How it is counted.</b> One placement <em>operation</em> is one increment, even when the
 * placed block generates more than one block in the world (a door has two halves, a bed has two
 * parts). The interface therefore says "placements", not "net new blocks".</p>
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void themisstats$afterPlace(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) {
            return;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            // A dispenser or another non-player context: not a player placement.
            return;
        }
        String blockId = BuiltInRegistries.BLOCK.getKey(((BlockItem) (Object) this).getBlock()).toString();
        MetricHooks.record(player, Metric.PLACEMENT, 1L, blockId);
    }
}
