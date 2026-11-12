package _1ms.playtime.Commands;

import _1ms.playtime.Main;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import java.util.List;
import java.util.Locale;

public final class PlaytimeRewards implements SimpleCommand {
    private final Main main;
    public PlaytimeRewards(Main main) { this.main = main; }

    @Override public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("vpt.rewards.admin");
    }

    @Override public void execute(Invocation invocation) {
        var source = invocation.source();
        if(!hasPermission(invocation)) {
            source.sendMessage(main.configHandler.getNO_PERMISSION());
            return;
        }
        String[] args = invocation.arguments();
        if(args.length < 1 || args.length > 2 || (!args[0].equalsIgnoreCase("preview") && !args[0].equalsIgnoreCase("backfill"))) {
            source.sendMessage(Component.text("用法：/ptrewards preview [奖励秒数] 或 /ptrewards backfill [奖励秒数]；包含全部离线玩家。"));
            return;
        }
        Long seconds = null;
        try {
            if(args.length == 2) {
                seconds = Long.parseLong(args[1]);
                if(seconds < 0) throw new NumberFormatException();
            }
        } catch(NumberFormatException invalid) {
            source.sendMessage(Component.text("奖励秒数必须为非负整数，例如 7200。"));
            return;
        }
        if(main.rewardService == null) {
            source.sendMessage(Component.text("奖励记录不可用，已停止补发。请检查控制台错误并保留 reward-deliveries.log。"));
            return;
        }
        boolean preview = args[0].equalsIgnoreCase("preview");
        source.sendMessage(Component.text(preview ? "开始预览全部玩家，不执行奖励。" : "开始补发全部达标玩家（包括离线玩家）。"));
        main.rewardService.backfill(seconds, preview, message -> source.sendMessage(Component.text(message)))
                .whenComplete((report, failure) -> {
                    if(failure == null) source.sendMessage(Component.text((preview ? "预览完成：" : "补发完成：") + report.summary()));
                    else {
                        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                        source.sendMessage(Component.text("奖励扫描未完成：" + cause.getMessage() + "。详情见代理端控制台。"));
                        main.getLogger().error("Reward scan failed", failure);
                    }
                });
    }

    @Override public List<String> suggest(Invocation invocation) {
        if(!hasPermission(invocation)) return List.of();
        String[] args = invocation.arguments();
        if(args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("preview", "backfill").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        if(args.length == 2) return main.configHandler.getRewardsH().keySet().stream().map(String::valueOf)
                .filter(s -> s.startsWith(args[1])).toList();
        return List.of();
    }
}
