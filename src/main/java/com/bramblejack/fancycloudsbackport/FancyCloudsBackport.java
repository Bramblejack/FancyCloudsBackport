package com.bramblejack.fancycloudsbackport;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(FancyCloudsBackport.MOD_ID)
public final class FancyCloudsBackport {
    public static final String MOD_ID = "fancycloudsbackport";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FancyCloudsBackport(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }
}
