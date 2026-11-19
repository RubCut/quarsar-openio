package quasar.rubcut.openio.domain.port

import quasar.rubcut.openio.domain.model.AudioChunk
import quasar.rubcut.openio.domain.model.RingFrame
import quasar.rubcut.openio.domain.model.ScreenFrame

interface ScreenOutput {
    fun drawScreen(frame: ScreenFrame): Boolean
}

interface RingOutput {
    fun drawRing(frame: RingFrame): Boolean
}

interface InputSource {
    fun start(): Boolean
    fun stop()
}

interface AudioCapture {
    fun start(): Boolean
    fun stop()
}

interface AudioSink {
    val name: String
    fun start(): Boolean
    fun enqueue(chunk: AudioChunk)
    fun stop()
}

interface EventSink {
    fun emit(eventJson: String)
}