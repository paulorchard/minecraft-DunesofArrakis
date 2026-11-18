package com.paulorchard.islandcraft.dunesofarrakis;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/**
 * Dune sand is harvested, not broken: when a survival player breaks it, the block stays exactly where it is and the player gets
 * its drops (loot table {@code blocks/dune_sand}: one vanilla sand). Creative players break it normally, so the world can still be
 * edited while testing (no drops in creative).
 */
public final class DuneSandHarvest {
    @SubscribeEvent
    public void onBreak(BreakBlockEvent event) {
        if (!event.getState().is(ArrakisBlocks.DUNE_SAND.get())) return;
        Player player = event.getPlayer();
        if (player.isCreative()) return;
        event.setCanceled(true);
        event.setNotifyClient(true);
        if (event.getLevel() instanceof ServerLevel level) {
            for (ItemStack drop : Block.getDrops(event.getState(), level, event.getPos(), null, player, player.getMainHandItem())) {
                if (!player.getInventory().add(drop)) player.drop(drop, false);
            }
        }
    }
}
