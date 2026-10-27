package com.alvar.observa.mixin;

import com.alvar.observa.collect.ChunkGenCounter;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * 唯一一处 Mixin：数「真正被生成的区块」。
 *
 * <p>挂 {@code ChunkStatusTasks#generateNoise} 的头部。选它的理由：从磁盘读出的区块在存档里已经是
 * FULL 状态，加载时不会跑噪声生成；只有第一次到达某片区域、需要现造地形时才会进这一步。
 * 因此这里 +1 = 「新生成一个区块」，而不是「加载一个区块」。</p>
 *
 * <p>注入点必须写成完整描述符：方法名 {@code generateNoise} 在别的类里也有，光靠名字不够稳。</p>
 */
@Mixin(ChunkStatusTasks.class)
public abstract class ChunkGenerationMixin {

    @Inject(
            method = "generateNoise(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
                    + "Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                    + "Lnet/minecraft/util/StaticCache2D;"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                    + "Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"))
    private static void observa$countGeneratedChunk(WorldGenContext context,
                                                    ChunkStep step,
                                                    StaticCache2D<GenerationChunkHolder> cache,
                                                    ChunkAccess chunk,
                                                    CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkGenCounter.onChunkGenerated();
    }
}
