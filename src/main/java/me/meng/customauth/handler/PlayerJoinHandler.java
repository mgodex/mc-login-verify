package me.meng.customauth.handler;

import me.meng.customauth.McLoginVerifyMod;
import me.meng.customauth.Config;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerJoinHandler {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "WelcomeTitle");
        t.setDaemon(true);
        return t;
    });

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!Config.ENABLE_VERIFY.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        verifyPlayerAsync(player);
    }

    private void verifyPlayerAsync(ServerPlayer player) {
        String username = player.getGameProfile().getName();
        String uuid = player.getGameProfile().getId().toString();
        String ip = getPlayerIp(player);

        String json = "{\"username\":\"" + escapeJson(username) + "\",\"mcMacId\":\"" + hash(ip + "|" + uuid) + "\",\"ip\":\"" + escapeJson(ip) + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.AUTH_URL.get()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    String body = response.body();
                    if (body == null || body.isBlank()) {
                        handleBlankResponse(player);
                        return;
                    }
                    int code = extractInt(body, "code");
                    if (code == 200) {
                        handleSuccess(player, body);
                    } else {
                        handleFailure(player, body);
                    }
                })
                .exceptionally(e -> {
                    McLoginVerifyMod.LOGGER.error("Auth HTTP request failed for {}: {}", username, e.getMessage());
                    disconnectPlayer(player, "验证系统维护中，暂时无法进入服务器，请访问mc.meng.me查看详细");
                    return null;
                });
    }

    private void handleBlankResponse(ServerPlayer player) {
        broadcastKick(player, "未知原因");
        disconnectPlayer(player, "You are not authorized to join this server");
    }

    private void handleSuccess(ServerPlayer player, String body) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            Component broadcastMsg = Component.literal("§e欢迎 §a" + player.getName().getString() + " §e加入服务器！");
            server.getPlayerList().broadcastSystemMessage(broadcastMsg, false);
        }
        sendDelayedTitle(player);
    }

    private void handleFailure(ServerPlayer player, String body) {
        String msg = extractString(body, "msg");
        String reason = matchReason(msg);
        broadcastKick(player, reason);
        disconnectPlayer(player, msg != null ? msg : "You are not authorized to join this server");
    }

    private void broadcastKick(ServerPlayer player, String reason) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            String playerName = player.getName().getString();
            Component broadcastMsg = Component.literal("§c" + playerName + " §e因" + reason + "被踢出服务器");
            server.getPlayerList().broadcastSystemMessage(broadcastMsg, false);
        }
    }

    private static String matchReason(String msg) {
        if (msg == null) return "未知原因";
        if (msg.contains("未找到验证服务器信息")) return "服务器id验证失败";
        if (msg.contains("未绑定账号")) return "未绑定账号";
        if (msg.contains("拉黑")) return "被管理员拉黑";
        if (msg.contains("已绑定其他设备")) return "绑定设备不符";
        return msg;
    }

    private void sendDelayedTitle(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        SCHEDULER.schedule(() -> {
            server.execute(() -> {
                if (player.connection != null) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(Config.WELCOME_SUBTITLE.get())));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Config.WELCOME_TITLE.get())));
                }
            });
        }, 3, TimeUnit.SECONDS);
    }

    private void disconnectPlayer(ServerPlayer player, String reason) {
        MinecraftServer server = player.getServer();
        if (server != null && player.connection != null) {
            server.execute(() -> player.connection.disconnect(Component.literal(reason)));
        }
    }

    private static String getPlayerIp(ServerPlayer player) {
        try {
            java.lang.reflect.Field f = net.minecraft.server.network.ServerCommonPacketListenerImpl.class
                    .getDeclaredField("connection");
            f.setAccessible(true);
            net.minecraft.network.Connection c = (net.minecraft.network.Connection) f.get(player.connection);
            SocketAddress addr = c.getRemoteAddress();
            String ip = addr.toString();
            if (ip.startsWith("/")) ip = ip.substring(1);
            int colon = ip.lastIndexOf(':');
            return colon > 0 ? ip.substring(0, colon) : ip;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static String hash(String input) {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(input.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc32.getValue());
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\', '/' -> sb.append('\\').append(c);
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
