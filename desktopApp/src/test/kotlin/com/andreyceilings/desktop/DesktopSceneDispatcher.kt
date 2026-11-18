package com.andreyceilings.desktop

import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

/** Deliver effect continuations on the thread that drives the headless scene's input and frames. */
internal class DesktopSceneDispatcher : CoroutineDispatcher() {
    private val pending = ConcurrentLinkedQueue<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        pending.add(block)
    }

    fun render(scene: ImageComposeScene, timeNanos: Long = System.nanoTime()) {
        while (true) (pending.poll() ?: break).run()
        scene.render(timeNanos).close()
    }
}
