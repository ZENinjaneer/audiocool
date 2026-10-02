package com.kjwindham.audiocool.slides

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides, from the camera's picture a few times a second, when a new slide is up: the picture
 * changed, then held still. It looks at a coarse grid of the picture (each cell's brightness and how
 * much detail, such as text, it holds), allowing for the camera adjusting its exposure and for lights
 * flickering, and remembers the slides it saved, so going back to one doesn't save it again. A slide
 * that only gained something (the next bullet point appearing) is a better picture of the same slide,
 * not a new one; so is the picture after you zoom.
 *
 * Not thread-safe; [AutoSlides] calls it from one thread at a time.
 */
class SlideWatcher {
    sealed interface Decision {
        /** A new slide, up since [shownAt] (the clock [frame] is given): take its photo. */
        data class New(val slide: Int, val shownAt: Long) : Decision

        /** Slide [slide] again, with more on it (or zoomed): take its photo again, in place of the last. */
        data class Better(val slide: Int) : Decision
    }

    /**
     * The picture as a grid: each cell's average [brightness] and [detail] (edges: text), in levels of
     * 255; an average of [frames] camera frames.
     */
    class Grid(val cols: Int, val rows: Int, val brightness: FloatArray, val detail: FloatArray, val frames: Int = 1) {
        val size get() = brightness.size

        /** Nearly the brightest a cell is, which differences are measured against. */
        val bright: Float = max(16f, brightness.sortedArray()[size * 9 / 10])

        /** The detail of the quieter cells: the camera's own noise, where there's nothing to see. */
        val quiet: Float = detail.sortedArray()[size / 4]
    }

    private class Slide(val id: Int, var picture: Grid)

    private val slides = ArrayList<Slide>()
    private var current: Slide? = null
    private var nextId = 0

    private var last: Grid? = null
    private var lastFrameAt = -1L
    /** When the picture last started changing; -1 when it hasn't since the last decision. */
    private var changingSince = -1L
    /** How long the picture has been changing since the last decision: a click is brief, someone walking in isn't. */
    private var movingMs = 0L
    private var stillSince = -1L
    private var still: FloatArray? = null
    private var stillFrames = 0
    private var decided = false
    private var reframed = false
    private val motions = ArrayDeque<Int>()

    /** How many slides it has seen. */
    val count get() = slides.size

    fun frame(grid: Grid?, at: Long): Decision? {
        val sinceLast = if (lastFrameAt < 0) 0L else (at - lastFrameAt).coerceIn(0L, 1_000L)
        lastFrameAt = at
        val previous = last
        last = grid
        if (grid == null || previous == null || previous.size != grid.size) {
            // Nothing to see (dark, or a blank screen), or the first look: wait for a picture to settle.
            movingMs += sinceLast
            moved(at)
            if (grid != null) {
                // Something to see again: whatever's up came up now.
                changingSince = at
                startStill(grid, at)
            }
            return null
        }
        val motion = changedCells(previous, grid)
        val limit = motionLimit()
        motions.addLast(motion)
        if (motions.size > MOTION_HISTORY) motions.removeFirst()
        if (motion >= limit) {
            movingMs += sinceLast
            moved(at)
            return null
        }
        if (stillSince < 0) startStill(grid, at) else addStill(grid)
        if (decided) return null
        val quick = movingMs <= QUICK_MS
        // The first slide waits longer: time to prop the phone up.
        if (at - stillSince < if (quick && slides.isNotEmpty()) SETTLE_MS else SLOW_SETTLE_MS) return null
        decided = true
        val sum = still!!
        val n = grid.size
        val picture = Grid(grid.cols, grid.rows, FloatArray(n) { sum[it] / stillFrames }, FloatArray(n) { sum[n + it] / stillFrames }, stillFrames)
        val shownAt = if (changingSince < 0) stillSince else changingSince
        changingSince = -1
        movingMs = 0
        return decide(picture, shownAt, quick)
    }

    /** Someone zoomed: the next picture to settle is the same slide, framed anew. */
    fun reframed(at: Long) {
        reframed = true
        moved(at)
    }

    /** After a pause: whatever's up when it resumes is looked at afresh. */
    fun resume() {
        last = null
        lastFrameAt = -1
        stillSince = -1
        decided = false
        changingSince = -1
        movingMs = 0
    }

    /** Forgets [slide], whose photo couldn't be taken, so it's seen as new next time. */
    fun forget(slide: Int) {
        slides.removeAll { it.id == slide }
        if (current?.id == slide) current = slides.lastOrNull()
    }

    private fun moved(at: Long) {
        if (stillSince >= 0 || changingSince < 0) changingSince = at
        stillSince = -1
        decided = false
    }

    private fun startStill(grid: Grid, at: Long) {
        stillSince = at
        still = grid.brightness + grid.detail
        stillFrames = 1
    }

    private fun addStill(grid: Grid) {
        val sum = still!!
        val n = grid.size
        for (i in 0 until n) {
            sum[i] += grid.brightness[i]
            sum[n + i] += grid.detail[i]
        }
        stillFrames++
    }

    /** Movement: more cells changing than a pointer would, and well beyond the quietest recent frames. */
    private fun motionLimit(): Int {
        if (motions.size < 8) return MOTION_CELLS
        val floor = motions.sorted()[motions.size * 3 / 10]
        return max(MOTION_CELLS, NOISE_FACTOR * floor + 1)
    }

    private fun decide(picture: Grid, shownAt: Long, quick: Boolean): Decision? {
        // A slide seen before, or part of one (going back to it starts its bullet points over).
        val seen = slides.lastOrNull { changedCells(it.picture, picture) < SAME_CELLS || isBuild(picture, it.picture) }
        if (seen != null) {
            current = seen
            reframed = false
            return null
        }
        val showing = current
        if (showing != null && (reframed || quick && isBuild(showing.picture, picture))) {
            showing.picture = picture
            reframed = false
            return Decision.Better(showing.id)
        }
        reframed = false
        // A slow change to one big patch: someone standing in front, say.
        if (!quick && showing != null && looksLikeSomeone(showing.picture, picture)) return null
        val slide = Slide(nextId++, picture)
        slides += slide
        current = slide
        return Decision.New(slide.id, shownAt)
    }

    companion object {
        /** Grid columns; rows follow the picture's shape. A cell is about 10 px of a 640 px frame. */
        const val COLS = 64

        /** How long the picture must hold still after a quick change, and after a slow one. */
        const val SETTLE_MS = 1_500L
        const val SLOW_SETTLE_MS = 3_000L

        /** Changing for no longer than this since the last still picture: clicks (to the next slide or bullet), not someone walking in. */
        const val QUICK_MS = 2_000L

        /** Fewer cells than this changing is no movement: a laser pointer, a mouse pointer. */
        private const val MOTION_CELLS = 6
        private const val NOISE_FACTOR = 3
        private const val MOTION_HISTORY = 60

        /**
         * A cell changed: in detail (text) by this share of the picture's bright level, or in brightness
         * by this much more (big things: a picture, someone in front). Lights flickering can shift a dim
         * room's brightness a good deal, but hardly its detail.
         */
        private const val CELL_DETAIL = 0.05f
        private const val CELL_BRIGHTNESS = 0.25f

        /**
         * Detail changing by less than this share of the quiet cells' (the camera's noise) isn't a change:
         * several times what noise moves a cell's average by, from one frame to the next.
         */
        private const val NOISE_MARGIN = 0.6f

        /** Pictures with fewer cells changed than this are the same slide (a pointer, the slide number). */
        private const val SAME_CELLS = 8

        /** A build adds at most this much of the picture. */
        private const val MAX_BUILD = 0.35f

        /** A cell is plain (background) with up to this share of the bright level more detail than most of the picture. */
        private const val PLAIN = 0.02f

        /** The range of brightness a picture needs to hold a slide, in levels of 255. */
        private const val MIN_RANGE = 20f

        /**
         * The camera's picture (its brightness plane, [width] × [height], [rowStride] bytes a row) as a
         * grid; null when it's too flat to hold a slide.
         */
        fun grid(y: ByteBuffer, width: Int, height: Int, rowStride: Int, cols: Int = COLS): Grid? {
            if (width < 4 || height < 4) return null
            val rows = max(1, (cols * height + width / 2) / width)
            val n = cols * rows
            val sums = FloatArray(n)
            val edges = FloatArray(n)
            val counts = IntArray(n)
            // The edge at each pixel is the difference to the pixels two to the right and two down. Every
            // pixel counts: the fewer, the more the camera's noise shows in each cell.
            for (py in 0 until height - 2) {
                val row = py * rows / height * cols
                val base = py * rowStride
                val below = base + 2 * rowStride
                for (px in 0 until width - 2) {
                    val v = y.get(base + px).toInt() and 0xFF
                    val right = y.get(base + px + 2).toInt() and 0xFF
                    val down = y.get(below + px).toInt() and 0xFF
                    val cell = row + px * cols / width
                    sums[cell] += v.toFloat()
                    edges[cell] += (abs(right - v) + abs(down - v)).toFloat()
                    counts[cell]++
                }
            }
            for (i in 0 until n) {
                sums[i] /= max(1, counts[i])
                edges[i] /= max(1, counts[i])
            }
            val sorted = sums.sortedArray()
            if (sorted[n * 98 / 100] - sorted[n * 2 / 100] < MIN_RANGE) return null
            return Grid(cols, rows, sums, edges)
        }

        /**
         * How differently each cell looks in [b] than in [a], in thresholds: 1 or more is a change. The
         * exposure is matched first (the median brightness ratio), for the brightness along each camera
         * row, since lights flickering show as bands across its rows.
         */
        internal fun differences(a: Grid, b: Grid): FloatArray {
            val out = FloatArray(a.size)
            val all = FloatArray(a.size)
            var count = 0
            for (i in 0 until a.size) if (a.brightness[i] >= 8f) all[count++] = (b.brightness[i] + 1f) / (a.brightness[i] + 1f)
            // Detail (edges) changes only with the exposure, not with light added by a flicker; and by more
            // than the camera's noise could, which counts on a dim picture (less, the more frames each averages).
            val gain = median(all, count)
            val detailScale = max(gain * a.bright * CELL_DETAIL, NOISE_MARGIN * a.quiet / sqrt(minOf(a.frames, b.frames).toFloat()))
            val ratios = FloatArray(a.cols)
            for (r in 0 until a.rows) {
                var k = 0
                for (c in 0 until a.cols) {
                    val i = r * a.cols + c
                    if (a.brightness[i] >= 8f) ratios[k++] = (b.brightness[i] + 1f) / (a.brightness[i] + 1f)
                }
                val rowGain = if (k == 0) gain else median(ratios, k)
                val brightnessScale = rowGain * a.bright * CELL_BRIGHTNESS
                for (c in 0 until a.cols) {
                    val i = r * a.cols + c
                    out[i] = max(abs(b.brightness[i] - rowGain * a.brightness[i]) / brightnessScale, abs(b.detail[i] - gain * a.detail[i]) / detailScale)
                }
            }
            return out
        }

        private fun median(values: FloatArray, count: Int): Float = if (count == 0) 1f else values.copyOf(count).apply { sort() }[count / 2]

        /** How many cells differ between two pictures. */
        internal fun changedCells(a: Grid, b: Grid): Int {
            if (a.size != b.size) return a.size
            return differences(a, b).count { it >= 1f }
        }

        /** Whether what changed from [before] to [after] is one patch over a quarter of the picture tall: someone in front. */
        internal fun looksLikeSomeone(before: Grid, after: Grid): Boolean {
            val d = differences(before, after)
            val differs = BooleanArray(before.size) { d[it] >= 1f }
            return differs.count { it } < before.size / 2 && tallestPatch(differs, before.cols, before.rows) > before.rows / 4
        }

        /**
         * Whether [after] is [before] with something added: what was on it is still there, and what
         * changed was plain background before, in lines no taller than a few lines of text (a bullet
         * point appearing; not the slide changing, or someone standing in front of it).
         */
        internal fun isBuild(before: Grid, after: Grid): Boolean = buildCheck(before, after).isBuild

        internal data class BuildCheck(val changed: Int, val changedOnPlain: Int, val content: Int, val contentKept: Int, val tallest: Int, val isBuild: Boolean)

        internal fun buildCheck(before: Grid, after: Grid): BuildCheck {
            if (before.size != after.size) return BuildCheck(before.size, 0, 0, 0, before.rows, false)
            val d = differences(before, after)
            val differs = BooleanArray(before.size) { d[it] >= 1f }
            // Background has hardly more detail than the quiet cells (the camera's noise).
            val plain = before.quiet + max(0.5f * before.quiet, PLAIN * before.bright)
            var changed = 0
            var changedOnPlain = 0
            var content = 0
            var contentKept = 0
            for (i in 0 until before.size) {
                val isPlain = before.detail[i] <= plain
                if (!isPlain) {
                    content++
                    if (!differs[i]) contentKept++
                }
                if (differs[i]) {
                    changed++
                    if (isPlain) changedOnPlain++
                }
            }
            val tallest = tallestPatch(differs, before.cols, before.rows)
            val build = changed >= SAME_CELLS && changed <= MAX_BUILD * before.size && changedOnPlain >= 0.85f * changed &&
                contentKept >= 0.95f * content && tallest <= max(2, before.rows / 8)
            return BuildCheck(changed, changedOnPlain, content, contentKept, tallest, build)
        }

        /** The height, in rows, of the tallest patch of touching cells set in [cells]. */
        private fun tallestPatch(cells: BooleanArray, cols: Int, rows: Int): Int {
            val seen = BooleanArray(cells.size)
            val queue = IntArray(cells.size)
            var tallest = 0
            for (start in cells.indices) {
                if (!cells[start] || seen[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                seen[start] = true
                var top = start / cols
                var bottom = top
                while (head < tail) {
                    val i = queue[head++]
                    val x = i % cols
                    val y = i / cols
                    top = minOf(top, y)
                    bottom = max(bottom, y)
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
                        val next = ny * cols + nx
                        if (cells[next] && !seen[next]) {
                            seen[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                tallest = max(tallest, bottom - top + 1)
            }
            return tallest
        }
    }
}
