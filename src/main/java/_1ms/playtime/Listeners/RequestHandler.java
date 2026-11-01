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

package _1ms.playtime.Listeners;

import _1ms.playtime.Commands.PlaytimeTopCommand;
import _1ms.playtime.Handlers.ConfigHandler;
import _1ms.playtime.Handlers.LegacyMessages;
import _1ms.playtime.Main;
import _1ms.playtimeprotocol.SnapshotProtocol;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@SuppressWarnings({"UnstableApiUsage", "unused"})
public class RequestHandler {
    private final Main main;
    private final ConfigHandler configHandler;
    private final UUID session = UUID.randomUUID();
    private long sequence;
    private boolean warnedAboutLegacyTopLimit;
    private ScheduledTask task;
    private final Map<RegisteredServer, ScheduledTask> rsTasks = new ConcurrentHashMap<>();
    private final Map<RegisteredServer, Subscription> subscriptions = new HashMap<>();

    private static final class Subscription {
        boolean modern;
        boolean top;
        boolean preload;
        long lastRequest = System.nanoTime();
    }

    public RequestHandler(Main main, PlaytimeTopCommand ignored, ConfigHandler configHandler) {
        this.main = main;
        this.configHandler = configHandler;
    }

    public void sendRS() {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("rs");
        main.getProxy().getAllServers().forEach(server -> {
            AtomicInteger attempts = new AtomicInteger();
            rsTasks.put(server, main.getProxy().getScheduler().buildTask(main, scheduled -> {
                if(!server.getPlayersConnected().isEmpty()) {
                    server.sendPluginMessage(main.MCI, out.toByteArray());
                    if(attempts.incrementAndGet() >= 5) {
                        scheduled.cancel();
                        rsTasks.remove(server);
                    }
                }
            }).repeat(1L, TimeUnit.SECONDS).schedule());
        });
    }

    @Subscribe
    public EventTask onRequest(PluginMessageEvent event) {
        if(!event.getIdentifier().equals(main.MCI))
            return null;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if(!(event.getSource() instanceof ServerConnection connection))
            return null;
        return EventTask.async(() -> {
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()));
                handleRequest(connection.getServer(), in.readUTF());
            } catch(IOException | RuntimeException failure) {
                main.getLogger().warn("Could not handle PlaytimeLink request from {}", connection.getServer().getServerInfo().getName(), failure);
            }
        });
    }

    public synchronized void handleRequest(RegisteredServer server, String request) {
        if(request.equals("cc")) {
            ScheduledTask restart = rsTasks.remove(server);
            if(restart != null) restart.cancel();
            return;
        }
        if(request.equals("hello2")) {
            sendConfig(server, true, configHandler.isPRELOAD_PLACEHOLDERS());
            return;
        }
        if(!Set.of("rpt", "rtl", "rpt2", "rtl2").contains(request))
            return;
        Subscription sub = subscriptions.computeIfAbsent(server, ignored -> new Subscription());
        sub.modern = request.endsWith("2");
        sub.lastRequest = System.nanoTime();
        if(request.startsWith("rtl")) sub.top = true;
        else {
            sub.preload = configHandler.isPRELOAD_PLACEHOLDERS();
            // A fresh plain request may come from a restarted/reloaded backend.
            sub.top = false;
        }
        sendConfig(server, sub.modern, configHandler.isPRELOAD_PLACEHOLDERS());
        if(task == null)
            task = main.getProxy().getScheduler().buildTask(main, this::sendUpdates)
                    .repeat(1L, TimeUnit.SECONDS).schedule();
    }

    public synchronized void sendUpdates() {
        subscriptions.entrySet().removeIf(entry -> entry.getValue().modern
                && System.nanoTime() - entry.getValue().lastRequest > TimeUnit.SECONDS.toNanos(30));
        if(subscriptions.isEmpty()) {
            if(task != null) task.cancel();
            task = null;
            return;
        }
        Map<String, Long> full = null;
        List<byte[]> modernTop = null;
        LegacyMessages.Packet legacyTop = null;
        for(var entry : subscriptions.entrySet()) {
            RegisteredServer server = entry.getKey();
            Subscription sub = entry.getValue();
            if(server.getPlayersConnected().isEmpty()) continue;
            try {
                if(sub.top || sub.preload) {
                    if(full == null) full = getFullDisplayTL();
                    if(sub.top) {
                        if(sub.modern) {
                            if(modernTop == null)
                                modernTop = SnapshotProtocol.encode("ptt2", session, ++sequence, true, full);
                            sendPackets(server, modernTop);
                        } else {
                            if(legacyTop == null) legacyTop = LegacyMessages.encode("ptt", full).getFirst();
                            if(legacyTop.entries() < full.size() && !warnedAboutLegacyTopLimit) {
                                main.getLogger().warn("Legacy PlaytimeLink supports only the first {} of {} ranks. Upgrade PlaytimeLink to 1.7 for complete leaderboards.", legacyTop.entries(), full.size());
                                warnedAboutLegacyTopLimit = true;
                            }
                            server.sendPluginMessage(main.MCI, legacyTop.bytes());
                        }
                    } else {
                        sendPlaytimes(server, sub.modern, true, full);
                    }
                    sub.preload = false;
                    if(sub.top) continue;
                }
                Map<String, Long> online = new LinkedHashMap<>();
                server.getPlayersConnected().forEach(player -> {
                    Long time = main.playtimeCache.get(main.getDataKey(player));
                    if(time != null) online.put(player.getUsername(), time);
                });
                sendPlaytimes(server, sub.modern, !configHandler.isPRELOAD_PLACEHOLDERS(), online);
            } catch(RuntimeException failure) {
                main.getLogger().error("Could not sync playtime to {}; will retry", server.getServerInfo().getName(), failure);
            }
        }
    }

    private void sendConfig(RegisteredServer server, boolean modern, boolean preload) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF(modern ? "conf2" : "conf");
        out.writeBoolean(preload);
        if(modern) {
            out.writeLong(session.getMostSignificantBits());
            out.writeLong(session.getLeastSignificantBits());
        }
        server.sendPluginMessage(main.MCI, out.toByteArray());
    }

    private void sendPlaytimes(RegisteredServer server, boolean modern, boolean replace, Map<String, Long> times) {
        if(modern) {
            sendPackets(server, SnapshotProtocol.encode("pt2", session, ++sequence, replace, times));
            return;
        }
        List<LegacyMessages.Packet> packets = LegacyMessages.encode("pt", times);
        if(packets.size() > 1) sendConfig(server, false, true);
        packets.forEach(packet -> server.sendPluginMessage(main.MCI, packet.bytes()));
    }

    private void sendPackets(RegisteredServer server, List<byte[]> packets) {
        for(byte[] packet : packets) {
            if(!server.sendPluginMessage(main.MCI, packet)) break;
        }
    }

    public LinkedHashMap<String, Long> getFullTL() {
        Map<String, Long> values = new HashMap<>(main.getSavedPlaytimes());
        values.putAll(main.playtimeCache);
        return values.entrySet().stream().sorted(Main.PLAYTIME_ORDER)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    public LinkedHashMap<String, Long> getFullDisplayTL() {
        Map<String, String> names = main.getSavedPlayerNames();
        main.getProxy().getAllPlayers().forEach(player -> names.put(main.getDataKey(player), player.getUsername()));
        return getFullTL().entrySet().stream().collect(Collectors.toMap(
                entry -> names.getOrDefault(entry.getKey(), entry.getKey()), Map.Entry::getValue,
                Math::max, LinkedHashMap::new));
    }
}
