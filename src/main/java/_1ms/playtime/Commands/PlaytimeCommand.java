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
import _1ms.playtime.Main;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
                switch (args[0].toLowerCase()) {
                    case "add" -> {//pt add %player% %time%
                        long val;
                        long beforePt;
                        Long cachedPlaytime = main.playtimeCache.get(targetKey);
                        if(cachedPlaytime != null) {//In cache
                            beforePt = cachedPlaytime;
                            val = beforePt+num;
                            main.playtimeCache.replace(targetKey, val);
                            if(main.getPlayerByDataKey(targetKey).isEmpty()) //Save if player ain't on.
                                main.savePt(targetKey, targetName, val);
                        } else {
                            beforePt = main.getSavedPt(targetKey);//Config if not
                            if(beforePt == -1) {
                                sender.sendMessage(configHandler.getNO_PLAYER());
                                return;
                            }
                            val = beforePt+num;
                            main.savePt(targetKey, targetName, val);
                            if(shouldCache(val)) { //Put in cache if the new PT can enter the current toplist, the clearer will kill it later.
                                main.playtimeCache.put(targetKey, val);
                            }
                        }
                        checkRewards(targetName, beforePt, val);
                        sendModMsg(val, sender, targetName);
                    }
                    case "sub" -> {
                        long val;
                        Long cachedPlaytime = main.playtimeCache.get(targetKey);
                        if(cachedPlaytime != null) {//In cache
                            val = cachedPlaytime-num;
                            if(val < 0){
                                sender.sendMessage(configHandler.getINVALID_VALUE());
                                return;
                            }
                            handleCache(targetKey, val);
                            if(main.getPlayerByDataKey(targetKey).isEmpty())
                                main.savePt(targetKey, targetName, val);
                        } else {
                            final long pt = main.getSavedPt(targetKey);//Config if not
                            if(pt == -1) {
                                sender.sendMessage(configHandler.getNO_PLAYER());
                                return;
                            }
                            val = pt-num;
                            if(val < 0){
                                sender.sendMessage(configHandler.getINVALID_VALUE());
                                return;
                            }
                            main.savePt(targetKey, targetName, val);
                        }
                        sendModMsg(val, sender, targetName);
                    }
                    case "set" -> {
                        long beforePt;
                        Long cachedPlaytime = main.playtimeCache.get(targetKey);
                        if(cachedPlaytime != null) {//In cache
                            beforePt = cachedPlaytime;
                            handleCache(targetKey, num);
                            if(main.getPlayerByDataKey(targetKey).isEmpty())
                                main.savePt(targetKey, targetName, num);
                        }
                        else {
                            beforePt = main.getSavedPt(targetKey);
                            if(beforePt == -1)
                                beforePt = 0;
                            main.savePt(targetKey, targetName, num);
                        }
                        if(beforePt < num)
                            checkRewards(targetName, beforePt, num);
                        sendModMsg(num, sender, targetName);
                    }
                    default -> sender.sendMessage(configHandler.getINVALID_ARGS());
                }
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

    public void handleCache(final String pname, final long val) {
        final LinkedHashMap<String, Long> tempMap = main.doSort(null);
        main.playtimeCache.replace(pname, val);
        final Long lowestTopPlaytime = main.getLastValue(tempMap);
        if(lowestTopPlaytime != null && lowestTopPlaytime > val)
            cacheHandler.upd2(pname);
    }

    private boolean shouldCache(long playtime) {
        final var tempMap = cacheHandler.generateTempCache();
        if(tempMap.size() < configHandler.getTOPLIST_LIMIT())
            return true;
        return tempMap.values().stream()
                .min(Long::compare)
                .map(lowestPlaytime -> lowestPlaytime < playtime)
                .orElse(true);
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
