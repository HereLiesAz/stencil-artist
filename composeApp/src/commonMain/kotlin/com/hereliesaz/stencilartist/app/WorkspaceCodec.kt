package com.hereliesaz.stencilartist.app

import com.hereliesaz.stencilartist.core.*
import com.hereliesaz.stencilartist.core.export.Paper
import com.hereliesaz.stencilartist.core.paint.PaintMode
import kotlin.io.encoding.Base64

/** A single local editing checkpoint. It contains no jobs, bitmaps, model handles or export handles. */
internal data class WorkspaceSnapshot(val stencil: StencilSnapshot, val ui: StudioUiSnapshot)

internal data class StencilSnapshot(
    val source: RgbaImage,
    val mode: SeparationMode,
    val toneParams: TonalParams,
    val colorParams: ColorParams,
    val removeBackground: Boolean,
    val depthAssistedBackground: Boolean,
    val lighting: LightingParams,
    val flatLighting: LightingParams,
    val originalLightCompensation: OriginalLightCompensation,
    val sourceAdjustments: ImageAdjustments,
    val preparedAdjustments: ImageAdjustments,
    val maskStrokes: List<MaskStroke>,
    val depthEdits: DepthEdits,
    val strokeBased: Boolean,
    val strokeEngine: StrokeEngine,
    val strokeWidthPixels: Int,
    val paintMode: PaintMode,
    val pickCount: Int,
    val savedCans: String,
    val savedAllowed: String,
    val shownSheet: Int?,
    val viewProcessedImage: Boolean,
    val keptUnused: List<Int>,
    val depthRequested: Boolean,
    val lateColours: List<LateColour>,
)

internal data class StudioUiSnapshot(
    val step: Int,
    val view: WorkspaceView,
    val maskDraft: List<MaskStroke>,
    val depthDraft: DepthEdits,
    val maskRestore: Boolean,
    val maskOverlay: Boolean,
    val maskRadius: Float,
    val maskRegion: Boolean,
    val depthMode: DepthBrush,
    val depthRadius: Float,
    val depthStrength: Float,
    val depthHardness: Float,
    val depthTarget: Float,
    val yaw: Float,
    val pitch: Float,
    val printWidthMm: Float,
    val safeBridgeWidthMm: Float,
    val cutterFormat: CutterFormat,
    val cutterFitChoice: Int,
    val cutterCustomWidthMm: Double,
    val cutterCustomHeightMm: Double,
    val cutterRollWidthMm: Double,
    val cutterEdgeMm: Double,
    val cutterOverlapMm: Double,
    val cutterCutLines: Boolean,
    val gcodeLaser: Boolean,
    val gcodeFeed: Double,
    val gcodeTravel: Double,
    val gcodePower: Double,
    val gcodePasses: Int,
    val gcodeSafeZ: Double,
    val gcodeCutZ: Double,
    val paper: Paper?,
    val customWidthMm: Double,
    val customHeightMm: Double,
    val landscape: Boolean,
    val mountMode: Int,
    val boardWidthIn: Double,
    val boardHeightIn: Double,
    val borderIn: Double,
)

internal data class LateColour(
    val sheet: Int,
    val color: Int,
    val width: Int,
    val height: Int,
    val mask: String,
)

/** Versioned, length-prefixed tokens. The image stores straight ARGB, without a lossy conversion. */
internal object WorkspaceCodec {
    private const val VERSION = "stencil-workspace/2"
    fun encode(value: WorkspaceSnapshot): String = Writer().apply {
        string(VERSION)
        writeStencilSnapshot(value.stencil)
        writeStudioUiSnapshot(value.ui)
    }.finish()

    /** Reject an incomplete, unsupported or invalid checkpoint before mutating the active project. */
    fun decode(encoded: String): WorkspaceSnapshot = Reader(encoded).run {
        val version = string()
        require(version == VERSION || version == "stencil-workspace/1") { "Unsupported workspace version" }
        legacy = version != VERSION
        val stencil = readStencilSnapshot()
        val ui = readStudioUiSnapshot()
        require(done()) { "Trailing checkpoint data" }
        require(ui.step in 0..9 && ui.maskRadius.isFinite() && ui.maskRadius >= .5f &&
            ui.depthRadius.isFinite() && ui.depthRadius >= .5f)
        require(stencil.shownSheet == null || stencil.shownSheet >= 0)
        require(stencil.pickCount in 1..ColorParams.MAX_SHEETS)
        require(stencil.strokeWidthPixels in StrokeStyler.MIN_BRUSH_WIDTH_PIXELS..StrokeStyler.MAX_BRUSH_WIDTH_PIXELS)
        require(ui.printWidthMm in 50f..1000f && ui.safeBridgeWidthMm in .5f..5f)
        require(ui.depthStrength in 0f..1f && ui.depthHardness in 0f..1f && ui.depthTarget in 0f..1f)
        WorkspaceSnapshot(stencil, ui)
    }
}

private class Writer {
    private val out = StringBuilder()
    fun string(value: String) { out.append(value.length).append(':').append(value) }
    fun int(value: Int) = string(value.toString())
    fun float(value: Float) = string(value.toString())
    fun double(value: Double) = string(value.toString())
    fun bool(value: Boolean) = int(if (value) 1 else 0)
    fun <T> list(values: List<T>, write: (T) -> Unit) {
        int(values.size); values.forEach(write)
    }
    fun finish(): String = out.toString()
}

private class Reader(private val input: String) {
    private var cursor = 0
    var legacy = false
    fun string(): String {
        val colon = input.indexOf(':', cursor)
        require(colon >= cursor && colon - cursor in 1..10)
        val length = input.substring(cursor, colon).toInt()
        require(length >= 0 && length <= input.length - colon - 1)
        cursor = colon + 1
        return input.substring(cursor, cursor + length).also { cursor += length }
    }
    fun int(): Int = string().toInt()
    fun float(): Float = string().toFloat().also { require(it.isFinite()) }
    fun double(): Double = string().toDouble().also { require(it.isFinite()) }
    fun bool(): Boolean = when (int()) { 0 -> false; 1 -> true; else -> error("Invalid boolean") }
    fun <T> list(read: () -> T): List<T> {
        val count = int()
        require(count in 0..minOf(2_000_000, (input.length - cursor) / 2))
        return List(count) { read() }
    }
    fun done(): Boolean = cursor == input.length
}

private fun Writer.writeRgbaImage(v: RgbaImage) {
    int(v.width); int(v.height)
    val bytes = ByteArray(v.pixels.size * 4)
    v.pixels.forEachIndexed { i, p ->
        for (channel in 0..3) bytes[i * 4 + channel] = (p ushr (channel * 8)).toByte()
    }
    string(Base64.encode(bytes))
}

private fun Reader.readRgbaImage(): RgbaImage {
    val width = int(); val height = int()
    require(width in 1..MAX_WORKING_EDGE && height in 1..MAX_WORKING_EDGE)
    val encoded = string()
    require(encoded.length == ((width * height * 4 + 2) / 3) * 4)
    val bytes = Base64.decode(encoded)
    require(bytes.size == width * height * 4)
    return RgbaImage(width, height, IntArray(width * height) { i ->
        var p = 0
        for (channel in 0..3) p = p or ((bytes[i * 4 + channel].toInt() and 255) shl (channel * 8))
        p
    })
}

/** Store the exact cut of a late recolour, so regenerated unrelated geometry never receives it. */
internal fun Separation.checkpointMask(sheet: Int): String {
    val bits = ByteArray((index.size + 7) / 8)
    for (i in index.indices) if (covers(sheet, i)) {
        bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
    }
    return Base64.encode(bits)
}

private fun Writer.writeTonalParams(v: TonalParams) {
    int(v.sheets)
    int(v.smoothing)
    int(v.closing)
    int(v.bias)
    string(v.base.name)
    bool(v.colors != null); v.colors?.let { item -> list(item) { element -> int(element) } }
}
private fun Reader.readTonalParams(): TonalParams = TonalParams(
    sheets = int(),
    smoothing = int(),
    closing = int(),
    bias = int(),
    base = Base.valueOf(string()),
    colors = if (bool()) list { int() } else null,
)

private fun Writer.writeColorParams(v: ColorParams) {
    int(v.sheets)
    int(v.smoothing)
    int(v.closing)
    string(v.order.name)
    bool(v.colors != null); v.colors?.let { item -> list(item) { element -> int(element) } }
}
private fun Reader.readColorParams(): ColorParams = ColorParams(
    sheets = int(),
    smoothing = int(),
    closing = int(),
    order = ColorOrder.valueOf(string()),
    colors = if (bool()) list { int() } else null,
)

private fun Writer.writeSceneLight(v: SceneLight) {
    string(v.kind.name)
    float(v.azimuth)
    float(v.elevation)
    float(v.distance)
    float(v.intensity)
    int(v.color)
    int(v.softness)
    bool(v.castsShadows)
    float(v.cone)
    float(v.targetX)
    float(v.targetY)
    float(v.targetZ)
}
private fun Reader.readSceneLight(): SceneLight = SceneLight(
    kind = SceneLightKind.valueOf(string()),
    azimuth = float(),
    elevation = float(),
    distance = float(),
    intensity = float(),
    color = int(),
    softness = int(),
    castsShadows = bool(),
    cone = float(),
    targetX = float(),
    targetY = float(),
    targetZ = float(),
)

private fun Writer.writeLightingParams(v: LightingParams) {
    bool(v.enabled)
    float(v.azimuth)
    float(v.elevation)
    float(v.intensity)
    float(v.ambient)
    float(v.depthStrength)
    float(v.extrusionThickness)
    bool(v.reverseDepth)
    bool(v.castShadows)
    bool(v.lights != null); v.lights?.let { item -> list(item) { element -> writeSceneLight(element) } }
    bool(v.useSourceTexture)
    int(v.surfaceColor)
    float(v.flatMatteMix)
    bool(v.receiverEnabled)
    int(v.receiverColor)
    float(v.receiverGap)
}
private fun Reader.readLightingParams(): LightingParams = LightingParams(
    enabled = bool(),
    azimuth = float(),
    elevation = float(),
    intensity = float(),
    ambient = float(),
    depthStrength = float(),
    extrusionThickness = float(),
    reverseDepth = bool(),
    castShadows = bool(),
    lights = if (bool()) list { readSceneLight() } else null,
    useSourceTexture = bool(),
    surfaceColor = int(),
    flatMatteMix = float(),
    receiverEnabled = bool(),
    receiverColor = int(),
    receiverGap = float(),
)

private fun Writer.writeOriginalLightCompensation(v: OriginalLightCompensation) {
    float(v.amount)
    float(v.azimuth)
    float(v.elevation)
    float(v.ambient)
    float(v.depthStrength)
    bool(v.reverseDepth)
}
private fun Reader.readOriginalLightCompensation(): OriginalLightCompensation = OriginalLightCompensation(
    amount = float(),
    azimuth = float(),
    elevation = float(),
    ambient = float(),
    depthStrength = float(),
    reverseDepth = bool(),
)

private fun Writer.writeToneCurve(v: ToneCurve) {
    int(v.black)
    int(v.shadow)
    int(v.midtone)
    int(v.highlight)
    int(v.white)
}
private fun Reader.readToneCurve(): ToneCurve = ToneCurve(
    black = int(),
    shadow = int(),
    midtone = int(),
    highlight = int(),
    white = int(),
)

private fun Writer.writeImageAdjustments(v: ImageAdjustments) {
    float(v.exposure)
    float(v.brightness)
    float(v.contrast)
    float(v.gamma)
    float(v.black)
    float(v.white)
    bool(v.autoLevels)
    float(v.shadows)
    float(v.highlights)
    float(v.saturation)
    float(v.warmth)
    float(v.tint)
    bool(v.grayscale)
    bool(v.invert)
    bool(v.despeckle)
    int(v.edgeSmooth)
    int(v.blur)
    float(v.sharpen)
    float(v.clarity)
    int(v.posterize)
    writeToneCurve(v.curve)
}
private fun Reader.readImageAdjustments(): ImageAdjustments = ImageAdjustments(
    exposure = float(),
    brightness = float(),
    contrast = float(),
    gamma = float(),
    black = float(),
    white = float(),
    autoLevels = bool(),
    shadows = float(),
    highlights = float(),
    saturation = float(),
    warmth = float(),
    tint = float(),
    grayscale = bool(),
    invert = bool(),
    despeckle = bool(),
    edgeSmooth = int(),
    blur = int(),
    sharpen = float(),
    clarity = float(),
    posterize = int(),
    curve = readToneCurve(),
)

private fun Writer.writeMaskPoint(v: MaskPoint) {
    float(v.x)
    float(v.y)
}
private fun Reader.readMaskPoint(): MaskPoint = MaskPoint(
    x = float(),
    y = float(),
)

private fun Writer.writeMaskStroke(v: MaskStroke) {
    list(v.points) { element -> writeMaskPoint(element) }
    float(v.radius)
    bool(v.restore)
    bool(v.region)
}
private fun Reader.readMaskStroke(): MaskStroke = MaskStroke(
    points = list { readMaskPoint() },
    radius = float(),
    restore = bool(),
    region = if (legacy) false else bool(),
)

private fun Writer.writeDepthStroke(v: DepthStroke) {
    list(v.points) { element -> writeMaskPoint(element) }
    float(v.radius)
    string(v.mode.name)
    float(v.strength)
    float(v.hardness)
    float(v.target)
}
private fun Reader.readDepthStroke(): DepthStroke = DepthStroke(
    points = list { readMaskPoint() },
    radius = float(),
    mode = DepthBrush.valueOf(string()),
    strength = float(),
    hardness = float(),
    target = float(),
)

private fun Writer.writeDepthEdits(v: DepthEdits) {
    list(v.strokes) { element -> writeDepthStroke(element) }
    int(v.smoothingPasses)
    float(v.edgeThreshold)
    float(v.flatten)
    float(v.flattenTarget)
    float(v.low)
    float(v.high)
    float(v.gamma)
}
private fun Reader.readDepthEdits(): DepthEdits = DepthEdits(
    strokes = list { readDepthStroke() },
    smoothingPasses = int(),
    edgeThreshold = float(),
    flatten = float(),
    flattenTarget = float(),
    low = float(),
    high = float(),
    gamma = float(),
)

private fun Writer.writePaper(v: Paper) {
    string(v.name)
    double(v.widthMm)
    double(v.heightMm)
}
private fun Reader.readPaper(): Paper = Paper(
    name = string(),
    widthMm = double(),
    heightMm = double(),
)

private fun Writer.writeStudioUiSnapshot(v: StudioUiSnapshot) {
    int(v.step)
    string(v.view.name)
    list(v.maskDraft) { element -> writeMaskStroke(element) }
    writeDepthEdits(v.depthDraft)
    bool(v.maskRestore)
    bool(v.maskOverlay)
    float(v.maskRadius)
    bool(v.maskRegion)
    string(v.depthMode.name)
    float(v.depthRadius)
    float(v.depthStrength)
    float(v.depthHardness)
    float(v.depthTarget)
    float(v.yaw)
    float(v.pitch)
    float(v.printWidthMm)
    float(v.safeBridgeWidthMm)
    string(v.cutterFormat.name)
    int(v.cutterFitChoice)
    double(v.cutterCustomWidthMm)
    double(v.cutterCustomHeightMm)
    double(v.cutterRollWidthMm)
    double(v.cutterEdgeMm)
    double(v.cutterOverlapMm)
    bool(v.cutterCutLines)
    bool(v.gcodeLaser)
    double(v.gcodeFeed)
    double(v.gcodeTravel)
    double(v.gcodePower)
    int(v.gcodePasses)
    double(v.gcodeSafeZ)
    double(v.gcodeCutZ)
    bool(v.paper != null); v.paper?.let { item -> writePaper(item) }
    double(v.customWidthMm)
    double(v.customHeightMm)
    bool(v.landscape)
    int(v.mountMode)
    double(v.boardWidthIn)
    double(v.boardHeightIn)
    double(v.borderIn)
}
private fun Reader.readStudioUiSnapshot(): StudioUiSnapshot = StudioUiSnapshot(
    step = int(),
    view = WorkspaceView.valueOf(string()),
    maskDraft = list { readMaskStroke() },
    depthDraft = readDepthEdits(),
    maskRestore = bool(),
    maskOverlay = bool(),
    maskRadius = float(),
    maskRegion = if (legacy) true else bool(),
    depthMode = DepthBrush.valueOf(string()),
    depthRadius = float(),
    depthStrength = float(),
    depthHardness = float(),
    depthTarget = float(),
    yaw = float(),
    pitch = float(),
    printWidthMm = float(),
    safeBridgeWidthMm = float(),
    cutterFormat = CutterFormat.valueOf(string()),
    cutterFitChoice = int(),
    cutterCustomWidthMm = double(),
    cutterCustomHeightMm = double(),
    cutterRollWidthMm = double(),
    cutterEdgeMm = double(),
    cutterOverlapMm = double(),
    cutterCutLines = bool(),
    gcodeLaser = bool(),
    gcodeFeed = double(),
    gcodeTravel = double(),
    gcodePower = double(),
    gcodePasses = int(),
    gcodeSafeZ = double(),
    gcodeCutZ = double(),
    paper = if (bool()) readPaper() else null,
    customWidthMm = double(),
    customHeightMm = double(),
    landscape = bool(),
    mountMode = int(),
    boardWidthIn = double(),
    boardHeightIn = double(),
    borderIn = double(),
)

private fun Writer.writeStencilSnapshot(v: StencilSnapshot) {
    writeRgbaImage(v.source)
    string(v.mode.name)
    writeTonalParams(v.toneParams)
    writeColorParams(v.colorParams)
    bool(v.removeBackground)
    bool(v.depthAssistedBackground)
    writeLightingParams(v.lighting)
    writeLightingParams(v.flatLighting)
    writeOriginalLightCompensation(v.originalLightCompensation)
    writeImageAdjustments(v.sourceAdjustments)
    writeImageAdjustments(v.preparedAdjustments)
    list(v.maskStrokes) { element -> writeMaskStroke(element) }
    writeDepthEdits(v.depthEdits)
    bool(v.strokeBased)
    string(v.strokeEngine.name)
    int(v.strokeWidthPixels)
    string(v.paintMode.name)
    int(v.pickCount)
    string(v.savedCans)
    string(v.savedAllowed)
    bool(v.shownSheet != null); v.shownSheet?.let { item -> int(item) }
    bool(v.viewProcessedImage)
    list(v.keptUnused) { element -> int(element) }
    bool(v.depthRequested)
    list(v.lateColours) { element -> writeLateColour(element) }
}
private fun Reader.readStencilSnapshot(): StencilSnapshot = StencilSnapshot(
    source = readRgbaImage(),
    mode = SeparationMode.valueOf(string()),
    toneParams = readTonalParams(),
    colorParams = readColorParams(),
    removeBackground = bool(),
    depthAssistedBackground = bool(),
    lighting = readLightingParams(),
    flatLighting = readLightingParams(),
    originalLightCompensation = readOriginalLightCompensation(),
    sourceAdjustments = readImageAdjustments(),
    preparedAdjustments = readImageAdjustments(),
    maskStrokes = list { readMaskStroke() },
    depthEdits = readDepthEdits(),
    strokeBased = bool(),
    strokeEngine = StrokeEngine.valueOf(string()),
    strokeWidthPixels = int(),
    paintMode = PaintMode.valueOf(string()),
    pickCount = int(),
    savedCans = string(),
    savedAllowed = string(),
    shownSheet = if (bool()) int() else null,
    viewProcessedImage = bool(),
    keptUnused = list { int() },
    depthRequested = bool(),
    lateColours = list { readLateColour() },
)

private fun Writer.writeLateColour(v: LateColour) {
    int(v.sheet)
    int(v.color)
    int(v.width)
    int(v.height)
    string(v.mask)
}
private fun Reader.readLateColour(): LateColour = LateColour(
    sheet = int(),
    color = int(),
    width = int(),
    height = int(),
    mask = string(),
)

