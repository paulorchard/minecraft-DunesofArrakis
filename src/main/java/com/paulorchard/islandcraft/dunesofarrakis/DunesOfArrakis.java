package com.paulorchard.islandcraft.dunesofarrakis;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ArrakisCoastField;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ArrakisContinentField;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ArrakisContinentRule;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ArrakisLinear;
import com.paulorchard.islandcraft.dunesofarrakis.worldgen.ArrakisSawtooth;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(DunesOfArrakis.MOD_ID)
public class DunesOfArrakis {
    public static final String MOD_ID = "islandcraft_dunesofarrakis";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Custom density function types used by the dune terrain (see docs/dunes.md). */
    private static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES =
        DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, MOD_ID);

    /** Custom surface rule types (the continent footprint decides the rock material). */
    private static final DeferredRegister<MapCodec<? extends SurfaceRules.RuleSource>> MATERIAL_RULES =
        DeferredRegister.create(Registries.MATERIAL_RULE, MOD_ID);

    static {
        DENSITY_FUNCTION_TYPES.register("arrakis_coast_field", () -> ArrakisCoastField.CODEC.codec());
        DENSITY_FUNCTION_TYPES.register("arrakis_continent_field", () -> ArrakisContinentField.CODEC.codec());
        MATERIAL_RULES.register("arrakis_continent_block", () -> ArrakisContinentRule.CODEC.codec());
        DENSITY_FUNCTION_TYPES.register("arrakis_linear", () -> ArrakisLinear.CODEC.codec());
        DENSITY_FUNCTION_TYPES.register("arrakis_sawtooth", () -> ArrakisSawtooth.CODEC.codec());
    }

    public DunesOfArrakis(IEventBus modEventBus) {
        DENSITY_FUNCTION_TYPES.register(modEventBus);
        MATERIAL_RULES.register(modEventBus);
        ArrakisBlocks.BLOCKS.register(modEventBus);
        ArrakisBlocks.ITEMS.register(modEventBus);
        NeoForge.EVENT_BUS.register(new DuneSandHarvest());
        LOGGER.info("IslandCraft - Dunes of Arrakis loaded");
    }
}
