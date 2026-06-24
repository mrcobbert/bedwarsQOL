package com.bedwarsqol.feature;

import com.bedwarsqol.BedwarsQol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerAddress;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.status.INetHandlerStatusClient;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;
import net.minecraft.network.status.server.S00PacketServerInfo;
import net.minecraft.network.status.server.S01PacketPong;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.SubscribeEvent;
import net.weavemc.api.event.TickEvent;

import java.net.InetAddress;

/**
 * Measures real latency to the current server, because Hypixel deliberately overwrites the
 * tab-list ping to a flat {@code 1ms} in-game — so {@code getResponseTime()} is unusable.
 *
 * <p><b>All networking runs on a dedicated daemon thread, never the game thread.</b>
 * {@code NetworkManager.createNetworkManagerAndConnect} does a <i>blocking</i> TCP connect
 * (plus a DNS lookup), so doing it on the client tick would hitch the game every cycle. The
 * tick handler does nothing but publish the current server IP (and only while the Info HUD is
 * enabled); the worker thread opens a throwaway status connection, times a ping/pong round
 * trip (vanilla {@code OldServerPinger} technique), and stores the result in a volatile.
 */
public final class PingTracker {

    private static final long REPING_INTERVAL_MS = 3000L;
    private static final long ATTEMPT_TIMEOUT_MS = 5000L;
    private static final long POLL_MS = 10L;

    /** Last measured round-trip latency in ms, or {@code -1} if not measuring / not measured yet. */
    private static volatile int pingMs = -1;
    /** Server IP to ping, published by the game thread; {@code null} means "don't ping". */
    private static volatile String targetIp;
    private static volatile boolean workerStarted;

    public static int ping() {
        return pingMs;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData server = mc == null ? null : mc.getCurrentServerData();
        boolean active = mc != null && mc.theWorld != null && server != null && server.serverIP != null
                && !mc.isSingleplayer()
                && BedwarsQol.config != null && BedwarsQol.config.infoEnabled; // only when the ping HUD is on
        if (!active) {
            targetIp = null;
            pingMs = -1;
            return;
        }
        targetIp = server.serverIP;
        startWorker();
    }

    private static void startWorker() {
        if (workerStarted) return; // only the game thread sets this, so no lock needed
        workerStarted = true;
        Thread t = new Thread(PingTracker::loop, "BedwarsQol-Ping");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        while (true) {
            String ip = targetIp;
            if (ip == null) {
                pingMs = -1;
            } else {
                measure(ip);
            }
            sleep(REPING_INTERVAL_MS);
        }
    }

    private static void measure(String ip) {
        NetworkManager conn = null;
        try {
            ServerAddress addr = ServerAddress.fromString(ip);
            // Blocking connect + DNS — fine here, this is the worker thread, not the game thread.
            conn = NetworkManager.createNetworkManagerAndConnect(
                    InetAddress.getByName(addr.getIP()), addr.getPort(), false);
            final NetworkManager c = conn;
            final long[] sentAt = {0L};
            final boolean[] done = {false};
            conn.setNetHandler(new INetHandlerStatusClient() {
                @Override
                public void handleServerInfo(S00PacketServerInfo packetIn) {
                    sentAt[0] = Minecraft.getSystemTime();
                    c.sendPacket(new C01PacketPing(sentAt[0]));
                }

                @Override
                public void handlePong(S01PacketPong packetIn) {
                    pingMs = (int) Math.max(0L, Minecraft.getSystemTime() - sentAt[0]);
                    done[0] = true;
                }

                @Override
                public void onDisconnect(IChatComponent reason) {
                    done[0] = true;
                }
            });
            conn.sendPacket(new C00Handshake(47, addr.getIP(), addr.getPort(), EnumConnectionState.STATUS));
            conn.sendPacket(new C00PacketServerQuery());

            long deadline = Minecraft.getSystemTime() + ATTEMPT_TIMEOUT_MS;
            while (!done[0] && conn.isChannelOpen() && Minecraft.getSystemTime() < deadline) {
                conn.processReceivedPackets();
                sleep(POLL_MS);
            }
        } catch (Exception ignored) {
            // Connect/DNS failure — leave the previous reading, try again next cycle.
        } finally {
            if (conn != null && conn.isChannelOpen()) {
                try {
                    conn.closeChannel(new ChatComponentText("bedwarsqol-ping-done"));
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
