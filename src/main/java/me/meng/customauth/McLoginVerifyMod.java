package me.meng.customauth;

import me.meng.customauth.handler.PlayerJoinHandler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(McLoginVerifyMod.MODID)
public class McLoginVerifyMod {
    public static final String MODID = "mc_login_verify";
    public static final Logger LOGGER = LogUtils.getLogger();

    public McLoginVerifyMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        NeoForge.EVENT_BUS.register(new PlayerJoinHandler());
        LOGGER.info("mc-login-verify loaded");
    }
}
