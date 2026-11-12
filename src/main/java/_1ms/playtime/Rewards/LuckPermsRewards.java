package _1ms.playtime.Rewards;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.PermissionNode;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Loaded only for compatible lpv rewards; LuckPerms remains an optional dependency. */
final class LuckPermsRewards {
    private LuckPermsRewards() {}

    static RewardService.Result apply(RewardService.Target target, PermissionReward reward,
                                      boolean preview, boolean resave) throws Exception {
        var api = LuckPermsProvider.get();
        var manager = api.getUserManager();
        UUID uuid;
        try { uuid = UUID.fromString(target.key()); }
        catch(IllegalArgumentException notUuid) {
            uuid = manager.lookupUniqueId(target.name()).get(30, TimeUnit.SECONDS);
        }
        if(uuid == null) throw new IllegalStateException("LuckPerms has no UUID for " + target.name());
        var user = manager.loadUser(uuid).get(30, TimeUnit.SECONDS);
        try {
            if(user.getCachedData().getPermissionData().checkPermission("vpt.rewards.exempt").asBoolean())
                return RewardService.Result.EXEMPT;
            boolean present = user.data().toCollection().stream().anyMatch(node -> node instanceof PermissionNode
                    && node.getKey().equalsIgnoreCase(reward.permission()) && node.getValue() == reward.value()
                    && !node.hasExpiry() && node.getContexts().isEmpty());
            if(preview) return present ? RewardService.Result.EXISTING : RewardService.Result.WOULD_APPLY;
            // Even an in-memory node may be from an earlier failed save. Persist it
            // again when the journal says delivery was not confirmed.
            if(!present) {
                user.data().clear(node -> node instanceof PermissionNode
                        && node.getKey().equalsIgnoreCase(reward.permission())
                        && !node.hasExpiry() && node.getContexts().isEmpty());
                user.data().add(PermissionNode.builder(reward.permission()).value(reward.value()).build());
            }
            if(!present || resave) {
                manager.saveUser(user).get(30, TimeUnit.SECONDS);
                api.getMessagingService().ifPresent(messaging -> messaging.pushUserUpdate(user));
            }
            return present ? RewardService.Result.EXISTING : RewardService.Result.APPLIED;
        } finally {
            manager.cleanupUser(user);
        }
    }
}
