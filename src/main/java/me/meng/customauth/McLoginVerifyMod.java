package me.meng.customauth;

import me.meng.customauth.handler.PlayerJoinHandler;
import me.meng.customauth.handler.QueryControlServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(McLoginVerifyMod.MODID)
public class McLoginVerifyMod {
    public static final String MODID = "mc_login_verify";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static String MOD_VERSION;

    private QueryControlServer queryControlServer;

    public McLoginVerifyMod(IEventBus modEventBus, ModContainer modContainer) {
        MOD_VERSION = modContainer.getModInfo().getVersion().toString();
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(new PlayerJoinHandler());

        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, event -> {
            queryControlServer = new QueryControlServer(event.getServer());
            queryControlServer.start();
        });

        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> {
            if (queryControlServer != null) {
                queryControlServer.stop();
            }
        });

        LOGGER.info("mc-login-verify loaded");
    }
}
