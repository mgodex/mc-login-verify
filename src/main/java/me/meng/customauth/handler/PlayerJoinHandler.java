package me.meng.customauth.handler;

import me.meng.customauth.McLoginVerifyMod;
import me.meng.customauth.Config;
import net.minecraft.network.chat.Component;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerJoinHandler {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
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
                        disconnectPlayer(player, "You are not authorized to join this server");
                        return;
                    }
                    int code = extractInt(body, "code");
                    if (code != 200) {
                        String msg = extractString(body, "msg");
                        disconnectPlayer(player, msg != null ? msg : "You are not authorized to join this server");
                    }
                })
                .exceptionally(e -> {
                    McLoginVerifyMod.LOGGER.error("Auth HTTP request failed for {}: {}", username, e.getMessage());
                    disconnectPlayer(player, "验证系统维护中，暂时无法进入服务器，请访问mc.meng.me查看详细");
                    return null;
                });
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
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return input;
        }
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
