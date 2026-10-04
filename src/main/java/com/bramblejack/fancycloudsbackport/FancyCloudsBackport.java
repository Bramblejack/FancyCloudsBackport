package com.bramblejack.fancycloudsbackport;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(FancyCloudsBackport.MOD_ID)
public final class FancyCloudsBackport {
    public static final String MOD_ID = "fancycloudsbackport";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FancyCloudsBackport(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
