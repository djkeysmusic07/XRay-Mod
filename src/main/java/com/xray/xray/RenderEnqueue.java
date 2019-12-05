package com.xray.xray;

import com.xray.Configuration;
import com.xray.XRay;
import com.xray.reference.block.BlockData;
import com.xray.reference.block.BlockInfo;
import com.xray.store.BlockStore;
import com.xray.utils.OutlineColor;
import com.xray.utils.WorldRegion;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.fluid.IFluidState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class RenderEnqueue implements Runnable
{
	private final WorldRegion box;

	public RenderEnqueue(WorldRegion region )
	{
		box = region;
	}

	@Override
	public void run() // Our thread code for finding ores near the player.
	{
		blockFinder();
	}

	/**
	 * Use Controller.requestBlockFinder() to trigger a scan.
	 */
	private void blockFinder() {
        HashMap<UUID, BlockData> blocks = Controller.getBlockStore().getStore();
        if ( blocks.isEmpty() ) {
		    if( !Render.ores.isEmpty() )
		        Render.ores.clear();
            return; // no need to scan the region if there's nothing to find
        }

		final World world = XRay.mc.world;
		final List<BlockInfo> renderQueue = new ArrayList<>();

		int lowBoundX, highBoundX, lowBoundY, highBoundY, lowBoundZ, highBoundZ;

		// Used for cleaning up the searching process
		BlockState currentState;
		IFluidState currentFluid;

		ResourceLocation block;
		BlockStore.BlockDataWithUUID dataWithUUID;

		// Loop on chunks (x, z)
		for ( int chunkX = box.minChunkX; chunkX <= box.maxChunkX; chunkX++ )
		{
			// Pre-compute the extend bounds on X
			int x = chunkX << 4; // lowest x coord of the chunk in block/world coordinates
			lowBoundX = (x < box.minX) ? box.minX - x : 0; // lower bound for x within the extend
			highBoundX = (x + 15 > box.maxX) ? box.maxX - x : 15;// and higher bound. Basically, we clamp it to fit the radius.

			for ( int chunkZ = box.minChunkZ; chunkZ <= box.maxChunkZ; chunkZ++ )
			{
				// Time to getStore the chunk (16x256x16) and split it into 16 vertical extends (16x16x16)
				if (!world.chunkExists(chunkX, chunkZ)) {
					continue; // We won't find anything interesting in unloaded chunks
				}

				Chunk chunk = world.getChunk( chunkX, chunkZ );
				ChunkSection[] extendsList = chunk.getSections();

				// Pre-compute the extend bounds on Z
				int z = chunkZ << 4;
				lowBoundZ = (z < box.minZ) ? box.minZ - z : 0;
				highBoundZ = (z + 15 > box.maxZ) ? box.maxZ - z : 15;

				// Loop on the extends around the player's layer (6 down, 2 up)
				for ( int curExtend = box.minChunkY; curExtend <= box.maxChunkY; curExtend++ )
				{
					ChunkSection ebs = extendsList[curExtend];
					if (ebs == null) // happens quite often!
						continue;

					// Pre-compute the extend bounds on Y
					int y = curExtend << 4;
					lowBoundY = (y < box.minY) ? box.minY - y : 0;
					highBoundY = (y + 15 > box.maxY) ? box.maxY - y : 15;

					// Now that we have an extend, let's check all its blocks
					for ( int i = lowBoundX; i <= highBoundX; i++ ) {
						for ( int j = lowBoundY; j <= highBoundY; j++ ) {
							for ( int k = lowBoundZ; k <= highBoundZ; k++ ) {
								currentState = ebs.getBlockState(i, j, k);
								currentFluid = currentState.getFluidState();

								if( (currentFluid.getFluid() == Fluids.LAVA || currentFluid.getFluid() == Fluids.FLOWING_LAVA) )
									renderQueue.add(new BlockInfo(x + i, y + j, z + k, new OutlineColor(255, 0, 0).getColor(), 255));

								// Reject blacklisted blocks
								if( Controller.blackList.contains(currentState.getBlock()) )
									continue;

								block = currentState.getBlock().getRegistryName();
								if( block == null )
									continue;

								dataWithUUID = Controller.getBlockStore().getStoreByReference(block.toString());
								if( dataWithUUID == null )
									continue;

								if( dataWithUUID.getBlockData() == null || !dataWithUUID.getBlockData().isDrawing() ) // fail safe
									continue;

								// Push the block to the render queue
								renderQueue.add(new BlockInfo(x + i, y + j, z + k, dataWithUUID.getBlockData().getColor().getColor(), 255));
							}
						}
					}
				}
			}
		}
		final BlockPos playerPos = XRay.mc.player.getPosition();
		renderQueue.sort((t, t1) -> Double.compare(t1.distanceSq(playerPos), t.distanceSq(playerPos)));
		Render.ores.clear();
		Render.ores.addAll( renderQueue ); // Add all our found blocks to the Render.ores list. To be use by Render when drawing.
	}

	/**
	 * Single-block version of blockFinder. Can safely be called directly
	 * for quick block check.
	 * @param pos the BlockPos to check
	 * @param state the current state of the block
	 * @param add true if the block was added to world, false if it was removed
	 */
	public static void checkBlock(BlockPos pos, BlockState state, boolean add )
	{
		if ( !Controller.drawOres() || Controller.getBlockStore().getStore().isEmpty() )
		    return; // just pass

		// If we're removing then remove :D
		if( !add ) {
			Render.ores.remove( new BlockInfo(pos, null, 0.0) );
			return;
		}

		ResourceLocation block = state.getBlock().getRegistryName();
		if( block == null )
			return;

		BlockStore.BlockDataWithUUID dataWithUUID = Controller.getBlockStore().getStoreByReference(block.toString());
		if( dataWithUUID == null || dataWithUUID.getBlockData() == null || !dataWithUUID.getBlockData().isDrawing() )
			return;

		// the block was added to the world, let's add it to the drawing buffer
		Render.ores.add( new BlockInfo(pos, dataWithUUID.getBlockData().getColor().getColor(), 255) );
	}
}