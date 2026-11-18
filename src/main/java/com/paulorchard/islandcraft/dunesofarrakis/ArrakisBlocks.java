package com.paulorchard.islandcraft.dunesofarrakis;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Blocks and block items of the mod. */
public final class ArrakisBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DunesOfArrakis.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DunesOfArrakis.MOD_ID);

    /**
     * The sand the dunes are made of. A plain {@link Block} (not a falling block), so it has no gravity. Everything else is copied
     * from vanilla sand (sounds, hardness, map colour, shovel tool). It is made immune to explosions and pistons; survival players
     * cannot break it, only harvest it (see {@link DuneSandHarvest}).
     */
    public static final DeferredBlock<Block> DUNE_SAND = BLOCKS.registerSimpleBlock("dune_sand",
        () -> BlockBehaviour.Properties.ofFullCopy(Blocks.SAND).explosionResistance(3_600_000.0F).pushReaction(PushReaction.BLOCK));
    public static final DeferredItem<BlockItem> DUNE_SAND_ITEM = ITEMS.registerSimpleBlockItem(DUNE_SAND);

    private ArrakisBlocks() {}
}
