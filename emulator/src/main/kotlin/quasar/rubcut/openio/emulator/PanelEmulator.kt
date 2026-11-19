package quasar.rubcut.openio.emulator

import quasar.rubcut.openio.domain.model.QuasarMode
import quasar.rubcut.openio.domain.model.ScreenFrame
import quasar.rubcut.openio.domain.render.ClockSize
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import kotlin.math.cos
import kotlin.math.sin

private const val SCREEN_SCALE = 18
private const val RING_DIAMETER = 300

/** 25x16 LED matrix with a small gap between "pixels". */
class ScreenPanel : JComponent() {

    @Volatile
    var frame: ScreenFrame = ScreenFrame()

    init {
        preferredSize = Dimension(25 * SCREEN_SCALE, 16 * SCREEN_SCALE)
        background = Color(0x0b0b10)
        border = BorderFactory.createLineBorder(Color(0x2a2a33), 8)
    }

    override fun paintComponent(g: Graphics) {
        g as Graphics2D
        g.color = background
        g.fillRect(0, 0, width, height)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val pixels = frame.pixels
        for (y in 0 until ScreenFrame.DEFAULT_HEIGHT) {
            for (x in 0 until ScreenFrame.DEFAULT_WIDTH) {
                val color = Color(pixels[y * ScreenFrame.DEFAULT_WIDTH + x])
                g.color = if (color.rgb == 0) Color(0x131318) else color
                g.fillRoundRect(
                    x * SCREEN_SCALE + 1,
                    y * SCREEN_SCALE + 1,
                    SCREEN_SCALE - 2,
                    SCREEN_SCALE - 2,
                    6, 6,
                )
            }
        }
    }
}

/** 24-LED ring; logical index 0 is the front arrow (top, counter-clockwise). */
class RingPanel : JComponent() {

    @Volatile
    var ring: quasar.rubcut.openio.domain.model.RingFrame = quasar.rubcut.openio.domain.model.RingFrame()

    init {
        preferredSize = Dimension(RING_DIAMETER, RING_DIAMETER)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        g as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cx = width / 2.0
        val cy = height / 2.0
        val radius = minOf(width, height) / 2.0 - 26
        val ledRadius = radius / 5.2

        g.color = Color(0x2a2a33)
        g.stroke = java.awt.BasicStroke(1.6f)
        g.draw(
            java.awt.geom.Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2),
        )

        // Physical LED 0 sits at the bottom/back; indices grow counter-clockwise,
        // so the front marker is LED 12 at the top.
        g.color = Color(0x9a93a8)
        val marker = java.awt.geom.Path2D.Double()
        marker.moveTo(cx, cy + radius + 20)
        marker.lineTo(cx - 6, cy + radius + 6)
        marker.lineTo(cx + 6, cy + radius + 6)
        marker.closePath()
        g.fill(marker)

        for (i in 0 until ring.size) {
            val angle = Math.PI / 2 - (i.toDouble() / ring.size) * Math.PI * 2
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            val color = Color(ring.get(i))
            val lit = ring.get(i) != 0
            g.color = if (lit) color else Color(0x1b1b21)
            g.fillOval(
                (x - ledRadius).toInt(),
                (y - ledRadius).toInt(),
                (ledRadius * 2).toInt(),
                (ledRadius * 2).toInt(),
            )
            if (lit) {
                g.color = Color(color.red, color.green, color.blue, 40)
                g.fillOval(
                    (x - ledRadius * 1.7).toInt(),
                    (y - ledRadius * 1.7).toInt(),
                    (ledRadius * 3.4).toInt(),
                    (ledRadius * 3.4).toInt(),
                )
            }
        }

        g.color = Color(0x77707f)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        val text = "24 LED · 0 снизу, 12 спереди"
        val bounds = g.fontMetrics.stringWidth(text)
        g.drawString(text, (width - bounds) / 2, height - 6)
    }
}

private fun <T> comboBox(items: Array<T>, selected: T, onSelect: (T) -> Unit): JComboBox<T> {
    val combo = JComboBox(items)
    combo.selectedItem = selected
    combo.addActionListener { onSelect(combo.selectedItem as T) }
    return combo
}

fun main(args: Array<String>) {
    if (args.contains("--selftest")) {
        runSelfTest(args)
        return
    }
    SwingUtilities.invokeLater { showWindow(args) }
}

private fun showWindow(args: Array<String>) {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
        // Default look is fine.
    }

    val host = EngineHost()
    val live = LiveSource(args.firstOrNull { it.startsWith("http") } ?: "http://127.0.0.1:18080")
    var useLive = false

    host.engine.setRingOrigin(12)
    host.engine.setClockSize(ClockSize.LARGE)
    host.setMode(QuasarMode.CLOCK)
    host.audio.kind = ToneSource.Kind.TONE_440 // keep in sync with the soundCombo default
    host.start()

    val frame = JFrame("Quasar OpenIO — эмулятор панели")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout(10, 10)

    val screenPanel = ScreenPanel()
    val ringPanel = RingPanel()
    val screenTitle = JLabel("Эмулятор · движок OpenIO", JLabel.CENTER)
    val statusLabel = JLabel("—", JLabel.CENTER)

    val preview = JPanel().apply {
        background = Color(0x141218)
        layout = java.awt.FlowLayout(FlowLayout.CENTER, 24, 16)
        add(screenPanel)
        add(ringPanel)
    }

    // ----------------------------------------------------------------- controls
    val controls = JPanel(GridBagLayout()).apply { background = Color(0x141218) }
    val gc = GridBagConstraints().apply {
        fill = GridBagConstraints.HORIZONTAL
        insets = Insets(3, 8, 3, 8)
        weightx = 1.0
    }
    fun row(y: Int, vararg components: JComponent) {
        gc.gridy = y
        gc.gridx = 0
        gc.weightx = 0.0
        for ((index, component) in components.withIndex()) {
            gc.gridx = index
            gc.weightx = if (index == components.size - 1) 1.0 else 0.0
            controls.add(component, gc)
        }
    }

    val sourceCombo = comboBox(arrayOf("Эмулятор (движок)", "Станция (HTTP)"), "Эмулятор (движок)") { value ->
        useLive = value.toString().startsWith("Станция")
        if (useLive) live.start() else live.stop()
        screenTitle.text = if (useLive) "Станция · живой вид" else "Эмулятор · движок OpenIO"
    }
    val modeCombo = comboBox(
        QuasarMode.entries.filter { it != QuasarMode.AUTO && it != QuasarMode.CUSTOM }.toTypedArray(),
        QuasarMode.CLOCK,
    ) { host.setMode(it) }
    val soundCombo = comboBox(ToneSource.Kind.entries.toTypedArray(), ToneSource.Kind.TONE_440) { host.audio.kind = it }
    val levelSlider = JSlider(0, 100, (host.audio.level * 100).toInt()).apply {
        addChangeListener { host.audio.level = value / 100.0 }
    }
    val sensitivitySlider = JSlider(25, 800, 100).apply {
        addChangeListener { host.engine.setScopeSensitivity(value / 100f) }
    }
    val clockSizeCombo = comboBox(ClockSize.entries.toTypedArray(), ClockSize.LARGE) { host.engine.setClockSize(it) }
    val animationButton = JButton("GIF-файл…").apply {
        addActionListener {
            val chooser = JFileChooser("app/src/main/assets/anim")
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                val file = chooser.selectedFile
                if (host.loadIdleAnimation(file)) {
                    host.setMode(QuasarMode.ANIMATION)
                    modeCombo.selectedItem = QuasarMode.ANIMATION
                } else {
                    screenTitle.text = "Не удалось декодировать GIF: ${file.name}"
                }
            }
        }
    }
    val animFolder = File("app/src/main/assets/anim").takeIf { it.isDirectory }
    val idleCombo = JComboBox<String>().apply {
        if (animFolder != null) {
            val gifs = animFolder.walkTopDown().filter { it.extension == "gif" }.take(400).toList()
            for (gif in gifs) {
                val relative = gif.relativeTo(animFolder.parentFile.parentFile.parentFile.parentFile).path
                    .removePrefix("app/src/main/assets/").removePrefix("anim/")
                addItem(relative)
            }
        }
        addActionListener {
            val selected = selectedItem as? String ?: return@addActionListener
            val file = animFolder?.resolve(selected.removePrefix("anim/")) ?: return@addActionListener
            if (file.isFile && host.loadIdleAnimation(file)) {
                host.setMode(QuasarMode.ANIMATION)
                modeCombo.selectedItem = QuasarMode.ANIMATION
            }
        }
    }

    row(0, JLabel("Источник"), sourceCombo)
    row(1, JLabel("Режим"), modeCombo, animationButton)
    row(2, JLabel("Звук"), soundCombo, JLabel("Громкость"))
    row(3, levelSlider)
    row(4, JLabel("Чувствительность"), sensitivitySlider)
    row(5, JLabel("Размер часов"), clockSizeCombo, JLabel("Idle-анимация"))
    row(6, idleCombo)

    val side = JPanel(BorderLayout()).apply {
        background = Color(0x141218)
        add(JLabel("Управление"), BorderLayout.NORTH)
        add(controls, BorderLayout.CENTER)
    }

    frame.add(preview, BorderLayout.CENTER)
    frame.add(side, BorderLayout.EAST)
    frame.add(
        JPanel(BorderLayout()).apply {
            background = Color(0x141218)
            add(screenTitle, BorderLayout.NORTH)
            add(statusLabel, BorderLayout.SOUTH)
        },
        BorderLayout.SOUTH,
    )

    // Pull rendered frames into the UI at ~30 fps.
    val painter = Timer(33) {
        if (useLive) {
            screenPanel.frame = live.screen ?: screenPanel.frame
            ringPanel.ring = live.ring ?: ringPanel.ring
            statusLabel.text = if (live.online) (live.status.ifEmpty { "станция · живой вид" }) else "станция недоступна"
            if (screenTitle.text.startsWith("Станция") && live.online && live.status.isNotEmpty()) {
                statusLabel.text = live.status
            }
        } else {
            screenPanel.frame = host.copyScreen()
            ringPanel.ring = host.copyRing()
            statusLabel.text =
                "режим ${host.engine.mode.id} · стиль ${host.engine.scopeStyle.id} · " +
                "${host.engine.scopeFpsActual} fps · звук: ${host.audio.kind.title}"
        }
        screenPanel.repaint()
        ringPanel.repaint()
    }
    painter.start()

    // Status caption for the live view, once a second.
    val statusPoller = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "emu-status").apply { isDaemon = true }
    }
    statusPoller.scheduleAtFixedRate({ if (useLive) live.pollStatusOnce() }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)

    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.isVisible = true
}

/**
 * Headless check: renders a few seconds of engine output and prints the panel
 * as ASCII, so the domain renderer can be validated without a display.
 * Usage: emulator --selftest [soundKind]
 */
private fun runSelfTest(args: Array<String>) {
    val host = EngineHost()
    val sound = ToneSource.Kind.entries.firstOrNull {
        it.name.equals(args.getOrNull(args.indexOf("--selftest") + 1) ?: "", true)
    } ?: ToneSource.Kind.TONE_440

    host.engine.setScopeFps(30)
    host.setMode(QuasarMode.OSCILLOSCOPE)
    host.audio.kind = sound
    println("selftest: sound=${sound.name}")

    // 2.5 s of audio at 20 ms chunks, rendering right after every chunk.
    val chunks = 125
    for (i in 0 until chunks) {
        host.engine.onAudioChunk(host.audio.next(320))
        host.engine.frameTick()
    }
    val frame = host.copyScreen()
    println("frame lit pixels: ${frame.pixels.count { it != 0 }}")
    for (y in 0 until ScreenFrame.DEFAULT_HEIGHT) {
        for (x in 0 until ScreenFrame.DEFAULT_WIDTH) {
            print(ascii(frame.get(x, y)))
        }
        println()
    }
    println("clock check:")
    host.setMode(QuasarMode.CLOCK)
    host.engine.tick(java.time.LocalTime.of(21, 47))
    val clock = host.copyScreen()
    for (y in 0 until ScreenFrame.DEFAULT_HEIGHT) {
        for (x in 0 until ScreenFrame.DEFAULT_WIDTH) {
            print(ascii(clock.get(x, y)))
        }
        println()
    }
}

/** 5-level ramp: dim pads/bars stay distinguishable in the dump. */
private fun ascii(value: Int): Char {
    val channel = value and 0xFF
    return when {
        value <= 0 -> ' '
        channel < 64 -> '.'
        channel < 128 -> '+'
        channel < 200 -> '*'
        else -> '#'
    }
}
