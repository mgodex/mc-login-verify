package me.meng.customauth.handler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import me.meng.customauth.Config;
import me.meng.customauth.McLoginVerifyMod;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class QueryControlServer {
    private static final Pattern SERVER_ID_PATTERN = Pattern.compile("/mc/(\\d+)/verify");

    private HttpServer server;
    private volatile ExecutorService executor;
    private final MinecraftServer minecraftServer;
    private final String serverId;

    public QueryControlServer(MinecraftServer minecraftServer) {
        this.minecraftServer = minecraftServer;
        this.serverId = extractServerId();
    }

    public void start() {
        if (!Config.ENABLE_QUERY_CONTROL.get()) {
            McLoginVerifyMod.LOGGER.info("Query control is disabled");
            return;
        }
        if (serverId == null) {
            McLoginVerifyMod.LOGGER.error("Failed to extract serverId from authUrl, query control not started");
            return;
        }
        try {
            int port = Config.QUERY_PORT.get();
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            server.createContext("/api/status", this::handleStatus);
            server.createContext("/api/kick", this::handleKick);
            server.createContext("/api/command", this::handleCommand);
            executor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "QueryControlWorker");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
            McLoginVerifyMod.LOGGER.info("Query control server started on port {}, serverId={}, token={}", port, serverId, Config.QUERY_PASSWORD.get());
        } catch (IOException e) {
            McLoginVerifyMod.LOGGER.error("Failed to start query control server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        McLoginVerifyMod.LOGGER.info("Query control server stopped");
    }

    public static String extractServerId() {
        String url = Config.AUTH_URL.get();
        if (url == null) return null;
        Matcher m = SERVER_ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private String checkAuthFromHeaders(HttpExchange exchange) {
        if (!Config.ENABLE_QUERY_CONTROL.get()) return "Query control is disabled";

        String token = exchange.getRequestHeaders().getFirst("X-Token");
        if (token == null || token.isBlank()) {
            token = exchange.getRequestHeaders().getFirst("Authorization");
            if (token != null && token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
        }

        String sid = exchange.getRequestHeaders().getFirst("X-Server-Id");

        return checkAuth(token, sid);
    }

    private String checkAuth(String requestToken, String sid) {
        if (!Config.ENABLE_QUERY_CONTROL.get()) return "Query control is disabled";
        String cfgToken = Config.QUERY_PASSWORD.get();
        if (cfgToken == null || cfgToken.isBlank() || !cfgToken.equals(requestToken)) return "Invalid token";
        if (serverId == null || !serverId.equals(sid)) return "Invalid serverId";
        return null;
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed\"}");
            return;
        }
        String error = checkAuthFromHeaders(exchange);
        if (error != null) {
            sendJson(exchange, 401, jsonError(error));
            return;
        }

        List<ServerPlayer> players = minecraftServer.getPlayerList().getPlayers();
        int online = players.size();
        int maxPlayers = minecraftServer.getMaxPlayers();

        String playerList = players.stream()
                .map(p -> {
                    String name = p.getGameProfile().getName();
                    int ping = p.connection.latency();
                    return "{\"name\":\"" + jsonEscape(name) + "\",\"ping\":" + ping + "}";
                })
                .collect(Collectors.joining(","));

        Runtime rt = Runtime.getRuntime();
        long memoryUsed = rt.totalMemory() - rt.freeMemory();
        long memoryMax = rt.maxMemory();
        double tps = getTps();
        double cpuUsage = getCpuUsage();

        File root = new File(".");
        long diskFree = root.getFreeSpace();
        long diskTotal = root.getTotalSpace();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        String mcVersion = minecraftServer.getServerVersion();
        String modVersion = McLoginVerifyMod.MOD_VERSION != null ? McLoginVerifyMod.MOD_VERSION : "unknown";

        String data = "{\"onlinePlayers\":" + online
                + ",\"maxPlayers\":" + maxPlayers
                + ",\"playerList\":[" + playerList + "]"
                + ",\"tps\":" + String.format("%.1f", tps)
                + ",\"memoryUsed\":" + memoryUsed
                + ",\"memoryMax\":" + memoryMax
                + ",\"cpuUsage\":" + (cpuUsage >= 0 ? String.format("%.1f", cpuUsage) : "null")
                + ",\"diskFree\":" + diskFree
                + ",\"diskTotal\":" + diskTotal
                + ",\"uptime\":" + uptime
                + ",\"serverVersion\":\"" + jsonEscape(mcVersion) + "\""
                + ",\"modVersion\":\"" + jsonEscape(modVersion) + "\""
                + ",\"serverId\":\"" + jsonEscape(serverId) + "\"}";

        sendJson(exchange, 200, "{\"success\":true,\"data\":" + data + "}");
    }

    private void handleKick(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String error = checkAuthFromHeaders(exchange);
        if (error != null) {
            sendJson(exchange, 401, jsonError(error));
            return;
        }

        String playerName = extractField(body, "player");
        if (playerName == null || playerName.isBlank()) {
            sendJson(exchange, 200, jsonError("Player name is required"));
            return;
        }

        String reason = extractField(body, "reason");
        if (reason == null || reason.isBlank()) reason = "You have been kicked";

        String finalReason = reason;
        minecraftServer.execute(() -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayerByName(playerName);
            if (player != null && player.connection != null) {
                player.connection.disconnect(Component.literal(finalReason));
            }
        });

        sendJson(exchange, 200, "{\"success\":true,\"message\":\"Kicked " + jsonEscape(playerName) + "\"}");
    }

    private void handleCommand(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"success\":false,\"message\":\"Method not allowed\"}");
            return;
        }
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String error = checkAuthFromHeaders(exchange);
        if (error != null) {
            sendJson(exchange, 401, jsonError(error));
            return;
        }

        String command = extractField(body, "command");
        if (command == null || command.isBlank()) {
            sendJson(exchange, 200, jsonError("Command is required"));
            return;
        }

        StringBuilder output = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        CommandSource collector = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                if (!output.isEmpty()) output.append("\n");
                output.append(component.getString());
            }
            @Override
            public boolean acceptsSuccess() { return true; }
            @Override
            public boolean acceptsFailure() { return true; }
            @Override
            public boolean shouldInformAdmins() { return true; }
        };

        CommandSourceStack original = minecraftServer.createCommandSourceStack();
        CommandSourceStack collectSource = new CommandSourceStack(
                collector, original.getPosition(), original.getRotation(),
                original.getLevel(), 4,
                original.getTextName(), original.getDisplayName(),
                original.getServer(), original.getEntity());

        minecraftServer.execute(() -> {
            try {
                minecraftServer.getCommands().performPrefixedCommand(collectSource, command);
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        String result = output.isEmpty() ? "Command executed" : output.toString();
        sendJson(exchange, 200, "{\"success\":true,\"data\":\"" + jsonEscape(result) + "\"}");
    }

    private double getTps() {
        float smoothedMs = minecraftServer.getCurrentSmoothedTickTime();
        if (smoothedMs <= 0) return 20.0;
        return Math.min(20.0, 1000.0 / smoothedMs);
    }

    private double getCpuUsage() {
        try {
            com.sun.management.OperatingSystemMXBean osBean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = osBean.getProcessCpuLoad();
            return load >= 0 ? Math.round(load * 1000.0) / 10.0 : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extractField(String body, String key) {
        if (body == null) return null;
        Pattern p = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String jsonError(String msg) {
        return "{\"success\":false,\"message\":\"" + jsonEscape(msg) + "\"}";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"', '\\' -> sb.append('\\').append(c);
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
