package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.BlockCoordinate;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(DunesOfArrakis.MOD_ID)
public class DunesOfArrakis {
    public static final String MOD_ID = "islandcraft_dunesofarrakis";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The only code of the world generator: block_x and block_z, because vanilla has no coordinate density function. */
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
        DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register("block_x", () -> BlockCoordinate.CODEC_X.codec());
        DENSITY_FUNCTION_TYPES.register("block_z", () -> BlockCoordinate.CODEC_Z.codec());
    }

    public DunesOfArrakis(IEventBus modEventBus) {
        DENSITY_FUNCTION_TYPES.register(modEventBus);
        ArrakisBlocks.BLOCKS.register(modEventBus);
        ArrakisBlocks.ITEMS.register(modEventBus);
        NeoForge.EVENT_BUS.register(new DuneSandHarvest());
        LOGGER.info("IslandCraft - Dunes of Arrakis loaded");
    }
}
