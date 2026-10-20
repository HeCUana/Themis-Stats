package dev.themisstats.permission;

import dev.themisstats.config.RuleState;
import dev.themisstats.identity.Identity;
import dev.themisstats.storage.AccountRepository;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * The single place where the visibility rules are decided.
 *
 * <p>Every command, every button, every completion, every export and every sidebar entry goes
 * through this service, so a rule or permission change immediately applies everywhere. The
 * visibility is always re-checked right before a result is sent, never only when a page was
 * rendered.</p>
 */
public final class PermissionService {

    /** The permission level an administrator needs for the management commands. */
    public static final int ADMIN_LEVEL = 2;

    private final RuleState rules;
    private final AccountRepository accounts;

    public PermissionService(RuleState rules, AccountRepository accounts) {
        this.rules = rules;
        this.accounts = accounts;
    }

    /** @return whether the source is the console or has at least the administrator level */
    public boolean isAdmin(CommandSourceStack source) {
        if (source == null) {
            return false;
        }
        if (source.getServer() != null && source.getEntity() == null) {
            // The console / command block.
            return true;
        }
        return net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(source.permissions());
    }

    /**
     * @return whether the source may query public leaderboards and other players' public totals
     */
    public boolean canQueryPublic(CommandSourceStack source) {
        if (isAdmin(source)) {
            return true;
        }
        return rules.publicQuery();
    }

    /**
     * @return whether the source may see the public overview of an identity group
     */
    public boolean canQueryPublicTotals(CommandSourceStack source, Identity identity) {
        if (isAdmin(source)) {
            return true;
        }
        if (!rules.publicQuery()) {
            return false;
        }
        return switch (identity) {
            case HUMAN -> rules.humanBoard();
            case BOT -> rules.botBoard();
            case SHADOW -> rules.shadowBoard();
            case UNKNOWN -> false;
        };
    }

    /**
     * @return whether the public board of the given identity group is open
     */
    public boolean isBoardOpen(Identity identity) {
        return switch (identity) {
            case HUMAN -> rules.humanBoard();
            case BOT -> rules.botBoard();
            case SHADOW -> rules.shadowBoard();
            case UNKNOWN -> false;
        };
    }

    /**
     * @return whether the source may see the precise position, the equipment and the restricted run
     *         details of the given object
     */
    public boolean canViewBotDetails(CommandSourceStack source, UUID uuid, Identity identity) {
        if (isAdmin(source)) {
            return true;
        }
        if (!identity.botLike()) {
            return false;
        }
        String visibility = rules.botLocations();
        return switch (visibility) {
            case "all" -> true;
            case "ops" -> net.minecraft.commands.Commands.LEVEL_MODERATORS.check(source.permissions());
            case "owners" -> isOwner(source, uuid);
            default -> false;
        };
    }

    /**
     * @return whether the source is the registered owner of the object
     */
    public boolean isOwner(CommandSourceStack source, UUID uuid) {
        if (source == null || source.getEntity() == null) {
            return false;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        return player.getUUID().equals(ownerOf(uuid));
    }

    /** @return the uuid of the registered owner of the object, or {@code null} */
    public UUID ownerOf(UUID uuid) {
        var profile = accounts.botProfile(uuid, Identity.BOT);
        if (profile != null && profile.ownerUuid() != null) {
            try {
                return UUID.fromString(profile.ownerUuid());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        var shadowProfile = accounts.botProfile(uuid, Identity.SHADOW);
        if (shadowProfile != null && shadowProfile.ownerUuid() != null) {
            try {
                return UUID.fromString(shadowProfile.ownerUuid());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * @return whether the source may export or back up the data
     */
    public boolean canExport(CommandSourceStack source) {
        return isAdmin(source);
    }
}
