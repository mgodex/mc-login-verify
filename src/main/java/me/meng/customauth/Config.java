package me.meng.customauth;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.ConfigValue<String> AUTH_URL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("auth: mGod", "authUrl: https://www.meng.me")
                .push("auth");

        AUTH_URL = builder
                .define("authUrl", "http://localhost:8080/mc/4399/verify");

        builder.pop();

        SPEC = builder.build();
    }
}
