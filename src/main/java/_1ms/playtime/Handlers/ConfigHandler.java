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
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.block.implementation.Section;
import dev.dejvokep.boostedyaml.dvs.versioning.BasicVersioning;
import dev.dejvokep.boostedyaml.route.Route;
import dev.dejvokep.boostedyaml.settings.dumper.DumperSettings;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import dev.dejvokep.boostedyaml.settings.loader.LoaderSettings;
import dev.dejvokep.boostedyaml.settings.updater.UpdaterSettings;
import dev.dejvokep.boostedyaml.settings.updater.MergeRule;
import lombok.AccessLevel;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Getter
public class ConfigHandler {//TODO REMOVE LOMBOK maybe
    @Getter(AccessLevel.NONE)
    private final Main main;
    @Getter(AccessLevel.NONE)
    private YamlDocument dataConfig;
    @Getter(AccessLevel.NONE)
    private Path dataFile;
    @Getter(AccessLevel.NONE)
    private byte[] dataRevision;
    @Getter(AccessLevel.NONE)
    private Path configFile;
    @Getter(AccessLevel.NONE)
    private YamlDocument config;

    public ConfigHandler(Main main) {
        this.main = main;
    }

    private Component NO_CONSOLE_USE;
    private String YOUR_PLAYTIME;
    private Component NO_PLAYER;
    private String OTHER_PLAYTIME;
    private Component NO_PERMISSION;
    private Component CONFIG_RELOAD;
    private Component PTRESETALL;
    private Component PTRESETALL_CONFIRM;
    private String PTSET;
    private Component INVALID_ARGS;
    private Component TOP_PLAYTIME_HEADER;
    private String TOP_PLAYTIME_LIST;
    private Component TOP_PLAYTIME_FOOTER;
    private String NO_SPAM;
    private String ADDRESS;
    private String DB_NAME;
    private String USERNAME;
    private String PASSWORD;
    private Component INVALID_VALUE;
    private Component STORAGE_ERROR;
    private String PTN;
    private String[] PTA;
    private String PTTN;
    private String[] PTTA;
    private String PTRLN;
    private String[] PTRLA;
    private String PTRAN;
    private String[] PTRAA;


    private int TOPLIST_LIMIT;
    private int SPAM_LIMIT;
    private int PORT;
    private boolean DATABASE;
    private boolean BSTATS;
    private boolean PRELOAD_PLACEHOLDERS;
    private boolean CHECK_FOR_UPDATES;
    private boolean USE_CACHE;
    private long CACHE_UPDATE_INTERVAL;
    private long AUTOSAVE_INTERVAL;
    private boolean VIEW_OWN_TIME;
    private boolean VIEW_OTHERS_TIME;
    private boolean VIEW_TOPLIST;
    private boolean isDataFileUpToDate;
    private boolean minimessage;
    private long genTime;
    private boolean OFFLINES_SHOULD_GET_REWARDS;
    private boolean USE_UUIDS;

    private final TreeMap<Long, List<String>> rewardsH = new TreeMap<>();
    private String[] excludedSrvs = {};

    public void initConfig(@DataDirectory Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
            configFile = dataDirectory.resolve("config.yml");
            config = loadYaml(configFile, "/config.yml");
            backupConfig();
            validateConfig(config);
            config.update();
            makeNonChanging();
            makeConfigCache();
            saveYaml(config, configFile);

            if(!DATABASE)
                initDataConf(dataDirectory);
        } catch (IOException e) {
            throw new RuntimeException("Config initialize error. ", e); //Need to throw error, otherwise it won't unload.
        }
    }

    private void initDataConf(@DataDirectory Path dataDirectory) throws IOException {
        dataFile = dataDirectory.resolve("playtimes.yml");
        dataRevision = Files.exists(dataFile) ? Files.readAllBytes(dataFile) : null;
        dataConfig = parseData(dataRevision == null ? "file-version: 2\nPlayer-Data: {}\n" : new String(dataRevision, StandardCharsets.UTF_8));
        validatePlayerData(dataConfig);
        backupFile(dataFile);
        // Player data is not a configuration template: do not schema-merge or
        // rewrite existing records merely because a new plugin version starts.
        if(dataRevision == null)
            saveData();
    }

    private YamlDocument loadYaml(Path file, String defaults) throws IOException {
        if(Files.exists(file) && Files.readString(file, StandardCharsets.UTF_8).isBlank())
            throw new StorageException("Existing " + file.getFileName() + " is empty; refusing to replace it with defaults", null);
        // The library's File-based reload leaves its input stream open. Own the
        // streams explicitly so Windows can replace the data file on autosave.
        try(InputStream input = Files.exists(file) ? Files.newInputStream(file) : InputStream.nullInputStream();
            InputStream resource = Objects.requireNonNull(getClass().getResourceAsStream(defaults))) {
            return YamlDocument.create(input, resource,
                    GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(false).setAllowDuplicateKeys(false).build(),
                    DumperSettings.DEFAULT,
                    UpdaterSettings.builder().setVersioning(new BasicVersioning("file-version"))
                            .setAutoSave(false)
                            // User-defined sections such as Rewards and Player-Data must
                            // survive empty/null defaults and future schema upgrades.
                            .setKeepAll(true)
                            .setMergeRule(MergeRule.SECTION_AT_MAPPING, true)
                            .setMergeRule(MergeRule.MAPPING_AT_SECTION, true)
                            .setOptionSorting(UpdaterSettings.OptionSorting.SORT_BY_DEFAULTS).build());
        }
    }

    private void validateConfig(YamlDocument document) {
        if(Boolean.TRUE.equals(document.get("isDataFileUpToDate")))
            throw new StorageException("Legacy millisecond conversion requested. Automatic conversion is disabled because an interrupted previous conversion cannot be detected safely. Verify the original units and migrate offline before setting isDataFileUpToDate to false.", null);
        Object method = document.get("Data.DATA_METHOD");
        if(!"YMLFILE".equals(method) && !"DATABASE".equals(method))
            throw new StorageException("Invalid Data.DATA_METHOD; refusing to select another storage backend", null);
        Object uuids = document.get("Data.USE_UUIDS");
        if(!(uuids instanceof Boolean))
            throw new StorageException("Data.USE_UUIDS must be boolean; original configuration retained", null);
        for(String section : List.of("Data", "Commands", "Messages")) {
            if(!(document.get(section) instanceof Section))
                throw new StorageException("Invalid configuration section: " + section, null);
        }
        validateConfigTypes(document, document.getDefaults(), "");
        readRewards(document);
    }

    private static TreeMap<Long, List<String>> readRewards(YamlDocument document) {
        TreeMap<Long, List<String>> result = new TreeMap<>();
        Object root = document.get("Rewards");
        if(root == null) return result;
        if(!(root instanceof Section section))
            throw new StorageException("Rewards must map seconds to a command or list of commands", null);
        for(Object key : section.getKeys()) {
            long seconds;
            try {
                seconds = Long.parseLong(String.valueOf(key));
                if(seconds < 0 || result.containsKey(seconds)) throw new NumberFormatException();
            } catch(NumberFormatException invalid) {
                throw new StorageException("Invalid or duplicate reward threshold: " + key, invalid);
            }
            Object value = section.get(Route.from(key));
            List<?> entries = value instanceof List<?> list ? list : Collections.singletonList(value);
            List<String> commands = new ArrayList<>();
            for(Object entry : entries) {
                if(!(entry instanceof String command) || command.isBlank() || command.contains("\n") || command.contains("\r"))
                    throw new StorageException("Reward " + key + " must contain nonempty single-line commands", null);
                String normalized = command.strip().replaceFirst("^/", "").strip();
                if(normalized.isEmpty()) throw new StorageException("Reward " + key + " has an empty command", null);
                commands.add(normalized);
            }
            if(commands.isEmpty()) throw new StorageException("Reward " + key + " has no commands", null);
            result.put(seconds, List.copyOf(new LinkedHashSet<>(commands)));
        }
        return result;
    }

    private void validateConfigTypes(Section actual, Section defaults, String prefix) {
        for(Object key : defaults.getKeys()) {
            String path = prefix + key;
            // BasicVersioning persists the version as a numeric string after update.
            if(path.equals("file-version")) continue;
            Object expected = defaults.get(Route.from(key));
            Object value = actual.get(Route.from(key));
            if(expected == null) continue; // Optional values and dynamic Rewards.
            if(expected instanceof Section nested) {
                if(!(value instanceof Section current))
                    throw new StorageException("Invalid configuration section: " + path, null);
                validateConfigTypes(current, nested, path + ".");
            } else if((expected instanceof Boolean && !(value instanceof Boolean))
                    || (expected instanceof String && !(value instanceof String))
                    || (expected instanceof Number && !(value instanceof Byte || value instanceof Short
                            || value instanceof Integer || value instanceof Long))) {
                throw new StorageException("Invalid configuration value type: " + path, null);
            }
        }
    }

    private String getConfStr(final String path) {
        return config.getString(path).replace("\\n", "\n");
    }

    public void makeConfigCache() {
        final long cacheStart = System.currentTimeMillis();
        minimessage = config.getString("Data.CONFIG_SERIALIZER").equals("MINIMESSAGE");
        NO_PERMISSION = initComp("Messages.NO_PERMISSION");
        NO_CONSOLE_USE = initComp("Messages.NO_CONSOLE_USE");
        YOUR_PLAYTIME = getConfStr("Messages.PLAYTIME_YOURS");
        NO_PLAYER = initComp("Messages.NO_PLAYER");
        OTHER_PLAYTIME = getConfStr("Messages.PLAYTIME_OTHERS");
        CONFIG_RELOAD = initComp("Messages.CONFIG_RELOAD");
        PTRESETALL = initComp("Messages.PTRESETALL");
        PTRESETALL_CONFIRM = initComp("Messages.PTRESETALL_CONFIRM");
        PTSET = getConfStr("Messages.PTSET");
        INVALID_ARGS = initComp("Messages.INVALID_ARGS");
        TOP_PLAYTIME_HEADER = initComp("Messages.TOP_PLAYTIME_HEADER");
        TOP_PLAYTIME_LIST = getConfStr("Messages.TOP_PLAYTIME_VALUES");
        TOP_PLAYTIME_FOOTER = initComp("Messages.TOP_PLAYTIME_FOOTER");
        NO_SPAM = getConfStr("Messages.NO_SPAM");
        SPAM_LIMIT = config.getInt("Data.SPAM_LIMIT");
        INVALID_VALUE = initComp("Messages.INVALID_VALUE");
        STORAGE_ERROR = initComp("Messages.STORAGE_ERROR");

        VIEW_OWN_TIME = config.getBoolean("Data.PERMISSIONS.VIEW_OWN_TIME");
        VIEW_OTHERS_TIME = config.getBoolean("Data.PERMISSIONS.VIEW_OTHERS_TIME");
        VIEW_TOPLIST = config.getBoolean("Data.PERMISSIONS.VIEW_TOPLIST");
        if(!USE_CACHE)
            TOPLIST_LIMIT = Math.max(1, config.getInt("Data.TOPLIST_LIMIT"));
        //Rewards.
        OFFLINES_SHOULD_GET_REWARDS = config.getBoolean("Data.OFFLINES_SHOULD_GET_REWARDS");

        final TreeMap<Long, List<String>> rewards = readRewards(config);
        synchronized (rewardsH) {
            rewardsH.clear();
            rewardsH.putAll(rewards);
        }
        genTime = System.currentTimeMillis() - cacheStart;
        excludedSrvs = splitConfigList(config.getString("Data.EXCLUDED_SERVERS"));

    }

    public void makeNonChanging() {
        USE_CACHE = config.getBoolean("Data.CACHING.USE_CACHE");
        CACHE_UPDATE_INTERVAL = Math.max(1000L, config.getLong("Data.CACHING.CACHE_UPDATE_INTERVAL"));
        AUTOSAVE_INTERVAL = Math.max(1000L, config.getLong("Data.AUTOSAVE_INTERVAL"));
        BSTATS = config.getBoolean("Data.BSTATS");
        CHECK_FOR_UPDATES = config.getBoolean("Data.CHECK_FOR_UPDATES");
        isDataFileUpToDate = config.getBoolean("isDataFileUpToDate");
        DATABASE = config.getString("Data.DATA_METHOD").equals("DATABASE");
        if(DATABASE) {
            ADDRESS = config.getString("Data.DATABASE.ADDRESS");
            PORT  = config.getInt("Data.DATABASE.PORT");
            DB_NAME  = config.getString("Data.DATABASE.DB_NAME");
            USERNAME = config.getString("Data.DATABASE.USERNAME");
            final String cfg = config.getString("Data.DATABASE.PASSWORD");
            PASSWORD = cfg == null ? "" : cfg;
        }
        USE_UUIDS = config.getBoolean("Data.USE_UUIDS");
        PRELOAD_PLACEHOLDERS = config.getBoolean("Data.PRELOAD_PLACEHOLDERS");
        if(USE_CACHE)
            TOPLIST_LIMIT = Math.max(1, config.getInt("Data.TOPLIST_LIMIT"));//CMDS, non reloadable as they are only registered once.
        PTN = config.getString("Commands.playtime.MAIN_CMD");
        PTA = splitConfigList(config.getString("Commands.playtime.ALIASES"));
        PTTN = config.getString("Commands.playtimetop.MAIN_CMD");
        PTTA = splitConfigList(config.getString("Commands.playtimetop.ALIASES"));
        PTRLN = config.getString("Commands.playtimereload.MAIN_CMD");
        PTRLA = splitConfigList(config.getString("Commands.playtimereload.ALIASES"));
        PTRAN = config.getString("Commands.playtimeresetall.MAIN_CMD");
        PTRAA = splitConfigList(config.getString("Commands.playtimeresetall.ALIASES"));
    }

    private String[] splitConfigList(String value) {
        if(value == null || value.isBlank())
            return new String[0];
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .toArray(String[]::new);
    }

    public synchronized Iterator<String> getConfigIterator(String path, boolean isData) {
        final Section section = isData ? dataConfig.getSection(path) : config.getSection(path);
        if(section == null) {
            return Collections.emptyIterator();
        } //Convert the config lib's Set<Object> into Iterator<String>
        return section.getKeys().stream().map(String::valueOf).collect(Collectors.toSet()).iterator();
    }

    public String getPermsUsageCount() {
        int i = 0;
        final String basePath = "Data.PERMISSIONS";
        Iterator<String> iterator = getConfigIterator(basePath, false);
        while (iterator.hasNext()) {
            String path = iterator.next();
            if(config.getBoolean(basePath + "." + path))
                i++;
            iterator.remove();
        }
        return String.valueOf(i);
    }

    public synchronized long getPtFromConfig(String key) {
        // Older releases used `set(key, null)` as deletion and left null tombstones.
        if(dataConfig.get(Route.from("Player-Data", key)) == null)
            return -1;
        return readTime(dataConfig, key);
    }

    private static long readTime(YamlDocument document, String key) {
        Object value = document.get(Route.from("Player-Data", key, "playtime"));
        if(!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0)
            throw new StorageException("Invalid or missing playtime for " + key + "; original data retained", null);
        return ((Number) value).longValue();
    }

    private static void validatePlayerData(YamlDocument document) {
        Object version = document.get("file-version");
        if(version != null && !Set.of("1", "2").contains(String.valueOf(version)))
            throw new StorageException("Unsupported player data version; refusing to rewrite a different schema", null);
        if(!document.getKeys().contains("Player-Data"))
            throw new StorageException("Missing Player-Data section; refusing to replace existing records", null);
        Object root = document.get("Player-Data");
        if(root == null) return;
        if(!(root instanceof Section section))
            throw new StorageException("Player-Data must be a mapping; original data retained", null);
        for(Object key : section.getKeys()) {
            if(!(key instanceof String))
                throw new StorageException("Player identifiers must be quoted strings", null);
            if(section.get(Route.from(key)) == null) continue;
            readTime(document, (String) key);
        }
    }

    private YamlDocument parseData(String text) throws IOException {
        try(InputStream input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))) {
            return YamlDocument.create(input, GeneralSettings.DEFAULT,
                    LoaderSettings.builder().setAutoUpdate(false).setAllowDuplicateKeys(false).build(),
                    DumperSettings.DEFAULT);
        }
    }

    private void mutateData(Consumer<YamlDocument> mutation) {
        try {
            YamlDocument candidate = parseData(dataConfig.dump());
            mutation.accept(candidate);
            validatePlayerData(candidate);
            saveYaml(candidate, dataFile);
            dataConfig = candidate;
        } catch(IOException e) {
            throw new StorageException("Could not prepare playtime write", e);
        }
    }

    public synchronized Map<String, Long> getSavedPlaytimes() {
        Map<String, Long> times = new HashMap<>();
        getConfigIterator("Player-Data", true).forEachRemaining(key -> {
            long time = getPtFromConfig(key);
            if(time >= 0)
                times.put(key, time);
        });
        return times;
    }

    public synchronized void savePtToConfig(String key, String playerName, long time) {
        mutateData(candidate -> setPlayer(candidate, key, playerName, time));
    }

    private void setPlayer(YamlDocument document, String key, String name, long time) {
        if(time < 0) throw new IllegalArgumentException("Negative playtime");
        document.set(Route.from("Player-Data", key, "playtime"), time);
        if(name != null && !name.isBlank())
            document.set(Route.from("Player-Data", key, "name"), name);
    }

    public synchronized void savePlaytimesToConfig(Map<String, Long> times, Map<String, String> names) {
        mutateData(candidate -> times.forEach((key, time) -> setPlayer(candidate, key, names.get(key), time)));
    }

    public synchronized void saveNameToConfig(String key, String playerName) {
        if(playerName == null || playerName.isBlank()) return;
        mutateData(candidate -> {
            if(candidate.get(Route.from("Player-Data", key)) == null)
                setPlayer(candidate, key, playerName, 0);
            else candidate.set(Route.from("Player-Data", key, "name"), playerName);
        });
    }

    public synchronized long migratePlayerKey(String oldKey, String newKey, String name) {
        long existing = getPtFromConfig(newKey);
        if(existing != -1) return existing;
        long time = getPtFromConfig(oldKey);
        if(time == -1) return -1;
        mutateData(candidate -> {
            candidate.move(Route.from("Player-Data", oldKey), Route.from("Player-Data", newKey));
            candidate.set(Route.from("Player-Data", newKey, "name"), name);
        });
        return time;
    }

    public synchronized String getNameFromConfig(String key) {
        final String playerName = dataConfig.getString(Route.from("Player-Data", key, "name"));
        return playerName == null || playerName.isBlank() ? key : playerName;
    }

    public synchronized Map<String, String> getSavedPlayerNames() {
        Map<String, String> names = new HashMap<>();
        getConfigIterator("Player-Data", true).forEachRemaining(key -> names.put(key, getNameFromConfig(key)));
        return names;
    }

    public synchronized String getKeyFromConfigName(String playerName) {
        Iterator<String> iterator = getConfigIterator("Player-Data", true);
        while(iterator.hasNext()) {
            final String key = iterator.next();
            if(getNameFromConfig(key).equalsIgnoreCase(playerName))
                return key;
        }
        return null;
    }

    public synchronized void removePtFromConfig(String key) {
        mutateData(candidate -> candidate.remove(Route.from("Player-Data", key)));
    }

    private void saveData() {
        saveYaml(dataConfig, dataFile);
    }

    private void backupConfig() throws IOException {
        backupFile(configFile);
    }

    private void backupFile(Path file) throws IOException {
        Path backup = file.resolveSibling(file.getFileName() + ".before-1.10.4.bak");
        if(Files.exists(file) && !Files.exists(backup)) {
            Files.copy(file, backup);
            main.getLogger().info("Saved original file backup to {}", backup);
        }
    }

    private void saveYaml(YamlDocument document, Path destination) {
        Path temporary = null;
        try {
            if(destination.equals(dataFile)) {
                byte[] current = Files.exists(dataFile) ? Files.readAllBytes(dataFile) : null;
                if(!Arrays.equals(current, dataRevision))
                    throw new StorageException("playtimes.yml changed outside the plugin; refusing to overwrite it. Stop the proxy and reconcile the file before restarting.", null);
            }
            String serialized = document.dump();
            // Both configuration migration and autosave must preserve the live file
            // if serialization or writing fails.
            temporary = Files.createTempFile(destination.getParent(), destination.getFileName() + "-", ".tmp");
            try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(serialized);
                while(bytes.hasRemaining())
                    channel.write(bytes);
                channel.force(true);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic file replacement is unavailable; refusing an unsafe overwrite of " + destination.getFileName(), unsupported);
            }
            if(destination.equals(dataFile))
                dataRevision = serialized.getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new StorageException("Could not save " + destination.getFileName(), ex);
        } finally {
            if(temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException cleanupFailure) {
                    main.getLogger().warn("Could not remove temporary playtime file {}", temporary, cleanupFailure);
                }
            }
        }
    }

    public synchronized void modifyMainConfig(String path, Object value) {
        try {
            backupConfig();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        config.set(path, value);
        saveYaml(config, configFile);
    }

    public synchronized void nullDataConfig() {
        mutateData(candidate -> candidate.set("Player-Data", new LinkedHashMap<>()));
    }

    public synchronized void reloadConfig() {
        try {
            if(!Files.isRegularFile(configFile))
                throw new StorageException("config.yml is missing or unreadable; retaining the active configuration", null);
            YamlDocument reloaded = loadYaml(configFile, "/config.yml");
            validateConfig(reloaded);
            for(String route : List.of("Data.DATA_METHOD", "Data.USE_UUIDS", "Data.DATABASE.ADDRESS",
                    "Data.DATABASE.PORT", "Data.DATABASE.DB_NAME", "Data.DATABASE.USERNAME", "Data.DATABASE.PASSWORD")) {
                if(!Objects.equals(config.get(route), reloaded.get(route)))
                    throw new StorageException("Storage/identity configuration changed at " + route + "; restart required. Existing cache remains attached to the original storage.", null);
            }
            backupConfig();
            reloaded.update();
            YamlDocument previous = config;
            config = reloaded;
            try {
                makeConfigCache();
                saveYaml(reloaded, configFile);
            } catch(RuntimeException failure) {
                config = previous;
                makeConfigCache();
                throw failure;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Component initComp(final String path) { //Req from the conf file, only at startup once
        return decideNonComponent(config.getString(path).replace("\\n", "\n"));
    }

    public Component decideNonComponent(String message) {//Use to decide in runtime where replacements are needed.
        return minimessage ? MiniMessage.miniMessage().deserialize(message) : Component.text(message);
    }

    public String repL(String msg, long PlayTime) {
        return msg.replace("%weeks%", String.valueOf(main.calculatePlayTime(PlayTime, 'w')))
                .replace("%days%", String.valueOf(main.calculatePlayTime(PlayTime, 'd')))
                .replace("%hours%", String.valueOf(main.calculatePlayTime(PlayTime, 'h')))
                .replace("%minutes%", String.valueOf(main.calculatePlayTime(PlayTime, 'm')))
                .replace("%seconds%", String.valueOf(main.calculatePlayTime(PlayTime, 's')))
                .replace("%totaldays%", String.valueOf(main.calcTotalPT(PlayTime, 'd')))
                .replace("%totalhours%", String.valueOf(main.calcTotalPT(PlayTime, 'h')))
                .replace("%totalminutes%", String.valueOf(main.calcTotalPT(PlayTime, 'm')))
                .replace("%totalseconds%", String.valueOf(main.calcTotalPT(PlayTime, 's')));
    }

    public NavigableMap<Long, List<String>> getRewardsH() {
        synchronized (rewardsH) {
            return new TreeMap<>(rewardsH);
        }
    }

    public String[] getExcludedSrvs() {
        return excludedSrvs.clone();
    }

}
