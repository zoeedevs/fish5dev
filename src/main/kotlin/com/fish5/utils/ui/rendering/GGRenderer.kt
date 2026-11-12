package com.fish5.utils.ui.rendering

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.fish5.Fish5.mc
import com.fish5.features.impl.render.ClickGUIModule
import net.minecraft.client.gui.Font as McFont
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import org.lwjgl.nanovg.NanoSVG.*
import org.lwjgl.stb.STBImage.stbi_load_from_memory
import org.lwjgl.system.MemoryUtil.memFree
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Draws Fish5's GUIs/HUDs through Minecraft's own [GuiGraphicsExtractor] (fill / blit / text /
 * scissor), so it works on every render backend (OpenGL and 26.2's Vulkan alike -- NanoVG can't run on
 * Vulkan, which is why it was dropped).
 *
 * ## Shapes: baked anti-aliased textures 
 * GuiGraphicsExtractor has no rounded/AA primitives and only takes Int coordinates. The old approach
 * faked corners with one `fill()` per pixel row, which (a) stair-stepped and (b) was slow: every GUI
 * element runs a layering search over the ones already placed, so hundreds of tiny fills cost the
 * square of their count. Now every rounded corner, ring and circle is a white shape rasterized ONCE by
 * Java2D with real anti-aliasing, cached, and drawn as a few tinted blits:
 *  - drawing happens on the PHYSICAL pixel grid (pose scaled down by k = physical px per layout unit,
 *    coordinates multiplied up by k), so every edge lands exactly on a screen pixel at any GUI Scale /
 *    resolution;
 *  - the textures use a LINEAR sampler, and Supersampling bakes them at 2x and lets the GPU average them
 *    down (a true 2x2 supersample of every curve); with it off they're baked at exactly 1 texel per pixel.
 * A rounded rect is 4 corner blits + 3 fills, no matter its size.
 *
 * ## Text: the bundled Inter TTF as signed distance fields
 * See [SdfFont] and the mixins it lists: TTF glyphs are stored as distance fields and the
 * `core/text.fsh` override rebuilds a one-pixel anti-aliased edge at any size, so text is smooth at every
 * scale without per-size font variants.
 */
object GGRenderer {

    /** Bound for the duration of one [frame] call; every draw method below reads this. */
    private var gg: GuiGraphicsExtractor? = null

    private val font: McFont get() = mc.font

    /**
     * Old call sites pass `NVGRenderer.defaultFont` as a trailing argument (it selected the custom
     * embedded TTF for NanoVG). Text always draws through `mc.font` now, so this is just a shim, kept
     * `null` and accepted-but-ignored by the `font` params below.
     */
    val defaultFont: Font? = null

    private fun ctx(): GuiGraphicsExtractor = gg ?: error("[GGRenderer] No active frame -- call GGRenderer.frame { } first")

    /** Rounds to the nearest pixel -- GuiGraphicsExtractor's draw calls are all Int-coordinate. */
    private fun Float.rt(): Int = round(this).toInt()

    fun devicePixelRatio(): Float =
        if (mc.window.screenWidth == 0) 1f else (mc.window.width / mc.window.screenWidth).toFloat()

    /** Runs [block] with [context] bound, inside its own pushed/popped pose so nothing leaks out. */
    fun frame(context: GuiGraphicsExtractor, block: () -> Unit) {
        check(gg == null) { "[GGRenderer] Already drawing a frame" }
        gg = context
        curScale = 1f
        scaleStack.clear()
        context.pose().pushMatrix()
        try {
            block()
        } finally {
            context.pose().popMatrix()
            gg = null
        }
    }

    // ---------------------------------------------------------------- transform tracking

    // Layout units -> GUI-scaled pixels, tracked through our own push/pop/scale so shapes, icons and
    // text can work out how many *physical* pixels one layout unit covers.
    private var curScale = 1f
    private val scaleStack = ArrayDeque<Float>()

    fun push() { ctx().pose().pushMatrix(); scaleStack.addLast(curScale) }
    fun pop() { ctx().pose().popMatrix(); curScale = scaleStack.removeLastOrNull() ?: curScale }
    fun scale(x: Float, y: Float) { ctx().pose().scale(x, y); curScale *= x }
    fun translate(x: Float, y: Float) { ctx().pose().translate(x, y) }
    fun rotate(radians: Float) { ctx().pose().rotate(radians) }

    /** Physical framebuffer pixels per current layout unit. */
    private fun physicalPerUnit(): Float = (curScale * mc.window.guiScale.toFloat()).coerceAtLeast(0.01f)

    /** Runs [block] in physical-pixel space: the pose is scaled by 1/k, so Int coords passed to
     *  GuiGraphicsExtractor are real screen pixels. [block] receives k to convert layout -> physical. */
    private inline fun physical(block: (Float) -> Unit) {
        val k = physicalPerUnit()
        val pose = ctx().pose()
        pose.pushMatrix()
        pose.scale(1f / k, 1f / k)
        try { block(k) } finally { pose.popMatrix() }
    }

    /** Rounds a layout coordinate onto the physical pixel grid (the GUI root pose has no translation). */
    private fun snap(v: Float): Float {
        val k = physicalPerUnit()
        return round(v * k) / k
    }

    // ---------------------------------------------------------------- scissor

    // GuiGraphicsExtractor keeps its own nesting/intersection stack.
    fun pushScissor(x: Float, y: Float, w: Float, h: Float) =
        ctx().enableScissor(x.rt(), y.rt(), (x + w).rt(), (y + h).rt())

    fun popScissor() = ctx().disableScissor()

    // ---------------------------------------------------------------- baked shape textures

    /** DynamicTexture sampled LINEAR + clamp-to-edge (vanilla's default is NEAREST). At an exact 1:1 texel
     *  match LINEAR equals NEAREST; anywhere else it averages instead of dropping/duplicating rows. */
    private class SmoothTexture(name: String, image: NativeImage) : DynamicTexture({ name }, image) {
        init {
            try {
                sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
            } catch (_: Throwable) {
                // keep DynamicTexture's own sampler rather than failing the draw
            }
        }
    }

    private val shapeCache = HashMap<String, Identifier>()
    private var textureCounter = 0

    /** Bake multiplier for shape textures: 2 = supersampled (2x2 averaged by LINEAR), 1 = exact pixel match. */
    private fun bakeScale(): Int = if (ClickGUIModule.supersampling) 2 else 1

    private fun register(prefix: String, image: NativeImage): Identifier {
        val id = Identifier.fromNamespaceAndPath("fish5", "gg_${prefix}_${textureCounter++}")
        mc.textureManager.register(id, SmoothTexture(id.toString(), image))
        return id
    }

    /** A white anti-aliased shape of [w]x[h] texels drawn by [draw], cached under [key]. Tinted at blit time. */
    private fun baked(key: String, w: Int, h: Int, draw: (Graphics2D) -> Unit): Identifier = shapeCache.getOrPut(key) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = java.awt.Color.WHITE
        draw(g)
        g.dispose()
        val native = NativeImage(w, h, false)
        for (y in 0 until h) for (x in 0 until w) native.setPixel(x, y, img.getRGB(x, y))
        register("shape", native)
    }

    /** Filled disc, [d] texels across. */
    private fun disc(d: Int): Identifier = baked("disc:$d", d, d) { it.fill(Ellipse2D.Float(0f, 0f, d.toFloat(), d.toFloat())) }

    /** Ring [d] texels across and [t] texels thick (outer circle minus inner circle). */
    private fun ring(d: Int, t: Int): Identifier = baked("ring:$d:$t", d, d) { g ->
        val area = Area(Ellipse2D.Float(0f, 0f, d.toFloat(), d.toFloat()))
        val inner = d - 2f * t
        if (inner > 0f) area.subtract(Area(Ellipse2D.Float(t.toFloat(), t.toFloat(), inner, inner)))
        g.fill(area)
    }

    /** Blits the [qx],[qy] quadrant (0/1 each) of a [tex] that is [texSize] texels square, into an [r]x[r] pixel cell. */
    private fun quadrant(tex: Identifier, texSize: Int, x: Int, y: Int, r: Int, qx: Int, qy: Int, color: Int) {
        val half = texSize / 2
        ctx().blit(RenderPipelines.GUI_TEXTURED, tex, x, y, (qx * half).toFloat(), (qy * half).toFloat(), r, r, half, half, texSize, texSize, color)
    }

    private fun visible(color: Int) = (color ushr 24) != 0

    // ---------------------------------------------------------------- shapes

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f) {
        if (!visible(color)) return
        physical { k ->
            val x0 = (x * k).rt(); val y0 = (y * k).rt(); val x1 = ((x + w) * k).rt(); val y1 = ((y + h) * k).rt()
            val pw = x1 - x0; val ph = y1 - y0
            if (pw <= 0 || ph <= 0) return@physical
            val r = (radius * k).rt().coerceIn(0, min(pw, ph) / 2)
            val c = ctx()
            if (r < 2) { c.fill(x0, y0, x1, y1, color); return@physical }
            val texSize = 2 * r * bakeScale()
            val tex = disc(texSize)
            quadrant(tex, texSize, x0, y0, r, 0, 0, color)
            quadrant(tex, texSize, x1 - r, y0, r, 1, 0, color)
            quadrant(tex, texSize, x0, y1 - r, r, 0, 1, color)
            quadrant(tex, texSize, x1 - r, y1 - r, r, 1, 1, color)
            if (pw > 2 * r) {
                c.fill(x0 + r, y0, x1 - r, y0 + r, color)
                c.fill(x0 + r, y1 - r, x1 - r, y1, color)
            }
            c.fill(x0, y0 + r, x1, y1 - r, color)
        }
    }

    /** Panel header/footer: only the top (or bottom) two corners are rounded. */
    fun drawHalfRoundedRect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float, roundTop: Boolean) {
        if (!visible(color)) return
        physical { k ->
            val x0 = (x * k).rt(); val y0 = (y * k).rt(); val x1 = ((x + w) * k).rt(); val y1 = ((y + h) * k).rt()
            val pw = x1 - x0; val ph = y1 - y0
            if (pw <= 0 || ph <= 0) return@physical
            val r = (radius * k).rt().coerceIn(0, min(pw / 2, ph))
            val c = ctx()
            if (r < 2) { c.fill(x0, y0, x1, y1, color); return@physical }
            val texSize = 2 * r * bakeScale()
            val tex = disc(texSize)
            if (roundTop) {
                quadrant(tex, texSize, x0, y0, r, 0, 0, color)
                quadrant(tex, texSize, x1 - r, y0, r, 1, 0, color)
                if (pw > 2 * r) c.fill(x0 + r, y0, x1 - r, y0 + r, color)
                c.fill(x0, y0 + r, x1, y1, color)
            } else {
                quadrant(tex, texSize, x0, y1 - r, r, 0, 1, color)
                quadrant(tex, texSize, x1 - r, y1 - r, r, 1, 1, color)
                if (pw > 2 * r) c.fill(x0 + r, y1 - r, x1 - r, y1, color)
                c.fill(x0, y0, x1, y1 - r, color)
            }
        }
    }

    /** Border. Straight edges are fills; corners are baked anti-aliased quarter-rings when [radius] > 0. */
    fun hollowRect(x: Float, y: Float, w: Float, h: Float, thickness: Float, color: Int, radius: Float = 0f) {
        if (!visible(color)) return
        physical { k ->
            val x0 = (x * k).rt(); val y0 = (y * k).rt(); val x1 = ((x + w) * k).rt(); val y1 = ((y + h) * k).rt()
            val pw = x1 - x0; val ph = y1 - y0
            if (pw <= 0 || ph <= 0) return@physical
            val t = (thickness * k).rt().coerceIn(1, max(1, min(pw, ph) / 2))
            val r = (radius * k).rt().coerceIn(0, min(pw, ph) / 2)
            val c = ctx()
            if (r < 2) {
                c.fill(x0, y0, x1, y0 + t, color)
                c.fill(x0, y1 - t, x1, y1, color)
                c.fill(x0, y0 + t, x0 + t, y1 - t, color)
                c.fill(x1 - t, y0 + t, x1, y1 - t, color)
                return@physical
            }
            val ss = bakeScale()
            val texSize = 2 * r * ss
            val tex = ring(texSize, min(t, r) * ss)
            quadrant(tex, texSize, x0, y0, r, 0, 0, color)
            quadrant(tex, texSize, x1 - r, y0, r, 1, 0, color)
            quadrant(tex, texSize, x0, y1 - r, r, 0, 1, color)
            quadrant(tex, texSize, x1 - r, y1 - r, r, 1, 1, color)
            c.fill(x0 + r, y0, x1 - r, y0 + t, color)
            c.fill(x0 + r, y1 - t, x1 - r, y1, color)
            c.fill(x0, y0 + r, x0 + t, y1 - r, color)
            c.fill(x1 - t, y0 + r, x1, y1 - r, color)
        }
    }

    /** Filled anti-aliased circle centred on ([x],[y]). */
    fun circle(x: Float, y: Float, radius: Float, color: Int) {
        if (!visible(color) || radius <= 0f) return
        physical { k ->
            val d = (2f * radius * k).rt().coerceAtLeast(1)
            val x0 = (x * k - d / 2f).rt()
            val y0 = (y * k - d / 2f).rt()
            val texSize = d * bakeScale()
            ctx().blit(RenderPipelines.GUI_TEXTURED, disc(texSize), x0, y0, 0f, 0f, d, d, texSize, texSize, texSize, texSize, color)
        }
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, thickness: Float, color: Int) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length <= 0f) return
        val c = ctx()
        c.pose().pushMatrix()
        c.pose().translate(x1, y1)
        c.pose().rotate(atan2(dy, dx))
        val half = (thickness / 2f).rt().coerceAtLeast(1)
        c.fill(0, -half, length.rt(), half, color)
        c.pose().popMatrix()
    }

    /** No blur primitive exists here, and flat-minimal doesn't want one -- intentional no-op. */
    fun dropShadow(x: Float, y: Float, width: Float, height: Float, blur: Float, spread: Float, radius: Float) = Unit

    fun gradientRect(x: Float, y: Float, w: Float, h: Float, color1: Int, color2: Int, gradient: Gradient, radius: Float = 0f) {
        when (gradient) {
            Gradient.TopToBottom -> ctx().fillGradient(x.rt(), y.rt(), (x + w).rt(), (y + h).rt(), color1, color2)
            Gradient.LeftToRight -> fillGradientHorizontal(x, y, w, h, color1, color2)
        }
    }

    /** No native horizontal-gradient primitive, so this blends in strips (capped so huge widths stay cheap). */
    private fun fillGradientHorizontal(x: Float, y: Float, w: Float, h: Float, colorLeft: Int, colorRight: Int) {
        val c = ctx()
        val steps = w.rt().coerceIn(1, 128)
        val stepW = w / steps
        val y0 = y.rt()
        val y1 = (y + h).rt()
        for (i in 0 until steps) {
            val t = if (steps <= 1) 0f else i / (steps - 1f)
            val sx = (x + i * stepW).rt()
            val ex = (x + (i + 1) * stepW).rt() + 1
            c.fill(sx, y0, ex, y1, lerpColor(colorLeft, colorRight, t))
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun mix(ca: Int, cb: Int) = (ca + (cb - ca) * t).toInt().coerceIn(0, 255)
        val aa = (a ushr 24) and 0xFF; val ar = (a ushr 16) and 0xFF; val ag = (a ushr 8) and 0xFF; val ab = a and 0xFF
        val ba = (b ushr 24) and 0xFF; val br = (b ushr 16) and 0xFF; val bg = (b ushr 8) and 0xFF; val bb = b and 0xFF
        return (mix(aa, ba) shl 24) or (mix(ar, br) shl 16) or (mix(ag, bg) shl 8) or mix(ab, bb)
    }

    // ---------------------------------------------------------------- text

    /** assets/fish5/font/gui.json -- the bundled Inter TTF (distance-field rendered, see [SdfFont]). */
    private val GUI_FONT = FontDescription.Resource(Identifier.fromNamespaceAndPath("fish5", "gui"))

    /** [s] in the Inter GUI font when Smooth Font is on, else plain (vanilla font). */
    private fun comp(s: String): Component =
        if (ClickGUIModule.smoothFont) Component.literal(s).withStyle { it.withFont(GUI_FONT) } else Component.literal(s)

    private fun scaleFor(size: Float) = size / font.lineHeight.toFloat()

    private fun drawLine(line: Component, x: Float, y: Float, size: Float, color: Int, shadow: Boolean) {
        val s = scaleFor(size)
        val c = ctx()
        c.pose().pushMatrix()
        c.pose().translate(snap(x), snap(y))
        c.pose().scale(s, s)
        c.text(font, line, 0, 0, color, shadow)
        c.pose().popMatrix()
    }

    fun text(text: String, x: Float, y: Float, size: Float, color: Int) {
        // The no-boolean text() overload defaults to Minecraft's built-in drop shadow, which balloons
        // into an offset ghost at this GUI's scale factors -- explicitly off; textShadow() wants it.
        drawLine(comp(text), x, y, size, color, false)
    }

    /** Overload kept so old call sites passing a (now-unused) [Font] still compile unchanged. */
    fun text(text: String, x: Float, y: Float, size: Float, color: Int, font: Font?) = text(text, x, y, size, color)

    fun textShadow(text: String, x: Float, y: Float, size: Float, color: Int, font: Font?) =
        drawLine(comp(text), x, y, size, color, true)

    fun textWidth(text: String, size: Float, font: Font? = null): Float =
        this.font.width(comp(text)) * scaleFor(size)

    fun drawWrappedString(text: String, x: Float, y: Float, w: Float, size: Float, color: Int, font: Font?, lineHeight: Float = 1f) {
        val f = this.font
        val s = scaleFor(size)
        val lines = f.split(comp(text), (w / s).rt().coerceAtLeast(1))
        val step = f.lineHeight * lineHeight * s
        val c = ctx()
        for ((i, line) in lines.withIndex()) {
            // Each line placed (and pixel-snapped) on its own so line spacing never lands between pixels.
            c.pose().pushMatrix()
            c.pose().translate(snap(x), snap(y + i * step))
            c.pose().scale(s, s)
            c.text(f, line, 0, 0, color, false)
            c.pose().popMatrix()
        }
    }

    fun wrappedTextBounds(text: String, w: Float, size: Float, font: Font?, lineHeight: Float = 1f): FloatArray {
        val f = this.font
        val s = scaleFor(size)
        val lines = f.split(comp(text), (w / s).rt().coerceAtLeast(1))
        val width = (lines.maxOfOrNull { f.width(it) } ?: 0) * s
        val height = lines.size * f.lineHeight * lineHeight * s
        return floatArrayOf(0f, 0f, width, height)
    }

    // ---------------------------------------------------------------- images & icons

    private class GpuImage(val id: Identifier, val width: Int, val height: Int)
    private val images = HashMap<Image, GpuImage>()
    private val iconCache = HashMap<Pair<Image, Int>, GpuImage>()
    private val svgText = HashMap<Image, String>()

    fun createImage(resourcePath: String): Image = Image(resourcePath)

    /**
     * Square SVG icon tinted [color] (draw the SVG in white). Rasterized by NanoSVG (anti-aliased) at the
     * physical size it lands on screen -- times the bake scale when Supersampling -- cached per size, and
     * blitted on the physical pixel grid through a LINEAR sampler, so it stays sharp at any GUI Scale.
     */
    fun icon(image: Image, x: Float, y: Float, size: Float, color: Int) {
        physical { k ->
            val d = (size * k).rt().coerceIn(2, 512)
            val texSize = d * bakeScale()
            val gpu = iconCache.getOrPut(image to texSize) { uploadSvgAt(image, texSize) }
            ctx().blit(RenderPipelines.GUI_TEXTURED, gpu.id, (x * k).rt(), (y * k).rt(), 0f, 0f, d, d, texSize, texSize, texSize, texSize, color)
        }
    }

    private fun uploadSvgAt(image: Image, px: Int): GpuImage {
        val text = svgText.getOrPut(image) { java.nio.charset.StandardCharsets.UTF_8.decode(image.buffer().duplicate()).toString() }
        val svg = nsvgParse(text, "px", 96f) ?: throw IllegalStateException("Failed to parse ${image.identifier}")
        val scale = px / svg.width().coerceAtLeast(1f)
        val buffer = org.lwjgl.system.MemoryUtil.memAlloc(px * px * 4)
        val rasterizer = nsvgCreateRasterizer()
        try {
            nsvgRasterize(rasterizer, svg, 0f, 0f, scale, buffer, px, px, px * 4)
        } finally {
            nsvgDeleteRasterizer(rasterizer)
            nsvgDelete(svg)
        }
        return uploadRgba(buffer, px, px, smooth = true)
    }

    /** The local player's face (skin head + hat layer); false if there's no player (e.g. title screen). */
    fun playerFace(x: Float, y: Float, size: Float): Boolean {
        val player = mc.player ?: return false
        physical { k ->
            PlayerFaceExtractor.extractRenderState(ctx(), player.skin, (x * k).rt(), (y * k).rt(), (size * k).rt())
        }
        return true
    }

    fun image(image: Image, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        val gpu = images.getOrPut(image) { uploadImage(image) }
        val c = ctx()
        c.pose().pushMatrix()
        c.pose().translate(x, y)
        c.pose().scale(w / gpu.width, h / gpu.height)
        c.blit(RenderPipelines.GUI_TEXTURED, gpu.id, 0, 0, 0f, 0f, gpu.width, gpu.height, gpu.width, gpu.height)
        c.pose().popMatrix()
    }

    /** Rasterizes the image's bytes (SVG via NanoSVG, PNG via STB) and registers it as a GPU texture. */
    private fun uploadImage(image: Image): GpuImage {
        val (buffer, width, height) = if (image.isSVG) rasterizeSVG(image) else rasterizePNG(image)
        return uploadRgba(buffer, width, height, smooth = false)
    }

    private fun uploadRgba(buffer: java.nio.ByteBuffer, width: Int, height: Int, smooth: Boolean): GpuImage {
        val native = NativeImage(NativeImage.Format.RGBA, width, height, false)
        for (py in 0 until height) {
            for (px in 0 until width) {
                val i = (py * width + px) * 4
                val r = buffer.get(i).toInt() and 0xFF
                val g = buffer.get(i + 1).toInt() and 0xFF
                val b = buffer.get(i + 2).toInt() and 0xFF
                val a = buffer.get(i + 3).toInt() and 0xFF
                native.setPixelABGR(px, py, (a shl 24) or (b shl 16) or (g shl 8) or r)
            }
        }
        memFree(buffer)
        val id = if (smooth) register("icon", native) else {
            val plain = Identifier.fromNamespaceAndPath("fish5", "gg_image_${textureCounter++}")
            mc.textureManager.register(plain, DynamicTexture({ plain.toString() }, native))
            plain
        }
        return GpuImage(id, width, height)
    }

    private fun rasterizePNG(image: Image): Triple<java.nio.ByteBuffer, Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        val channels = IntArray(1)
        val buffer = stbi_load_from_memory(image.buffer(), w, h, channels, 4)
            ?: throw NullPointerException("Failed to load image: ${image.identifier}")
        return Triple(buffer, w[0], h[0])
    }

    private fun rasterizeSVG(image: Image): Triple<java.nio.ByteBuffer, Int, Int> {
        val text = image.stream.use { it.bufferedReader().readText() }
        val svg = nsvgParse(text, "px", 96f) ?: throw IllegalStateException("Failed to parse ${image.identifier}")
        val width = svg.width().toInt()
        val height = svg.height().toInt()
        val buffer = org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4)
        val rasterizer = nsvgCreateRasterizer()
        try {
            nsvgRasterize(rasterizer, svg, 0f, 0f, 1f, buffer, width, height, width * 4)
        } finally {
            nsvgDeleteRasterizer(rasterizer)
            nsvgDelete(svg)
        }
        return Triple(buffer, width, height)
    }
}
