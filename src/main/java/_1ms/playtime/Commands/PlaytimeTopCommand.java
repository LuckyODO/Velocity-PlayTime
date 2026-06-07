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

import _1ms.playtime.Handlers.ConfigHandler;
import _1ms.playtime.Main;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

public class PlaytimeTopCommand implements SimpleCommand {
    private final Main main;
    private final ConfigHandler configHandler;

    public PlaytimeTopCommand(Main main, ConfigHandler configHandler) {
        this.main = main;
        this.configHandler = configHandler;
    }

    @Override
    public void execute(Invocation invocation) { //do cooldown
        CommandSource sender = invocation.source();
        if (configHandler.isVIEW_TOPLIST() && !invocation.source().hasPermission("vpt.gettoplist")) {
            sender.sendMessage(configHandler.getNO_PERMISSION());
            return;
        }
        if(invocation.arguments().length > 0) {
            sender.sendMessage(configHandler.getINVALID_ARGS());
            return;
        }
        if(main.checkSpam(true, sender))
            return;
        main.doSort(invocation);
    }

    public HashMap<String, Long> getInRuntime() {
        Iterator<String> iterator = main.getIterator();
        final HashMap<String, Long> TempCache = new HashMap<>();
        if(iterator != null) {
            while (iterator.hasNext()) {
                String Pname = iterator.next();
                Optional<Player> player = main.getPlayerByDataKey(Pname);
                if (player.isEmpty()) {
                    long Ptime = main.getSavedPt(Pname);
                    TempCache.put(Pname, Ptime);
                }
                iterator.remove();
            }
        }

        main.playtimeCache.forEach((playerKey, playTime) -> {
            Optional<Player> player = main.getPlayerByDataKey(playerKey);
            player.ifPresent(ignored -> TempCache.put(playerKey, playTime));
        });
        return TempCache.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(configHandler.getTOPLIST_LIMIT())
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
    }
}
