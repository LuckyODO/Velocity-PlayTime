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

package _1ms.playtime.Handlers;

import _1ms.playtime.Main;
import lombok.Getter;

import java.util.*;
import java.util.stream.Collectors;

public class CacheHandler {
    private final Main main;
    private final ConfigHandler configHandler;
    @Getter
    private long cacheGenTime;

    public CacheHandler(Main main, ConfigHandler configHandler) {
        this.main = main;
        this.configHandler = configHandler;
    }

    public void buildCache() {
        long start = System.currentTimeMillis();
        updateCache();
        cacheGenTime = System.currentTimeMillis() - start + configHandler.getGenTime();
        main.getLogger().info("The cache has been built, took: {} ms", cacheGenTime);
    }

    public HashMap<String, Long> generateTempCache() {
        return new HashMap<>(main.playtimeCache);
    }

    public void updateCache() {
        synchronized(main) {
            refreshCache();
        }
    }

    private void refreshCache() {
        // Read storage first: on failure leave the existing cache intact.
        Map<String, Long> saved = main.getSavedPlaytimes();
        Map<String, Long> candidates = new HashMap<>(saved);
        candidates.putAll(main.playtimeCache);
        candidates.entrySet().stream()
                .sorted(Main.PLAYTIME_ORDER)
                .limit(configHandler.getTOPLIST_LIMIT())
                .forEach(entry -> main.playtimeCache.putIfAbsent(entry.getKey(), entry.getValue()));

        Set<String> leaders = main.playtimeCache.entrySet().stream()
                .sorted(Main.PLAYTIME_ORDER)
                .limit(configHandler.getTOPLIST_LIMIT())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        // Retain the UNION of top N and online players, plus anything not yet persisted.
        main.playtimeCache.forEach((key, time) -> {
            if(!leaders.contains(key)) {
                main.playtimeCache.computeIfPresent(key, (ignored, current) -> {
                    if(main.getPlayerByDataKey(key).isEmpty() && !main.hasPendingSave(key)
                            && Objects.equals(current, saved.get(key)))
                        return null;
                    return current;
                });
            }
        });
    }
}
