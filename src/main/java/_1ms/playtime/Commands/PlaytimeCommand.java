/*      This file is part of the Velocity Playtime project.
        Copyright (C) 2024-2025 _1ms

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <https://www.gnu.org/licenses/>. */

package _1ms.playtime.Commands;

import _1ms.playtime.Handlers.CacheHandler;
import _1ms.playtime.Handlers.ConfigHandler;
import _1ms.playtime.Handlers.StorageException;
import _1ms.playtime.Main;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class PlaytimeCommand implements SimpleCommand {
    private final Main main;
    private final ConfigHandler configHandler;
    private final CacheHandler cacheHandler;

    public PlaytimeCommand(Main main, ConfigHandler configHandler, CacheHandler cacheHandler) {
        this.main = main;
        this.configHandler = configHandler;
        this.cacheHandler = cacheHandler;
    }

    @Override
    public void execute(Invocation invocation) {
        try {
            executeChecked(invocation);
        } catch (StorageException failure) {
            invocation.source().sendMessage(configHandler.getSTORAGE_ERROR());
            main.getLogger().error("Playtime command storage operation failed", failure);
        }
    }

    private void executeChecked(Invocation invocation) {
        final String[] args = invocation.arguments();
        final CommandSource sender = invocation.source();
        if(main.checkSpam(false, sender))
           return;
        switch (args.length) {
            case 0 -> {
                if(!(sender instanceof Player player)) {
                    sender.sendMessage(configHandler.getNO_CONSOLE_USE());
                    return;
                }
                SendYourPlaytime(player);
            }
            case 1 -> {
                if(sender instanceof Player player)
                    if(player.getUsername().equalsIgnoreCase(args[0])) {
                        SendYourPlaytime(player);
                        return;
                    }
                if(configHandler.isVIEW_OTHERS_TIME() && !sender.hasPermission("vpt.getotherstime")) {
                    sender.sendMessage(configHandler.getNO_PERMISSION());
                    return;
                }
                final String targetKey = main.resolveDataKey(args[0]);
                final Long cachedPlaytime = main.playtimeCache.get(targetKey);
                long PlayTime = cachedPlaytime != null ? cachedPlaytime : main.getSavedPt(targetKey);
                if (PlayTime == -1)
                    sender.sendMessage(configHandler.getNO_PLAYER());
                else
                    sender.sendMessage(configHandler.decideNonComponent(configHandler.repL(configHandler.getOTHER_PLAYTIME(), PlayTime).replace("%player%", main.getDisplayName(targetKey)).replace("%place%", String.valueOf(main.getPlace(targetKey)))));
            }
            case 3 -> {
                if(!sender.hasPermission("vpt.modify")) {
                    sender.sendMessage(configHandler.getNO_PERMISSION());
                    return;
                }
                long num;
                try {
                    num = Long.parseLong(args[2]);
                    if(num < 0)
                        throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    sender.sendMessage(configHandler.getINVALID_VALUE());
                    return;
                }
                if(!args[0].equalsIgnoreCase("add") && !args[0].equalsIgnoreCase("sub") && !args[0].equalsIgnoreCase("set")) {
                    sender.sendMessage(configHandler.getINVALID_ARGS());
                    return;
                }
                final String targetKey = main.resolveDataKey(args[1]);
                String targetName = main.getDisplayName(targetKey);
                if(targetName.equals(targetKey))
                    targetName = args[1];
                main.rememberPlayerName(targetKey, targetName);
                final String operation = args[0].toLowerCase(Locale.ROOT);
                final AtomicLong beforePt = new AtomicLong();
                final Long result;
                try {
                    result = main.playtimeCache.compute(targetKey, (key, cached) -> {
                        long previous = cached != null ? cached : main.getSavedPt(key);
                        if(previous == -1 && !operation.equals("set"))
                            return null;
                        previous = Math.max(0, previous);
                        beforePt.set(previous);
                        long value = switch(operation) {
                            case "add" -> Math.addExact(previous, num);
                            case "sub" -> previous - num;
                            default -> num;
                        };
                        if(value < 0)
                            throw new ArithmeticException("Negative playtime");
                        return value;
                    });
                } catch (ArithmeticException invalid) {
                    sender.sendMessage(configHandler.getINVALID_VALUE());
                    return;
                }
                if(result == null) {
                    sender.sendMessage(configHandler.getNO_PLAYER());
                    return;
                }
                // Keep the new value cached on a failed write so autosave can retry it.
                main.saveCachedPlayer(targetKey);
                if(configHandler.isUSE_CACHE())
                    cacheHandler.updateCache();
                if(beforePt.get() < result)
                    checkRewards(targetName, beforePt.get(), result);
                sendModMsg(result, sender, targetName);
            }
            default -> sender.sendMessage(configHandler.getINVALID_ARGS());
        }
    }

    private void checkRewards(String pname, long beforePt, long afterPt) {
        final boolean shouldReward = main.getProxy().getPlayer(pname)
                .map(player -> !player.hasPermission("vpt.rewards.exempt"))
                .orElse(configHandler.isOFFLINES_SHOULD_GET_REWARDS());
        if(!shouldReward)
            return;
        configHandler.getRewardsH().forEach((key, value) -> { //Check rewards on PT ADD
            if(beforePt < key && key <= afterPt)
                scheduleReward(pname, value);
        });
    }

    private void scheduleReward(String pname, String command) {
        main.getProxy().getScheduler().buildTask(main, () ->
                main.getProxy().getCommandManager().executeAsync(main.getProxy().getConsoleCommandSource(), command.replace("%player%", pname))
        ).delay(100, TimeUnit.MILLISECONDS).schedule();
    }

    private void sendModMsg(long val, CommandSource sender, String target) {
        if(sender instanceof Player)//Only send if it isn't console so automation can be done without the msgs.
            sender.sendMessage(configHandler.decideNonComponent(configHandler.getPTSET().replace("%sec%", String.valueOf(val)).replace("%player%", target)));
    }

    public void SendYourPlaytime(Player player) {
        if (configHandler.isVIEW_OWN_TIME() && !player.hasPermission("vpt.getowntime")) {
            player.sendMessage(configHandler.getNO_PERMISSION());
            return;
        }
        final String playerKey = main.getDataKey(player);
        Long PlayTime = main.playtimeCache.get(playerKey);
        if(PlayTime == null) {
            PlayTime = main.getSavedPt(playerKey);
            if(PlayTime == -1)
                PlayTime = 0L;
            main.playtimeCache.putIfAbsent(playerKey, PlayTime);
        }
        player.sendMessage(configHandler.decideNonComponent(configHandler.repL(configHandler.getYOUR_PLAYTIME(), PlayTime).replace("%place%", String.valueOf(main.getPlace(playerKey)))));
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(final Invocation invocation) {
        final CommandSource sender = invocation.source();
        if(configHandler.isVIEW_OTHERS_TIME() && !sender.hasPermission("vpt.getotherstime"))
            return CompletableFuture.completedFuture(new ArrayList<>());
        final List<String> tabargs = new ArrayList<>();
        final String[] target = invocation.arguments();
        if(target.length == 0) {
            for (Player player : main.getProxy().getAllPlayers())
                if (!player.equals(sender))
                    tabargs.add(player.getUsername());
            return CompletableFuture.completedFuture(tabargs);
        }
        if (target.length == 1) {
            // First argument tab completion
            final String prefix = target[0].toLowerCase();
            if(sender.hasPermission("vpt.modify")){
                if ("add".startsWith(prefix)) tabargs.add("add");
                if ("sub".startsWith(prefix)) tabargs.add("sub");
                if ("set".startsWith(prefix)) tabargs.add("set");
            }
            for (Player player : main.getProxy().getAllPlayers())
                if (player.getUsername().toLowerCase().startsWith(prefix))
                    tabargs.add(player.getUsername());
        } else if (target.length == 2 && (target[0].equalsIgnoreCase("add") || target[0].equalsIgnoreCase("sub") || target[0].equalsIgnoreCase("set")) && sender.hasPermission("vpt.modify")) {
            // Second argument tab completion for specific first arguments
            for (Player player : main.getProxy().getAllPlayers())
                if (player.getUsername().toLowerCase().startsWith(target[1].toLowerCase()))
                    tabargs.add(player.getUsername());
        }
        return CompletableFuture.completedFuture(tabargs);
    }
}
