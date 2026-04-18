package com.druk.lmplayground.screenshots

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SignalCellular4Bar
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.ide.common.rendering.api.SessionParams
import com.druk.lmplayground.R
import com.druk.lmplayground.conversation.ConversationBar
import com.druk.lmplayground.conversation.Message
import com.druk.lmplayground.conversation.UserInput
import com.druk.lmplayground.models.ModelInfo
import com.druk.lmplayground.models.Model
import com.druk.lmplayground.models.ModelWithStatus
import com.druk.lmplayground.storage.ModelsScreen
import com.druk.lmplayground.storage.StorageInfo
import com.druk.lmplayground.theme.PlaygroundTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate

// ── Device frame using Pixel 9 Pro skin ────────────────────────────
// Layout from skin: frame 1408x2974, display at (60,61) size 1280x2856, corner_radius 109
private const val FRAME_WIDTH = 1408f
private const val FRAME_HEIGHT = 2974f
private const val DISPLAY_X = 60f
private const val DISPLAY_Y = 61f
private const val DISPLAY_W = 1280f
private const val DISPLAY_H = 2856f
private const val DISPLAY_CORNER_RADIUS = 109f

// Padding fractions relative to frame size
private const val PAD_LEFT = DISPLAY_X / FRAME_WIDTH
private const val PAD_TOP = DISPLAY_Y / FRAME_HEIGHT
private const val PAD_RIGHT = (FRAME_WIDTH - DISPLAY_X - DISPLAY_W) / FRAME_WIDTH
private const val PAD_BOTTOM = (FRAME_HEIGHT - DISPLAY_Y - DISPLAY_H) / FRAME_HEIGHT

// ── Fake status bar (matches camera hole height, covers that area) ─
@Composable
private fun FakeStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "10:10",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Wifi, null, Modifier.size(14.dp), tint = Color.White)
            Icon(Icons.Outlined.SignalCellular4Bar, null, Modifier.size(14.dp), tint = Color.White)
            Icon(Icons.Outlined.BatteryFull, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}

@Composable
private fun DeviceFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        val frameW = maxWidth
        val fullFrameH = frameW * (FRAME_HEIGHT / FRAME_WIDTH)

        // Full-size phone. If it overflows the parent, the bottom gets cut naturally.
        Box(modifier = Modifier.requiredSize(frameW, fullFrameH)) {
            // App content behind the frame - status bar covers camera hole area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = frameW * PAD_LEFT,
                        top = fullFrameH * PAD_TOP,
                        end = frameW * PAD_RIGHT,
                        bottom = fullFrameH * PAD_BOTTOM
                    )
                    .background(MaterialTheme.colorScheme.background)
            ) {
                FakeStatusBar()
                content()
            }
            // Device frame on top
            Image(
                painter = painterResource(R.drawable.device_frame_pixel9pro),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds
            )
        }
    }
}

// ── Marketing screenshot frame ─────────────────────────────────────
@Composable
private fun StoreScreenshotFrame(
    icon: ImageVector,
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A))
            .clipToBounds()
    ) {
        // Phone positioned below header; extends past bottom edge - gets clipped naturally.
        DeviceFrame(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 560.dp)
        ) {
            content()
        }
        // Header on top, drawn after so it's above the phone if they overlap
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = title,
                fontSize = 44.sp,
                fontWeight = FontWeight.Normal,
                fontFamily = FontFamily.Serif,
                color = Color.White,
                textAlign = TextAlign.Center,
                lineHeight = 52.sp,
                letterSpacing = (-0.5).sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = subtitle,
                fontSize = 22.sp,
                color = Color(0xFF888888),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

// ── Inline param slider (since ParamSlider is private) ─────────────
@Composable
private fun InlineParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    subtitle: String? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(
                        text = " ($subtitle)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = { },
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Test class ──────────────────────────────────────────────────────
@RunWith(Parameterized::class)
class StoreScreenshots(
    private val locale: String,
    private val localeName: String
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{1}")
        fun locales() = listOf(
            arrayOf("en", "English"),
            arrayOf("es", "Spanish"),
            arrayOf("pt", "Portuguese"),
            arrayOf("fr", "French"),
            arrayOf("de", "German"),
            arrayOf("it", "Italian"),
            arrayOf("pl", "Polish"),
            arrayOf("uk", "Ukrainian"),
            arrayOf("ro", "Romanian"),
            arrayOf("tr", "Turkish"),
            arrayOf("ar", "Arabic"),
            arrayOf("zh", "Chinese"),
            arrayOf("ja", "Japanese"),
            arrayOf("ko", "Korean"),
            arrayOf("in", "Indonesian"),
            arrayOf("hi", "Hindi"),
            arrayOf("vi", "Vietnamese"),
            arrayOf("th", "Thai"),
            arrayOf("nl", "Dutch"),
            arrayOf("iw", "Hebrew"),
            arrayOf("cs", "Czech"),
            arrayOf("sv", "Swedish"),
            arrayOf("bn", "Bengali"),
            arrayOf("ms", "Malay"),
            arrayOf("fil", "Filipino"),
            arrayOf("nb", "Norwegian"),
            arrayOf("da", "Danish"),
            arrayOf("fi", "Finnish")
        )
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_9_PRO.copy(locale = locale),
        renderingMode = SessionParams.RenderingMode.NORMAL,
        showSystemUi = false,
        useDeviceResolution = true
    )

    // On real Arabic-locale devices, Android flips LayoutDirection automatically.
    // Paparazzi's locale config doesn't — so we flip it here to match runtime behavior.
    @Composable
    private fun LocaleLayout(content: @Composable () -> Unit) {
        val direction = if (locale == "ar" || locale == "iw") LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            content()
        }
    }

    @Composable
    private fun gemma4Model(): ModelInfo {
        val efficient = stringResource(R.string.model_category_efficient)
        return ModelInfo(
            name = "Gemma 4 E2B",
            filename = "gemma-4-E2B-it-Q4_K_M.gguf",
            remoteUri = Uri.parse("https://huggingface.co/model.gguf"),
            releaseDate = LocalDate.parse("2026-03-25"),
            description = "Google \u00B7 $efficient \u00B7 3.11Gb",
            logoRes = R.drawable.logo_google
        )
    }

    // Scene 0: Hero / marketing slide
    @Test
    fun scene0_hero() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val titleText = buildAnnotatedString {
                    append(stringResource(R.string.screenshot_hero_title_prefix))
                    append("\n")
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(stringResource(R.string.screenshot_hero_title_emphasis))
                    }
                    append(" ")
                    append(stringResource(R.string.screenshot_hero_title_suffix))
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0A0A0A))
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(140.dp))
                    Image(
                        painter = painterResource(R.drawable.penrose_triangle),
                        contentDescription = null,
                        modifier = Modifier.size(140.dp)
                    )
                    Spacer(modifier = Modifier.height(56.dp))
                    // Title with serif font - lighter weight, more stretched
                    Text(
                        text = titleText,
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.Serif,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        lineHeight = 78.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    // Subtitle with sans-serif font
                    Text(
                        text = stringResource(R.string.screenshot_hero_subtitle),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFBBBBBB),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.screenshot_hero_providers_label),
                        fontSize = 16.sp,
                        color = Color(0xFF888888),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    // Stacked provider avatars - overlap each other with black border for separation
                    val logos = listOf(
                        R.drawable.logo_google,
                        R.drawable.logo_meta,
                        R.drawable.logo_qwen,
                        R.drawable.logo_microsoft,
                        R.drawable.logo_mistral,
                        R.drawable.logo_nvidia,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy((-24).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        logos.forEach { logo ->
                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF0A0A0A))
                            ) {
                                Image(
                                    painter = painterResource(logo),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(140.dp))
                }
            } }
        }
    }

    // Scene 1: Choose model
    @Test
    fun scene1_chooseModel() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val efficient = stringResource(R.string.model_category_efficient)
                val lightweight = stringResource(R.string.model_category_lightweight)
                val general = stringResource(R.string.model_category_general)
                val reasoning = stringResource(R.string.model_category_reasoning)
                val compactReasoning = stringResource(R.string.model_category_compact_reasoning)
                val enterprise = stringResource(R.string.model_category_enterprise)

                val models = listOf(
                    ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google \u00B7 $efficient \u00B7 3.11Gb", R.drawable.logo_google), true),
                    ModelWithStatus(ModelInfo("Llama 3.2 3B", "llama32.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-09-25"), "Meta \u00B7 $general \u00B7 1.88Gb", R.drawable.logo_meta), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba \u00B7 $general \u00B7 1.07Gb", R.drawable.logo_qwen), true),
                    ModelWithStatus(ModelInfo("Phi-4 mini", "phi4.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-15"), "Microsoft \u00B7 $lightweight \u00B7 2.49Gb", R.drawable.logo_microsoft), true),
                    ModelWithStatus(ModelInfo("Ministral 3B", "min3b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-10-16"), "Mistral \u00B7 $enterprise \u00B7 1.85Gb", R.drawable.logo_mistral), true),
                    ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA \u00B7 $compactReasoning \u00B7 2.84Gb", R.drawable.logo_nvidia), true),
                )

                StoreScreenshotFrame(
                    icon = Icons.Outlined.AutoAwesome,
                    title = stringResource(R.string.screenshot_choose_model),
                    subtitle = stringResource(R.string.screenshot_choose_model_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            ConversationBar(modelInfo = null, modelStatus = null)
                            androidx.compose.material3.Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column {
                                    models.filter { it.isDownloaded }.forEach { m ->
                                        Model(model = m.model) { }
                                    }
                                    androidx.compose.material3.HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    androidx.compose.material3.TextButton(
                                        onClick = { },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.AutoAwesome,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(stringResource(R.string.browse_more_models))
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }
            } }
        }
    }

    // Scene 2: Chat with thinking
    @Test
    fun scene2_chat() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val question = stringResource(R.string.screenshot_chat_question)
                val response = stringResource(R.string.screenshot_chat_response)

                val gemma4 = gemma4Model()
                StoreScreenshotFrame(
                    icon = Icons.Outlined.WifiOff,
                    title = stringResource(R.string.screenshot_chat),
                    subtitle = stringResource(R.string.screenshot_chat_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ConversationBar(modelInfo = gemma4, modelStatus = null)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message("User", question),
                                    isUserMe = true,
                                    showActions = false
                                )
                                com.druk.lmplayground.conversation.Message(
                                    msg = Message(
                                        author = "Assistant",
                                        content = "<think>Let me explain machine learning clearly and concisely.</think>\n$response",
                                        thinkingDurationSeconds = 3,
                                        thinkingTokens = 42,
                                        responseTokens = 156,
                                        responseDurationSeconds = 4.2f
                                    ),
                                    isUserMe = false,
                                    showActions = true
                                )
                            }
                            UserInput(onMessageSent = {})
                        }
                    }
                }
            } }
        }
    }

    // Scene 3: Generation parameters
    @Test
    fun scene3_generationParams() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val gemma4 = gemma4Model()
                StoreScreenshotFrame(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(R.string.screenshot_fine_tune),
                    subtitle = stringResource(R.string.screenshot_fine_tune_sub)
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Column {
                            ConversationBar(modelInfo = gemma4, modelStatus = null)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.generation_parameters),
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = stringResource(R.string.reset),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                // System prompt row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.system_prompt_current),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = stringResource(R.string.clear),
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                androidx.compose.material3.OutlinedCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.screenshot_system_prompt_sample),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        minLines = 3,
                                        maxLines = 3
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                InlineParamSlider(
                                    label = stringResource(R.string.context_size),
                                    value = 4096f,
                                    valueRange = 512f..8192f,
                                    valueDisplay = "4096"
                                )
                                InlineParamSlider(
                                    label = stringResource(R.string.thinking_budget),
                                    value = 1024f,
                                    valueRange = 64f..4096f,
                                    valueDisplay = stringResource(R.string.tokens_value, 1024)
                                )
                                InlineParamSlider(
                                    label = stringResource(R.string.temperature),
                                    value = 0.7f,
                                    valueRange = 0f..2f,
                                    valueDisplay = "0.70"
                                )
                                InlineParamSlider(
                                    label = stringResource(R.string.top_p),
                                    value = 0.9f,
                                    valueRange = 0f..1f,
                                    valueDisplay = "0.90"
                                )
                                InlineParamSlider(
                                    label = stringResource(R.string.repetition_penalty),
                                    value = 1.1f,
                                    valueRange = 1f..2f,
                                    valueDisplay = "1.10"
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.advanced),
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
            } }
        }
    }

    // Scene 4: Download models
    @Test
    fun scene4_modelsDownload() {
        paparazzi.snapshot {
            PlaygroundTheme(isDarkTheme = true) { LocaleLayout {
                val efficient = stringResource(R.string.model_category_efficient)
                val lightweight = stringResource(R.string.model_category_lightweight)
                val general = stringResource(R.string.model_category_general)
                val ultraLight = stringResource(R.string.model_category_ultra_lightweight)
                val thinking = stringResource(R.string.model_category_thinking)
                val reasoning = stringResource(R.string.model_category_reasoning)

                val models = listOf(
                    ModelWithStatus(ModelInfo("LFM2.5 350M", "lfm350.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Liquid AI \u00B7 $ultraLight \u00B7 267Mb", R.drawable.logo_liquid), true),
                    ModelWithStatus(ModelInfo("Gemma 4 E2B", "g4e2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-03-25"), "Google \u00B7 $efficient \u00B7 3.11Gb", R.drawable.logo_google), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 0.8B", "q35-08.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba \u00B7 $lightweight \u00B7 466Mb", R.drawable.logo_qwen), true),
                    ModelWithStatus(ModelInfo("Qwen 3.5 2B", "q35-2b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2026-02-27"), "Alibaba \u00B7 $general \u00B7 1.07Gb", R.drawable.logo_qwen), false),
                    ModelWithStatus(ModelInfo("Nemotron 3 Nano", "nem4b.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-12-15"), "NVIDIA \u00B7 $reasoning \u00B7 2.84Gb", R.drawable.logo_nvidia), false),
                    ModelWithStatus(ModelInfo("LFM2.5 1.2B", "lfm12.gguf", Uri.parse("https://x.com/m"), LocalDate.parse("2025-01-09"), "Liquid AI \u00B7 $thinking \u00B7 731Mb", R.drawable.logo_liquid), false),
                )

                StoreScreenshotFrame(
                    icon = Icons.Outlined.CloudDownload,
                    title = stringResource(R.string.screenshot_download_models),
                    subtitle = stringResource(R.string.screenshot_download_models_sub)
                ) {
                    ModelsScreen(
                        storageInfo = StorageInfo(
                            path = "/storage/emulated/0/Models",
                            usedBytes = 3_800_000_000L,
                            totalBytes = 64_000_000_000L,
                            availableBytes = 42_000_000_000L,
                            isCustomFolder = false
                        ),
                        allModels = models,
                        downloadingModels = emptyMap(),
                        snackbarMessage = null,
                        pendingMigration = null,
                        migrationProgress = null,
                        onBackClick = { },
                        onChangeFolderClick = { },
                        onDeleteModel = { },
                        onDownloadModel = { },
                        onCancelDownload = { },
                        onSnackbarDismiss = { },
                        onConfirmMigration = { },
                        onSkipMigration = { },
                        onCancelMigration = { }
                    )
                }
            } }
        }
    }
}
