package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/** The mod: the Dune Sand block and its harvest rule. World generation is data only (no code in the generation path). */
@Mod(DunesOfArrakis.MOD_ID)
public class DunesOfArrakis {
    public static final String MOD_ID = "islandcraft_dunesofarrakis";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DunesOfArrakis(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, ArrakisConfig.SPEC);
        modEventBus.addListener(FMLCommonSetupEvent.class, event -> event.enqueueWork(DunesOfArrakis::setupDh));
        ArrakisBlocks.BLOCKS.register(modEventBus);
        ArrakisBlocks.ITEMS.register(modEventBus);
        NeoForge.EVENT_BUS.register(new DuneSandHarvest());
        LOGGER.info("IslandCraft - Dunes of Arrakis loaded");
    }

    /** Optional Distant Horizons support: touches DH classes only when DH is loaded, and ignores every failure (DH then keeps its own generator). */
    private static void setupDh() {
        try {
            if (ModList.get().isLoaded("distanthorizons")) com.paulorchard.islandcraft.dunesofarrakis.dh.DhSandSupport.init();
        } catch (Throwable t) {
            LOGGER.debug("Distant Horizons support not active", t);
        }
    }
}
