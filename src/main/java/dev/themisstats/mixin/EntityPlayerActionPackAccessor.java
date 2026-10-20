package dev.themisstats.mixin;

import carpet.helpers.EntityPlayerActionPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Read only access to the action plan of a Carpet fake.
 *
 * <p>Carpet keeps the configured actions in a private map. The mod observes them through this
 * accessor instead of guessing field names by reflection, and it only ever reads them: the
 * observer never starts, stops or changes an action.</p>
 */
@Mixin(EntityPlayerActionPack.class)
public interface EntityPlayerActionPackAccessor {

    @Accessor("actions")
    Map<EntityPlayerActionPack.ActionType, EntityPlayerActionPack.Action> themisstats$actions();
}
