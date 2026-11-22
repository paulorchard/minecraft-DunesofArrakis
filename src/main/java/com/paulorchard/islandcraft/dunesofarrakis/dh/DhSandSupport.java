package com.paulorchard.islandcraft.dunesofarrakis.dh;

import com.mojang.logging.LogUtils;
import com.paulorchard.islandcraft.dunesofarrakis.ArrakisConfig;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

/**
 * Optional Distant Horizons support. This class and {@link DhSandGenerator} are the only ones that touch the DH API; they are only ever loaded
 * through DunesOfArrakis.setupDh(), which runs only when DH is loaded and catches every Throwable.
 * On every level load it registers our generator for Arrakis server levels. If anything is missing or wrong, nothing is registered and DH keeps its own generator.
 */
public final class DhSandSupport {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void init() {
        if (!ArrakisConfig.dhSandGenerator()) {
            LOGGER.debug("Dune Sand DH generator disabled by config");
            return;
        }
        DhApiEventRegister.on(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> param) {
                try {
                    if (!ArrakisConfig.dhSandGenerator()) return;
                    var levelWrapper = param.value.levelWrapper;
                    if (!(levelWrapper.getWrappedMcObject() instanceof ServerLevel level)) return;   // client levels never generate
                    DhSandGenerator generator = DhSandGenerator.createOrNull(levelWrapper, level);
                    if (generator == null) return;                                                  // not an Arrakis level, or the self-test failed: DH keeps its own generator
                    var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(levelWrapper, generator);
                    if (result.success) LOGGER.info("Distant Horizons: Dune Sand far-terrain generator registered for {}", level.dimension().identifier());
                    else LOGGER.debug("Distant Horizons: registration refused ({}), DH keeps its own generator", result.message);
                } catch (Throwable t) {
                    LOGGER.debug("Distant Horizons: Dune Sand generator not registered, DH keeps its own generator", t);
                }
            }
        });
    }

    private DhSandSupport() {}
}
