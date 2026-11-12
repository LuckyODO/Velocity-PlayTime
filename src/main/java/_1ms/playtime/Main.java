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

package _1ms.playtime;

import _1ms.BuildConstants;
import _1ms.playtime.Commands.ConfigReload;
import _1ms.playtime.Commands.PlaytimeCommand;
import _1ms.playtime.Commands.PlaytimeResetAll;
import _1ms.playtime.Commands.PlaytimeTopCommand;
import _1ms.playtime.Handlers.CacheHandler;
import _1ms.playtime.Handlers.ConfigHandler;
import _1ms.playtime.Handlers.MySQLHandler;
import _1ms.playtime.Handlers.UpdateHandler;
import _1ms.playtime.Handlers.StorageException;
import _1ms.playtime.Listeners.PlaytimeEvents;
import _1ms.playtime.Listeners.RequestHandler;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import lombok.Getter;
import org.bstats.charts.SimplePie;
import org.bstats.velocity.Metrics;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.nio.file.Path;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "velocityplaytime",
        name = "VelocityPlaytime",
        version = BuildConstants.VERSION,
        authors = "_1ms",
        description = "Playtime logger for velocity"
)
@SuppressWarnings("unused")
public class Main {
    public ConfigHandler configHandler;
    public PlaytimeCommand playtimeCommand;
    public CacheHandler cacheHandler;
    public PlaytimeEvents playtimeEvents;
    public PlaytimeTopCommand playtimeTopCommand;
    public RequestHandler requestHandler;
    public ConfigReload configReload;
    public UpdateHandler updateHandler;
    public PlaytimeResetAll playtimeResetAll;
    public MySQLHandler mySQLHandler;
    public final MinecraftChannelIdentifier MCI = MinecraftChannelIdentifier.from("velocity:playtime");

    private final ConcurrentHashMap<String, SpamData> spamH = new ConcurrentHashMap<>();
    public record SpamData(boolean isTop, long time){}

    public void InitInstances() {
        configHandler = new ConfigHandler(this);
        mySQLHandler = new MySQLHandler(configHandler, this);
        cacheHandler = new CacheHandler(this, configHandler);
        playtimeCommand = new PlaytimeCommand(this, configHandler, cacheHandler);
        playtimeEvents = new PlaytimeEvents(this, configHandler);
        playtimeTopCommand = new PlaytimeTopCommand(this, configHandler);
        requestHandler = new RequestHandler(this, playtimeTopCommand, configHandler);
        configReload = new ConfigReload(configHandler);
        updateHandler = new UpdateHandler(this);
        playtimeResetAll = new PlaytimeResetAll(this, configHandler);
    }

    public final ConcurrentHashMap<String, Long> playtimeCache = new ConcurrentHashMap<>();
    private final Map<String, String> knownNames = new ConcurrentHashMap<>();
    private final Set<String> pendingSaves = ConcurrentHashMap.newKeySet();
    private final List<ScheduledTask> maintenanceTasks = new ArrayList<>();
    private boolean stopping;
    public static final Comparator<Map.Entry<String, Long>> PLAYTIME_ORDER =
            Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey);

    @Getter
    private final Logger logger;

    @Getter
    private final ProxyServer proxy;

    private final Metrics.Factory metricsFactory;

    @Inject
    public Main(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory, Metrics.Factory metricsFactory) {
        this.proxy = proxy;
        this.logger = logger;
        this.metricsFactory = metricsFactory;
        InitInstances();

        configHandler.initConfig(dataDirectory);
    }

    public boolean loadDB() {
        logger.info("Connecting to the database...");
        boolean connected = mySQLHandler.openConnection();
        if(connected)
            logger.info("Successfully connected to the database.");
        return connected;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
//        if(updateHandler.checkForUpdates())
//            return;
        if(configHandler.isDATABASE() && !loadDB()) {//Check for db here and connect, return if it failed to conn.
            logger.error("The plugin couldn't load.");
            return;
        }
        if(configHandler.isCHECK_FOR_UPDATES())//Using a virtual thread is likely better than velocity's scheduler for a little upd check?
            Thread.ofVirtual().start(updateHandler::checkForUpdates);

        proxy.getChannelRegistrar().register(MCI);
        if(configHandler.isUSE_CACHE()) {
            cacheHandler.buildCache();
            maintenanceTasks.add(proxy.getScheduler().buildTask(this, () -> {
                try {
                    cacheHandler.updateCache();
                } catch (RuntimeException e) {
                    logger.error("Could not refresh the playtime cache; retaining existing data", e);
                }
                spamH.entrySet().removeIf(entry -> (System.currentTimeMillis() - entry.getValue().time()) > configHandler.getSPAM_LIMIT() + 5000); //Clear hashmap of unneeded values
            }).repeat(configHandler.getCACHE_UPDATE_INTERVAL(), TimeUnit.MILLISECONDS).schedule());
        }

        maintenanceTasks.add(proxy.getScheduler().buildTask(this, this::autosave)
                .delay(configHandler.getAUTOSAVE_INTERVAL(), TimeUnit.MILLISECONDS)
                .repeat(configHandler.getAUTOSAVE_INTERVAL(), TimeUnit.MILLISECONDS).schedule());

        if(configHandler.isBSTATS()) {
            final Metrics metrics = metricsFactory.make(this, 22432);

            metrics.addCustomChart(new SimplePie("uses_cache", () -> configHandler.isUSE_CACHE() ? "true" : "false"));
            metrics.addCustomChart(new SimplePie("perms_usage", () -> configHandler.getPermsUsageCount()));
            metrics.addCustomChart(new SimplePie("cachegen_time", () -> cacheHandler.getCacheGenTime() + " ms"));
            metrics.addCustomChart(new SimplePie("cacheupdate_interval", () -> String.valueOf(configHandler.getCACHE_UPDATE_INTERVAL())));
            metrics.addCustomChart(new SimplePie("autoupdater", () -> String.valueOf(configHandler.isBSTATS())));
            metrics.addCustomChart(new SimplePie("toplistLimit", () -> String.valueOf(configHandler.getTOPLIST_LIMIT())));
            metrics.addCustomChart(new SimplePie("cfgserializer", () -> String.valueOf(configHandler.isMinimessage())));
            metrics.addCustomChart(new SimplePie("data_method", () -> configHandler.isDATABASE() ? "database" : "ymlfile"));
            metrics.addCustomChart(new SimplePie("anti_spam", () -> String.valueOf(configHandler.getSPAM_LIMIT())));
            metrics.addCustomChart(new SimplePie("rewards", () -> String.valueOf(configHandler.getRewardsH().size())));
            metrics.addCustomChart(new SimplePie("preload", () -> String.valueOf(configHandler.isPRELOAD_PLACEHOLDERS())));
            metrics.addCustomChart(new SimplePie("offlinerewards", () -> String.valueOf(configHandler.isOFFLINES_SHOULD_GET_REWARDS())));
        }
//Register events
        proxy.getEventManager().register(this, playtimeEvents);
        proxy.getEventManager().register(this, requestHandler);
//Start counting PT
        maintenanceTasks.add(proxy.getScheduler()
                .buildTask(this, this::countPT)
                .repeat(1L, TimeUnit.SECONDS)
                .schedule());
//Register cmds
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder(configHandler.getPTN())
                .aliases(configHandler.getPTA())
                .plugin(this)
                .build();
        SimpleCommand simpleCommand = playtimeCommand;
        commandManager.register(commandMeta, simpleCommand);

        CommandMeta commandMeta2 = commandManager.metaBuilder(configHandler.getPTTN())
                .aliases(configHandler.getPTTA())
                .plugin(this)
                .build();
        SimpleCommand simpleCommand2 = playtimeTopCommand;
        commandManager.register(commandMeta2, simpleCommand2);

        CommandMeta commandMeta3 = commandManager.metaBuilder(configHandler.getPTRLN())
                .aliases(configHandler.getPTRLA())
                .plugin(this)
                .build();
        SimpleCommand simpleCommand3 = configReload;
        commandManager.register(commandMeta3, simpleCommand3);

        CommandMeta commandMeta4 = commandManager.metaBuilder(configHandler.getPTRAN())
                .aliases(configHandler.getPTRAA())
                .plugin(this)
                .build();
        SimpleCommand simpleCommand4 = playtimeResetAll;
        commandManager.register(commandMeta4, simpleCommand4);
//Send restart packet to PlaytimeLink.
        requestHandler.sendRS();

        logger.info("Velocity PlayTime Loaded.");
    }

    void countPT() {//Playtime counting happens here.
        final String[] excludedServers = configHandler.getExcludedSrvs();
        final NavigableMap<Long, String> rewards = configHandler.getRewardsH();
        proxy.getAllPlayers().forEach(p->{
            String key = getDataKey(p);
            String pname = p.getUsername();
            if(isOnExcludedServer(p, excludedServers))
                return;
            Long newPt = playtimeCache.computeIfPresent(key, (ignored, pt) -> pt + 1L);
            if(newPt == null)
                return;
            rewards.forEach((rewardTime, val) -> { //And rewards
                try {
                    if(Objects.equals(rewardTime, newPt) && !p.hasPermission("vpt.rewards.exempt"))
                        proxy.getCommandManager().executeAsync(proxy.getConsoleCommandSource(), val.replace("%player%", pname));
                } catch (Exception ignored) {}
            });
        });
    }

    private boolean isOnExcludedServer(Player player, String[] excludedServers) {
        final var currentServer = player.getCurrentServer();
        if(currentServer.isEmpty())
            return false;
        final String serverName = currentServer.get().getServerInfo().getName();
        for(String excludedServer : excludedServers) {
            if(Objects.equals(serverName, excludedServer))
                return true;
        }
        return false;
    }

    public CompletableFuture<Boolean> checkServerStatus(RegisteredServer server) {
        return server.ping()
                .thenApply(ping -> true)
                .exceptionally(ex -> false);
    }

    @Subscribe
    public synchronized void ProxyShutdownEvent(ProxyShutdownEvent event) {
        stopping = true;
        maintenanceTasks.forEach(ScheduledTask::cancel);
        saveCachedPlaytimes();
        mySQLHandler.closeConnection();
        logger.info("Velocity PlayTime Unloaded.");
    }

    synchronized void autosave() {
        if(stopping)
            return;
        for(Player player : proxy.getAllPlayers()) {
            if(!playtimeCache.containsKey(getDataKey(player))) {
                try {
                    playtimeEvents.loadPlayer(player);
                } catch (RuntimeException failure) {
                    logger.error("Could not reload playtime for {}; keeping it unloaded", player.getUsername(), failure);
                }
            }
        }
        saveCachedPlaytimes();
        knownNames.keySet().removeIf(key -> !playtimeCache.containsKey(key) && getPlayerByDataKey(key).isEmpty());
    }

    public void rememberPlayerName(String key, String name) {
        knownNames.put(key, name);
    }

    public boolean hasPendingSave(String key) {
        return pendingSaves.contains(key);
    }

    public synchronized void saveCachedPlayer(String key) {
        // The main monitor orders every save/reset. Read the latest value only
        // after acquiring it; never hold a cache entry lock during database I/O.
        Long time = playtimeCache.get(key);
        if(time == null)
            return;
        String name = knownNames.get(key);
        savePt(key, name != null ? name : getDisplayName(key), time);
        if(!configHandler.isUSE_CACHE()) {
            playtimeCache.computeIfPresent(key, (ignored, current) -> {
                if(current.equals(time) && getPlayerByDataKey(key).isEmpty()) {
                    knownNames.remove(key);
                    return null;
                }
                return current;
            });
        }
    }

    public synchronized void saveCachedPlaytimes() {
        if(!configHandler.isDATABASE()) {
            Map<String, Long> snapshot = new HashMap<>(playtimeCache);
            if(snapshot.isEmpty())
                return;
            Map<String, String> names = new HashMap<>();
            snapshot.keySet().forEach(key -> names.put(key, knownNames.containsKey(key) ? knownNames.get(key) : getDisplayName(key)));
            pendingSaves.addAll(snapshot.keySet());
            try {
                // One complete YAML write per interval, not one full-file write per player.
                configHandler.savePlaytimesToConfig(snapshot, names);
                pendingSaves.removeAll(snapshot.keySet());
                if(!configHandler.isUSE_CACHE()) {
                    snapshot.forEach((key, saved) -> playtimeCache.computeIfPresent(key, (ignored, current) -> {
                        if(current.equals(saved) && getPlayerByDataKey(key).isEmpty()) {
                            knownNames.remove(key);
                            return null;
                        }
                        return current;
                    }));
                }
            } catch (RuntimeException e) {
                logger.error("Could not autosave playtimes.yml; retaining cached data for retry", e);
            }
            return;
        }
        for(String key : playtimeCache.keySet()) {
            try {
                saveCachedPlayer(key);
            } catch (RuntimeException e) {
                logger.error("Could not save playtime for {}; retaining it for the next save", key, e);
            }
        }
    }

    public List<String> calcTab(CommandSource sender, String[] target) {
        final List<String> tabargs = new ArrayList<>();
        if(target.length == 0) {
            for (Player player : proxy.getAllPlayers()) {
                if (!player.equals(sender))
                    tabargs.add(player.getUsername());
            }
            return tabargs;
        }
        if(target.length == 1) {
            for (Player player : proxy.getAllPlayers()) {
                if (!player.equals(sender) && player.getUsername().toLowerCase().startsWith(target[0].toLowerCase())) {
                    tabargs.add(player.getUsername());
                }
            }
        }
        return tabargs;
    }

    public String getDataKey(Player player) {
        return configHandler.isUSE_UUIDS() ? player.getUniqueId().toString() : player.getUsername();
    }

    public Optional<Player> getPlayerByDataKey(String key) {
        if(configHandler.isUSE_UUIDS()) {
            try {
                return proxy.getPlayer(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {}
        }
        return proxy.getPlayer(key);
    }

    public String resolveDataKey(String playerName) {
        Optional<Player> onlinePlayer = proxy.getPlayer(playerName);
        if(onlinePlayer.isPresent())
            return getDataKey(onlinePlayer.get());
        if(playtimeCache.get(playerName) != null)
            return playerName;
        if(configHandler.isUSE_UUIDS()) {
            final String storedKey = configHandler.isDATABASE() ? mySQLHandler.findKeyByName(playerName) : configHandler.getKeyFromConfigName(playerName);
            if(storedKey != null)
                return storedKey;
        }
        return playerName;
    }

    public String getDisplayName(String key) {
        Optional<Player> onlinePlayer = getPlayerByDataKey(key);
        if(onlinePlayer.isPresent())
            return onlinePlayer.get().getUsername();
        if(!configHandler.isUSE_UUIDS())
            return key;
        return configHandler.isDATABASE() ? mySQLHandler.readName(key) : configHandler.getNameFromConfig(key);
    }

    public long getSavedPt(String name) {
        long time = configHandler.isDATABASE() ? mySQLHandler.readData(name) : configHandler.getPtFromConfig(name);
        if(time < -1)
            throw new StorageException("Invalid saved playtime for " + name, null);
        return time;
    }

    public void savePt(String name, long time) {
        savePt(name, getDisplayName(name), time);
    }

    public void savePt(String name, String playerName, long time) {
        if(time < 0)
            throw new IllegalArgumentException("Playtime cannot be negative");
        pendingSaves.add(name);
        if(configHandler.isDATABASE())
            mySQLHandler.saveData(name, playerName, time);
        else
            configHandler.savePtToConfig(name, playerName, time);
        pendingSaves.remove(name);
    }

    public Map<String, Long> getSavedPlaytimes() {
        return configHandler.isDATABASE() ? mySQLHandler.readAllTimes() : configHandler.getSavedPlaytimes();
    }

    public Map<String, String> getSavedPlayerNames() {
        if(!configHandler.isUSE_UUIDS()) return new HashMap<>();
        Map<String, String> names = configHandler.isDATABASE()
                ? mySQLHandler.readAllNames() : configHandler.getSavedPlayerNames();
        names.putAll(knownNames);
        return names;
    }

    public void savePlayerName(String key, String playerName) {
        if(!configHandler.isUSE_UUIDS())
            return;
        if(configHandler.isDATABASE()) {
            mySQLHandler.saveName(key, playerName);
            return;
        }
        configHandler.saveNameToConfig(key, playerName);
    }

    public long migratePlayerKey(String oldKey, String newKey, String name) {
        return configHandler.isDATABASE() ? mySQLHandler.migratePlayerKey(oldKey, newKey, name)
                : configHandler.migratePlayerKey(oldKey, newKey, name);
    }

    public void removePt(String name) {
        if(configHandler.isDATABASE()) {
            mySQLHandler.deleteData(name);
            return;
        }
        configHandler.removePtFromConfig(name);
    }

    public void removeAllPt() {
        if(configHandler.isDATABASE()) {
            mySQLHandler.deleteAll();
            return;
        }
        configHandler.nullDataConfig();
    }

    public synchronized void resetAllPlaytimes() {
        removeAllPt();
        playtimeCache.replaceAll((key, time) -> 0L);
        playtimeCache.keySet().removeIf(key -> getPlayerByDataKey(key).isEmpty());
        pendingSaves.clear();
        knownNames.keySet().retainAll(playtimeCache.keySet());
    }

    public Iterator<String> getIterator() {
        return configHandler.isDATABASE() ? mySQLHandler.getIterator() : configHandler.getConfigIterator("Player-Data", true);
    }

    public boolean checkSpam(boolean isTop, CommandSource sender) {
        final long currT = System.currentTimeMillis();
        if(!(configHandler.getSPAM_LIMIT() < 1) && sender instanceof Player player && !player.hasPermission("vpt.spam")) {
            final String name = player.getUsername();
            if(spamH.containsKey(name) && spamH.get(name).isTop() == isTop) {
                final long diffT = currT - spamH.get(name).time();
                if(diffT < configHandler.getSPAM_LIMIT()) {
                    final String msg = configHandler.getNO_SPAM().replace("%seconds%", String.valueOf(((configHandler.getSPAM_LIMIT()-diffT)/1000)+1));
                    sender.sendMessage(configHandler.decideNonComponent(msg));
                    return true;
                }
            }
            spamH.put(name, new SpamData(isTop, currT));
        }
        return false;
    }

    public long calculatePlayTime(long rawValue, char v) {
        return switch (v) {
            case 'w' -> rawValue / 604800;
            case 'd' -> (rawValue % 604800) / 86400;
            case 'h' -> ((rawValue % 604800) % 86400) / 3600;
            case 'm' -> (((rawValue % 604800) % 86400) % 3600) / 60;
            case 's' -> ((((rawValue % 604800) % 86400) % 3600) % 60);
            default -> -1;
        };
    }

    public long calcTotalPT(long rawValue, char v) {
        return switch (v) {
            case 'd' -> rawValue / 86400;
            case 'h' -> rawValue / 3600;
            case 'm' -> rawValue / 60;
            case 's' -> rawValue;
            default -> -1;
        };
    }

    public int getPlace(String pName) {//Check if the cache contains it.
        LinkedHashMap<String, Long> tl = doSort(null);
        if(!tl.containsKey(pName))
            tl = requestHandler.getFullTL();
        int i = 0;
        for(String pl : tl.keySet()) {
            i++;
            if(pName.equals(pl))
                return i;
        }
        return -1;
    }

    public LinkedHashMap<String, Long> doSort(@Nullable SimpleCommand.Invocation invocation) {
        final HashMap<String, Long> TempCache = configHandler.isUSE_CACHE() ? cacheHandler.generateTempCache() : playtimeTopCommand.getInRuntime();
        final boolean isForPlaceholder = invocation == null;
        final LinkedHashMap<String, Long> placeholderH  = new LinkedHashMap<>();
        if(!isForPlaceholder)
            invocation.source().sendMessage(configHandler.getTOP_PLAYTIME_HEADER());
        final List<Map.Entry<String, Long>> sortedEntries = TempCache == null ? Collections.emptyList() : TempCache.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(PLAYTIME_ORDER)
                .limit(configHandler.getTOPLIST_LIMIT())
                .toList();
        int place = 1;
        for(Map.Entry<String, Long> Entry : sortedEntries) {
            final long playTime = Entry.getValue();
            if(isForPlaceholder)
                placeholderH.put(Entry.getKey(), playTime);
            else
                invocation.source().sendMessage(configHandler.decideNonComponent(configHandler.repL(configHandler.getTOP_PLAYTIME_LIST(), playTime).replace("%player%", getDisplayName(Entry.getKey())).replace("%place%", String.valueOf(place))));
            place++;
        }
        if(!isForPlaceholder)
            invocation.source().sendMessage(configHandler.getTOP_PLAYTIME_FOOTER());
        return placeholderH;
    }

    public Long getLastValue(LinkedHashMap<String, Long> map) {
        Long lastValue = null;
        for(Long value : map.values())
            lastValue = value;
        return lastValue;
    }

}
