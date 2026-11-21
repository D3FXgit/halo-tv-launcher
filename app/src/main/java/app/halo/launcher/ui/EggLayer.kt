package app.halo.launcher.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import app.halo.launcher.R
import app.halo.launcher.easter.Daisy
import app.halo.launcher.easter.Egg
import app.halo.launcher.easter.EggEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Draws the playing Easter egg over Halo. [onDone] gets the event's id when it is finished. */
@Composable
fun EggLayer(event: EggEvent?, onDone: (Long) -> Unit) {
    val e = event ?: return
    key(e.id) {
        val done = { onDone(e.id) }
        when (e.egg) {
            Egg.SING -> SingEgg(done)
            Egg.SELF_DESTRUCT -> SelfDestructEgg(done)
            Egg.BEAM -> BeamEgg(done)
            Egg.SNOW -> SnowEgg(if (e.value > 0) e.value else 45, done)
            Egg.SKYNET -> SkynetEgg(done)
            Egg.COIN -> CoinEgg(e.value, done)
            Egg.DICE -> DiceEgg(e.value.coerceIn(1, 6), done)
            Egg.PARTY -> PartyEgg(done)
            Egg.GOODNIGHT -> GoodnightEgg(done)
            Egg.CREDITS -> CreditsEgg(done)
            Egg.RETRO -> RetroBanner(e.value == 1, done)
            Egg.BARREL_ROLL -> LaunchedEffect(Unit) { delay(1900); done() }
            else -> LaunchedEffect(Unit) { done() }
        }
    }
}

// ---- helpers ------------------------------------------------------------------------------

/** Seconds since this composable appeared, updated every frame. */
@Composable
private fun frameSeconds(): State<Float> {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { t.floatValue = (it - start) / 1e9f }
    }
    return t
}

/** A full-screen layer that takes every key (any key or Back ends it). */
@Composable
private fun KeyCatcher(onKey: () -> Unit, content: @Composable () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(50)
        runCatching { focus.requestFocus() }
    }
    BackHandler { onKey() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .onPreviewKeyEvent { k ->
                if (k.nativeKeyEvent.action == android.view.KeyEvent.ACTION_UP &&
                    k.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
                ) onKey()
                k.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK
            }
            .focusable(),
    ) { content() }
}

private class Bit(r: Random) {
    val x = r.nextFloat()
    val delay = r.nextFloat() * 1.6f
    val speed = 0.7f + r.nextFloat() * 0.9f
    val wobble = 1f + r.nextFloat() * 3f
    val spin = (r.nextFloat() - 0.5f) * 10f
    val size = 0.5f + r.nextFloat()
    val color = Color.hsv(r.nextFloat() * 360f, 0.75f, 1f)
}

@Composable
private fun Confetti(modifier: Modifier = Modifier) {
    val bits = remember { Random(System.nanoTime()).let { r -> List(160) { Bit(r) } } }
    val t by frameSeconds()
    Canvas(modifier.fillMaxSize()) {
        for (b in bits) {
            val age = t - b.delay
            if (age < 0f) continue
            val y = -40f + age * b.speed * size.height / 2.6f
            if (y > size.height + 40f) continue
            val x = b.x * size.width + sin(age * b.wobble) * 50f
            rotate(age * b.spin * 57f, Offset(x, y)) {
                drawRect(b.color, Offset(x - 9f * b.size, y - 5f * b.size), Size(18f * b.size, 10f * b.size))
            }
        }
    }
}

private val eggText = Color(0xFFF4F7FF)

// ---- the eggs -------------------------------------------------------------------------------

@Composable
private fun SingEgg(done: () -> Unit) {
    val player = remember { Daisy.Player() }
    val pos = remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(Unit) {
        val pcm = withContext(Dispatchers.Default) { Daisy.render() }
        player.start(pcm)
        while (true) {
            withFrameNanos { }
            pos.floatValue = player.position
            if (player.position >= Daisy.duration - 0.4f) break
        }
        delay(600)
        done()
    }
    DisposableEffect(Unit) { onDispose { player.stop() } }
    val t = pos.floatValue
    val starts = Daisy.starts
    val current = if (t < 0f) -1 else starts.indexOfLast { it <= t }
    // Lines of the song: indices of notes that start a line.
    val lineStarts = Daisy.song.indices.filter { Daisy.song[it].line }
    val lineIdx = lineStarts.indexOfLast { it <= current.coerceAtLeast(0) }.coerceAtLeast(0)
    fun lineRange(li: Int): IntRange {
        val a = lineStarts[li]
        val b = if (li + 1 < lineStarts.size) lineStarts[li + 1] - 1 else Daisy.song.lastIndex
        return a..b
    }
    KeyCatcher(onKey = { player.stop(); done() }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .padding(bottom = 90.px)
                    .width(1500.px)
                    .glass(RoundedCornerShape(40.px), strong = true, fillAlpha = 0.86f)
                    .padding(horizontal = 56.px, vertical = 40.px),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    stringResource(R.string.egg_sing_title),
                    style = hstyle(22, FontWeight.SemiBold, Color(0xFF7FE3FF), tracking = 0.08f),
                )
                Spacer(Modifier.height(18.px))
                val range = lineRange(lineIdx)
                BasicText(
                    buildAnnotatedString {
                        for (i in range) {
                            val sung = i <= current
                            withStyle(SpanStyle(color = if (sung) Color(0xFF7FE3FF) else eggText.copy(alpha = 0.55f))) {
                                append(Daisy.song[i].text)
                            }
                        }
                    },
                    style = hstyle(54, FontWeight.Bold, eggText).copy(textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
                )
                if (lineIdx + 1 < lineStarts.size) {
                    Spacer(Modifier.height(12.px))
                    BasicText(
                        lineRange(lineIdx + 1).joinToString("") { Daisy.song[it].text },
                        style = hstyle(30, FontWeight.Medium, eggText.copy(alpha = 0.4f)).copy(textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelfDestructEgg(done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(9_000); done() }
    if (t < 5f) {
        val pulse = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 2f)
        Box(
            Modifier.fillMaxSize().background(Color(0xFFE0121A).copy(alpha = 0.18f + 0.3f * pulse)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(stringResource(R.string.egg_self_destruct_label), style = hstyle(40, FontWeight.Bold, eggText, tracking = 0.2f))
                BasicText("${5 - t.toInt()}", style = hstyle(300, FontWeight.ExtraBold, eggText))
            }
        }
    } else {
        Confetti()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(
                stringResource(R.string.egg_just_kidding),
                Modifier.graphicsLayer { alpha = ((t - 5f) * 2f).coerceIn(0f, 1f) * ((9f - t) * 1.5f).coerceIn(0f, 1f) },
                style = hstyle(110, FontWeight.ExtraBold, eggText),
            )
        }
    }
}

@Composable
private fun BeamEgg(done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(3_600); done() }
    val cols = remember { Random(7).let { r -> List(70) { r.nextFloat() to r.nextFloat() } } }
    // Strongest while the screen is "dematerialised" (Halo's root fades out and in meanwhile).
    val strength = (sin((t / 3.4f).coerceIn(0f, 1f) * PI.toFloat())).coerceAtLeast(0f)
    Canvas(Modifier.fillMaxSize()) {
        for ((i, c) in cols.withIndex()) {
            val x = c.first * size.width
            var y = (c.second * size.height + t * 260f * (1 + i % 3)) % size.height
            repeat(9) { k ->
                val flicker = 0.5f + 0.5f * sin(t * 18f + i * 1.7f + k)
                drawCircle(
                    Color(0xFFBFE9FF).copy(alpha = (0.85f * strength * flicker).coerceIn(0f, 1f)),
                    radius = 2.5f + 2.5f * flicker,
                    center = Offset(x + sin(t * 6f + k) * 6f, (y + k * 34f) % size.height),
                )
            }
            y += 1f
        }
        drawRect(Color(0xFF9FDcFF).copy(alpha = 0.18f * strength))
    }
}

private class Flake(r: Random) {
    val x = r.nextFloat()
    val y = r.nextFloat()
    val speed = 40f + r.nextFloat() * 90f
    val size = 2f + r.nextFloat() * 5f
    val sway = 0.5f + r.nextFloat() * 1.5f
}

@Composable
private fun SnowEgg(seconds: Int, done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(seconds * 1000L); done() }
    val flakes = remember { Random(System.nanoTime()).let { r -> List(180) { Flake(r) } } }
    val fade = (t / 3f).coerceIn(0f, 1f) * ((seconds - t) / 3f).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        for (f in flakes) {
            val y = (f.y * size.height + t * f.speed) % (size.height + 20f) - 10f
            val x = f.x * size.width + sin(t * f.sway + f.x * 10f) * 30f
            drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = f.size, center = Offset(x, y))
        }
    }
}

@Composable
private fun SkynetEgg(done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(1_800); done() }
    val a = sin((t / 1.6f).coerceIn(0f, 1f) * PI.toFloat())
    Box(Modifier.fillMaxSize().background(Color(0xFFD0101A).copy(alpha = 0.45f * a)))
}

@Composable
private fun CoinEgg(side: Int, done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(4_200); done() }
    val flipping = t < 1.8f
    val turns = if (flipping) t / 1.8f * 7f else 7f + side * 0.5f
    val showHeads = ((turns * 2).toInt() % 2 == 0)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(300.px)
                    .graphicsLayer {
                        rotationY = turns * 360f
                        cameraDistance = 14f * density
                        translationY = if (flipping) -sin(t / 1.8f * PI.toFloat()) * 260f else 0f
                    }
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFFFE9A0), Color(0xFFE2A93B), Color(0xFF9C6A12)))),
                contentAlignment = Alignment.Center,
            ) {
                if (showHeads) {
                    Canvas(Modifier.size(150.px)) { drawCircle(Color(0xFF7A4E05), radius = size.minDimension / 2.4f, style = Stroke(width = size.minDimension / 12f)) }
                } else {
                    BasicText("½", style = hstyle(120, FontWeight.ExtraBold, Color(0xFF7A4E05)))
                }
            }
            Spacer(Modifier.height(40.px))
            if (!flipping) {
                BasicText(
                    stringResource(if (side == 0) R.string.egg_heads else R.string.egg_tails),
                    style = hstyle(72, FontWeight.ExtraBold, eggText),
                )
            }
        }
    }
}

/** Pip positions (0–2 grid) of a die face. */
private val pips = mapOf(
    1 to listOf(1 to 1), 2 to listOf(0 to 0, 2 to 2), 3 to listOf(0 to 0, 1 to 1, 2 to 2),
    4 to listOf(0 to 0, 2 to 0, 0 to 2, 2 to 2), 5 to listOf(0 to 0, 2 to 0, 1 to 1, 0 to 2, 2 to 2),
    6 to listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1, 0 to 2, 2 to 2),
)

@Composable
private fun DiceEgg(face: Int, done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(4_000); done() }
    val rolling = t < 1.5f
    val shown = if (rolling) ((t * 11).toInt() * 7 + 3) % 6 + 1 else face
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier
                    .size(260.px)
                    .graphicsLayer { rotationZ = if (rolling) sin(t * 20f) * 25f else 0f }
                    .clip(RoundedCornerShape(46.px))
                    .background(Color(0xFFF7F8FB)),
            ) {
                val cell = size.width / 3f
                for ((gx, gy) in pips.getValue(shown)) {
                    drawCircle(Color(0xFF15171C), radius = cell * 0.3f, center = Offset(cell * (gx + 0.5f), cell * (gy + 0.5f)))
                }
            }
            Spacer(Modifier.height(40.px))
            if (!rolling) BasicText("$face", style = hstyle(72, FontWeight.ExtraBold, eggText))
        }
    }
}

@Composable
private fun PartyEgg(done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(10_000); done() }
    val beat = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() * 2f)
    val fade = (t / 0.6f).coerceIn(0f, 1f) * ((10f - t) / 1f).coerceIn(0f, 1f)
    val hues = List(6) { Color.hsv((t * 90f + it * 60f) % 360f, 0.85f, 1f) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade * (0.18f + 0.22f * beat) }.background(Brush.linearGradient(hues)))
    Confetti()
}

private class Star(r: Random) {
    val x = r.nextFloat()
    val y = r.nextFloat()
    val size = 0.8f + r.nextFloat() * 2.2f
    val speed = 0.5f + r.nextFloat() * 2f
    val phase = r.nextFloat() * 6f
}

@Composable
private fun GoodnightEgg(done: () -> Unit) {
    val t by frameSeconds()
    val stars = remember { Random(11).let { r -> List(220) { Star(r) } } }
    KeyCatcher(onKey = done) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (t / 1.8f).coerceIn(0f, 1f) }) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF03040B), Color(0xFF0B1230), Color(0xFF141B3D))))
                for (s in stars) {
                    val tw = 0.45f + 0.55f * sin(t * s.speed + s.phase)
                    drawCircle(Color.White.copy(alpha = tw.coerceIn(0f, 1f)), radius = s.size, center = Offset(s.x * size.width, s.y * size.height * 0.85f))
                }
                // Crescent moon.
                val c = Offset(size.width * 0.78f, size.height * 0.22f)
                val r = size.height * 0.07f
                drawCircle(Color(0xFFFFF4D6), radius = r, center = c)
                drawCircle(Color(0xFF070A1C), radius = r * 0.9f, center = c + Offset(r * 0.45f, -r * 0.2f))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(
                    stringResource(R.string.egg_goodnight_title),
                    Modifier.graphicsLayer { alpha = ((t - 1f) / 1.5f).coerceIn(0f, 1f) },
                    style = hstyle(96, FontWeight.Bold, eggText),
                )
                Spacer(Modifier.height(20.px))
                BasicText(
                    stringResource(R.string.egg_goodnight_hint),
                    Modifier.graphicsLayer { alpha = ((t - 3f) / 1.5f).coerceIn(0f, 0.6f) },
                    style = hstyle(24, FontWeight.Medium, eggText),
                )
            }
        }
    }
}

@Composable
private fun CreditsEgg(done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(32_000); done() }
    val lines: List<Pair<String, Int>> = listOf(
        "HALO" to 120,
        stringResource(R.string.egg_credits_tagline) to 30,
        "" to 60,
        stringResource(R.string.egg_credits_created) to 26,
        "D3X" to 84,
        "" to 50,
        stringResource(R.string.egg_credits_built) to 26,
        "Kotlin · Jetpack Compose" to 40,
        "" to 50,
        stringResource(R.string.egg_credits_pictures) to 26,
        "Wikimedia Commons · NASA · Unsplash" to 40,
        "" to 50,
        stringResource(R.string.egg_credits_voice) to 26,
        "Google Gemini" to 40,
        "" to 50,
        stringResource(R.string.egg_credits_song) to 26,
        "Daisy Bell (Harry Dacre, 1892)" to 40,
        "" to 90,
        stringResource(R.string.egg_credits_thanks) to 48,
    )
    KeyCatcher(onKey = done) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xF2020309), Color(0xF20A0F24))))) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (1100.px.toPx() - t * 70.px.toPx()).roundToInt()) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.px),
            ) {
                for ((text, size) in lines) {
                    if (text.isEmpty()) Spacer(Modifier.height(size.px))
                    else BasicText(
                        text,
                        style = hstyle(size, if (size >= 40) FontWeight.Bold else FontWeight.Medium, if (size <= 30) eggText.copy(alpha = 0.6f) else eggText,
                            tracking = if (size >= 84) 0.12f else 0f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RetroBanner(on: Boolean, done: () -> Unit) {
    val t by frameSeconds()
    LaunchedEffect(Unit) { delay(2_600); done() }
    Box(Modifier.fillMaxSize().padding(top = 70.px), contentAlignment = Alignment.TopCenter) {
        BasicText(
            stringResource(if (on) R.string.egg_retro_on else R.string.egg_retro_off),
            Modifier
                .graphicsLayer { alpha = (t * 4f).coerceIn(0f, 1f) * ((2.6f - t) * 3f).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(horizontal = 36.px, vertical = 18.px),
            style = hstyle(40, FontWeight.Bold, Color(0xFF7CFF6B), tracking = 0.15f).copy(fontFamily = FontFamily.Monospace),
        )
    }
}

// ---- retro look (Konami code) -----------------------------------------------------------------

private const val RETRO_SHADER = """
uniform shader content;
uniform float cell;
half4 main(float2 p) {
    float2 q = (floor(p / cell) + 0.5) * cell;
    half4 c = content.eval(q);
    c.rgb = floor(c.rgb * 6.0 + 0.5) / 6.0;
    float line = mod(p.y, cell) < cell * 0.3 ? 0.72 : 1.0;
    return half4(c.rgb * line, c.a);
}
"""

/** Chunky pixels, a reduced palette and scanlines (Android 13+); scanlines only on older TVs. */
@Composable
fun Modifier.retroLook(on: Boolean): Modifier {
    if (!on) return this
    if (Build.VERSION.SDK_INT >= 33) {
        val shader = remember { RuntimeShader(RETRO_SHADER) }
        return this.graphicsLayer {
            shader.setFloatUniform("cell", 6f * (size.width / 1920f).coerceAtLeast(0.5f))
            renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
    return this.drawWithContent {
        drawContent()
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.28f), Offset(0f, y), Size(size.width, 2f))
            y += 6f
        }
    }
}
