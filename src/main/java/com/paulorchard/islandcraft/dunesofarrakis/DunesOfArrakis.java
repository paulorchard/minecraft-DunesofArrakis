package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** The mod: the Dune Sand block and its harvest rule. World generation is data only (no code in the generation path). */
@Mod(DunesOfArrakis.MOD_ID)
public class DunesOfArrakis {
    public static final String MOD_ID = "islandcraft_dunesofarrakis";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DunesOfArrakis(IEventBus modEventBus) {
        ArrakisBlocks.BLOCKS.register(modEventBus);
        ArrakisBlocks.ITEMS.register(modEventBus);
        NeoForge.EVENT_BUS.register(new DuneSandHarvest());
        LOGGER.info("IslandCraft - Dunes of Arrakis loaded");
    }
}
