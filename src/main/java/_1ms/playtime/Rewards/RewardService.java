package _1ms.playtime.Rewards;

import _1ms.playtime.Main;
import _1ms.playtime.Handlers.StorageException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RewardService implements AutoCloseable {
    public record Target(String key, String name, long seconds) {}
    public enum Result { APPLIED, DISPATCHED, EXISTING, WOULD_APPLY, EXEMPT, UNCERTAIN, FAILED }
    public record Report(int scanned, int eligible, Map<Result, Integer> results) {
        public int count(Result result) { return results.getOrDefault(result, 0); }
        public String summary() {
            return "扫描 " + scanned + " 人，达标 " + eligible + " 人；奖励命令：权限补齐 " + count(Result.APPLIED)
                    + "，已执行 " + count(Result.DISPATCHED) + "，已具备/已记录 " + count(Result.EXISTING)
                    + "，待补 " + count(Result.WOULD_APPLY) + "，豁免 " + count(Result.EXEMPT)
                    + "，结果待核实 " + count(Result.UNCERTAIN) + "，失败 " + count(Result.FAILED);
        }
    }
    @FunctionalInterface
    public interface PermissionAccess {
        Result apply(Target target, PermissionReward reward, boolean preview, boolean resave) throws Exception;
    }

    private final Main main;
    private final RewardLedger ledger;
    private final PermissionAccess permissions;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("playtime-rewards-", 0).factory());
    private final AtomicBoolean batchActive = new AtomicBoolean();
    private volatile boolean closed;

    public RewardService(Main main, Path directory) {
        // Resolve the optional API only when a matching reward is actually used.
        this(main, new RewardLedger(directory.resolve("reward-deliveries.log")),
                (target, reward, preview, resave) -> LuckPermsRewards.apply(target, reward, preview, resave));
    }

    public RewardService(Main main, RewardLedger ledger, PermissionAccess permissions) {
        this.main = main;
        this.ledger = ledger;
        this.permissions = permissions;
    }

    public CompletableFuture<Report> backfill(Long threshold, boolean preview, Consumer<String> progress) {
        if(closed) return CompletableFuture.failedFuture(new IllegalStateException("Reward service is stopping"));
        if(!batchActive.compareAndSet(false, true))
            return CompletableFuture.failedFuture(new IllegalStateException("已有奖励扫描正在运行，请等待完成"));
        final NavigableMap<Long, List<String>> rules = main.configHandler.getRewardsH();
        if(threshold != null) {
            if(!rules.containsKey(threshold)) {
                batchActive.set(false);
                return CompletableFuture.failedFuture(new IllegalArgumentException("没有配置 " + threshold + " 秒的奖励"));
            }
            rules.keySet().removeIf(key -> !key.equals(threshold));
        }
        try {
            return CompletableFuture.supplyAsync(() -> scan(rules, preview, progress), worker)
                    .whenComplete((report, failure) -> batchActive.set(false));
        } catch(RejectedExecutionException failure) {
            batchActive.set(false);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private Report scan(NavigableMap<Long, List<String>> rules, boolean preview, Consumer<String> progress) {
        if(rules.isEmpty()) return new Report(0, 0, Map.of());
        // Full storage snapshot, then current cache. No leaderboard limit and no
        // dependency on who is currently online or OFFLINES_SHOULD_GET_REWARDS.
        List<Target> targets = main.getRewardTargets();
        EnumMap<Result, Integer> counts = new EnumMap<>(Result.class);
        int eligible = 0;
        int scanned = 0;
        long lastProgress = System.nanoTime();
        for(Target target : targets) {
            if(closed || Thread.currentThread().isInterrupted()) throw new IllegalStateException("奖励扫描已停止；已保存的记录会保留");
            NavigableMap<Long, List<String>> reached = rules.headMap(target.seconds(), true);
            if(!reached.isEmpty()) eligible++;
            for(var entry : reached.entrySet()) {
                for(String command : entry.getValue()) {
                    Result result = deliver(target, entry.getKey(), command, preview);
                    counts.merge(result, 1, Integer::sum);
                }
            }
            scanned++;
            if(System.nanoTime() - lastProgress > TimeUnit.SECONDS.toNanos(5)) {
                progress.accept("已扫描 " + scanned + "/" + targets.size() + " 人");
                lastProgress = System.nanoTime();
            }
        }
        return new Report(scanned, eligible, Map.copyOf(counts));
    }

    public void awardRange(String key, String name, long before, long after) {
        if(closed || after <= before) return;
        var rules = main.configHandler.getRewardsH().subMap(before, false, after, true);
        if(rules.isEmpty()) return;
        try {
            worker.execute(() -> {
                try {
                    if(closed) return;
                    Target target = new Target(key, name, after);
                    for(var entry : rules.entrySet())
                        for(String command : entry.getValue()) deliver(target, entry.getKey(), command, false);
                } catch(RuntimeException failure) {
                    main.getLogger().error("Could not issue rewards for {}; use /ptrewards backfill after fixing the error", key, failure);
                }
            });
        } catch(RejectedExecutionException ignored) { /* Shutdown retains unresolved rewards for explicit backfill. */ }
    }

    private Result deliver(Target target, long seconds, String command, boolean preview) {
        if(closed || Thread.currentThread().isInterrupted()) throw new IllegalStateException("Reward service is stopping");
        if(main.getPlayerByDataKey(target.key()).map(p -> p.hasPermission("vpt.rewards.exempt")).orElse(false))
            return Result.EXEMPT;
        String id = RewardLedger.id(target.key(), seconds, command);
        RewardLedger.State state = ledger.get(id);
        // Carry receipts from name-keyed records through a later UUID migration.
        if(state == null && !target.key().equalsIgnoreCase(target.name()))
            state = ledger.get(RewardLedger.id(target.name(), seconds, command));
        var permission = PermissionReward.parse(command);
        if(permission.isPresent()) {
            try {
                if(preview) return permissions.apply(target, permission.get(), true, false);
                if(state == RewardLedger.State.DONE
                        && permissions.apply(target, permission.get(), true, false) == Result.EXISTING)
                    return Result.EXISTING;
                ledger.put(id, RewardLedger.State.PREPARED);
                Result result = permissions.apply(target, permission.get(), false, state != RewardLedger.State.DONE);
                ledger.put(id, result == Result.EXEMPT ? RewardLedger.State.FAILED : RewardLedger.State.DONE);
                return result;
            } catch(StorageException persistenceFailure) { throw persistenceFailure; }
            catch(Exception | LinkageError failure) {
                main.getLogger().error("LuckPerms reward failed for {} at {}s: {}. It can be retried by backfill.", target.key(), seconds, command, failure);
                return Result.FAILED;
            }
        }
        if(state == RewardLedger.State.DONE) return Result.EXISTING;
        if(state == RewardLedger.State.PREPARED) {
            main.getLogger().warn("Uncertain reward requires manual verification: {} at {}s: {}", target.key(), seconds, command);
            return Result.UNCERTAIN;
        }
        if(preview) return Result.WOULD_APPLY;
        if(command.contains("%player%") && !target.name().matches("[A-Za-z0-9_.-]{1,32}")) {
            main.getLogger().warn("Cannot safely resolve a player name for {}; use %uuid% for UUID-compatible commands", target.key());
            return Result.FAILED;
        }
        String expanded = command.replace("%player%", target.name());
        if(expanded.contains("%uuid%")) {
            try { expanded = expanded.replace("%uuid%", UUID.fromString(target.key()).toString()); }
            catch(IllegalArgumentException missingUuid) { return Result.FAILED; }
        }
        ledger.put(id, RewardLedger.State.PREPARED);
        boolean handled;
        try {
            handled = main.getProxy().getCommandManager().executeAsync(main.getProxy().getConsoleCommandSource(), expanded)
                    .get(30, TimeUnit.SECONDS);
        } catch(Exception failure) {
            main.getLogger().error("Reward command outcome is uncertain for {} at {}s: {}", target.key(), seconds, command, failure);
            return Result.UNCERTAIN;
        }
        // Velocity confirms dispatch, not an arbitrary plugin's business outcome.
        ledger.put(id, handled ? RewardLedger.State.DONE : RewardLedger.State.FAILED);
        return handled ? Result.DISPATCHED : Result.FAILED;
    }

    @Override public void close() {
        closed = true;
        worker.shutdown();
        try {
            if(!worker.awaitTermination(35, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch(InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
