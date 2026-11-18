package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(DunesOfArrakis.MOD_ID)
public class DunesOfArrakis {
    public static final String MOD_ID = "islandcraft_dunesofarrakis";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DunesOfArrakis() {
        LOGGER.info("IslandCraft - Dunes of Arrakis loaded");
    }
}
