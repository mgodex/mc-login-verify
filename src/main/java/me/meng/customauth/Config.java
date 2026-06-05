package me.meng.customauth;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> AUTH_URL;
    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_VERIFY;
    public static final ModConfigSpec.ConfigValue<Boolean> ENABLE_QUERY_CONTROL;
    public static final ModConfigSpec.ConfigValue<String> QUERY_PASSWORD;
    public static final ModConfigSpec.ConfigValue<Integer> QUERY_PORT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("auth: mGod", "authUrl: https://www.meng.me")
                .push("auth");

        AUTH_URL = builder
                .define("authUrl", "http://localhost:8080/mc/4399/verify");

        ENABLE_VERIFY = builder
                .comment("Enable player login verification")
                .define("enableVerify", true);

        builder.pop();

        builder.comment("Remote query and control (token auth)")
                .push("queryControl");

        ENABLE_QUERY_CONTROL = builder
                .comment("Enable remote query and control via HTTP")
                .define("enableQueryControl", false);

        String defaultToken = java.util.UUID.randomUUID().toString().replace("-", "");
        QUERY_PASSWORD = builder
                .comment("Access token for remote API (auto-generated on first run)")
                .define("token", defaultToken);

        QUERY_PORT = builder
                .comment("HTTP server port for remote API")
                .defineInRange("queryPort", 25577, 1024, 65535);

        builder.pop();

        SPEC = builder.build();
    }
}
