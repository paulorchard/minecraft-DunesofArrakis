package com.paulorchard.islandcraft.dunesofarrakis;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Dune Sand. Behaves as a plain solid block, with three overrides that make the creative hide-sand toggle possible:
 * <ul>
 * <li>{@code noOcclusion()} (in {@link ArrakisBlocks}): the sand never hides a neighbour's face, so while the sand is hidden the rock walls next to it still draw.</li>
 * <li>{@link #skipRendering}: faces between two Dune Sand blocks are skipped by the block itself, so the buried interior is not drawn (what occlusion did before).</li>
 * <li>{@link #getLightDampening}: stays 15, so light is blocked exactly as for a full opaque block.</li>
 * <li>{@link #getRenderShape}: INVISIBLE while {@link SandView} says hidden (client only; the server never uses it).</li>
 * </ul>
 */
public class DuneSandBlock extends Block {
    public DuneSandBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState neighborState, Direction direction) {
        return neighborState.is(this);
    }

    @Override
    protected int getLightDampening(BlockState state) {
        return 15;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return SandView.isHidden() ? RenderShape.INVISIBLE : RenderShape.MODEL;
    }
}
