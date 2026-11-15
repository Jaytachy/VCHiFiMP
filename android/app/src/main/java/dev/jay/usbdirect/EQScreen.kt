package dev.jay.usbdirect

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

private const val EQ_DB_RANGE = 15.0
private const val EQ_FMIN = 20.0
private const val EQ_FMAX = 20000.0

private fun typeLabel(type: Int): String = when (type) {
    1 -> "Low shelf"
    2 -> "High shelf"
    3 -> "HPF"
    4 -> "LPF"
    else -> "Peak"
}

private fun fmtDb(v: Double): String = "%+.1f dB".format(v)

@Composable
fun EqScreen(
    transportFlow: StateFlow<TransportState>?,
    enabled: Boolean,
    config: DspConfig,
    presetName: String,
    userPresets: List<EqPreset>,
    importMessage: String?,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onConfig: (DspConfig) -> Unit,
    onPresetName: (String) -> Unit,
    onSavePreset: (String, DspConfig) -> Unit,
    onRenamePreset: (String, String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onImport: () -> Unit,
    onReset: () -> Unit
) {
    val transport = rememberTransport(transportFlow)
    val rate = if (transport.deviceRate > 0) transport.deviceRate.toDouble() else 48000.0

    val peq = config.bands.filter { it.type in 0..2 }.take(10)
    val hpf = config.bands.firstOrNull { it.type == 3 }
    val lpf = config.bands.firstOrNull { it.type == 4 }

    var selected by remember { mutableStateOf(0) }
    LaunchedEffect(peq.size) {
        if (peq.isEmpty()) selected = 0 else if (selected > peq.lastIndex) selected = peq.lastIndex
    }

    var dialog by remember { mutableStateOf<String?>(null) }
    var nameDraft by remember { mutableStateOf("") }

    fun commit(newPeq: List<DspBand> = peq, newHpf: DspBand? = hpf, newLpf: DspBand? = lpf) {
        onConfig(
            config.copy(bands = newPeq + listOfNotNull(newHpf, newLpf))
        )
    }

    fun replaceBand(i: Int, b: DspBand) {
        if (i !in peq.indices) return
        val list = peq.toMutableList()
        list[i] = b
        commit(newPeq = list)
    }

    val builtinNames = DspPresets.builtin.map { it.first }
    val isUserPreset = userPresets.any { it.name == presetName }
    val headroom = DspMath.maxBoostDb(config, rate)
    val effectivePreamp = DspMath.effectivePreampDb(config, rate)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 200.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Equalizer", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Parametric EQ · USB DAC path",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Equalizer", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (enabled) "DSP active · ${config.bands.count { it.enabled }} bands"
                            else "DSP bypassed · native passthrough untouched",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!enabled) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    "Bit-perfect bypass",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabled)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Response", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "±15 dB · ${rate.toInt()} Hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    EqCurve(
                        config = config,
                        rate = rate,
                        peq = peq,
                        selected = selected,
                        onSelect = { selected = it },
                        onBandChange = { i, b -> replaceBand(i, b) },
                        modifier = Modifier.fillMaxWidth().height(190.dp)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        listOf("20", "100", "1k", "10k", "20k").forEach {
                            Text(
                                it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (peq.isEmpty()) "No PEQ bands" else "Band ${selected + 1}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(Modifier.weight(1f))
                        if (peq.size < 10) {
                            TextButton(onClick = {
                                val b = DspBand(0, 1000.0, 1.0, 0.0)
                                commit(newPeq = peq + b)
                                selected = peq.size
                            }) { Text("Add band") }
                        }
                        if (peq.isNotEmpty()) {
                            IconButton(onClick = {
                                val list = peq.toMutableList()
                                list.removeAt(selected)
                                commit(newPeq = list)
                                if (selected > list.lastIndex) selected = list.lastIndex.coerceAtLeast(0)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove band")
                            }
                        }
                    }

                    if (peq.isEmpty()) {
                        Text(
                            "Import AutoEQ or add a band to start shaping the curve.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (selected in peq.indices) {
                        val band = peq[selected]
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Peak", "Low shelf", "High shelf").forEachIndexed { i, label ->
                                FilterChip(
                                    selected = band.type == i,
                                    onClick = { replaceBand(selected, band.copy(type = i)) },
                                    label = { Text(label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        EqSlider(
                            "Fc", "${band.fc.toInt()} Hz",
                            value = log10(band.fc.coerceIn(EQ_FMIN, EQ_FMAX)).toFloat(),
                            range = log10(EQ_FMIN).toFloat()..log10(EQ_FMAX).toFloat(),
                            onChange = {
                                replaceBand(
                                    selected,
                                    band.copy(fc = 10.0.pow(it.toDouble()).coerceIn(EQ_FMIN, EQ_FMAX))
                                )
                            }
                        )
                        EqSlider(
                            "Gain", fmtDb(band.gainDb),
                            value = band.gainDb.toFloat(),
                            range = -15f..15f,
                            onChange = { replaceBand(selected, band.copy(gainDb = it.toDouble())) }
                        )
                        EqSlider(
                            "Q", "%.2f".format(band.q),
                            value = band.q.toFloat().coerceIn(0.1f, 10f),
                            range = 0.1f..10f,
                            onChange = { replaceBand(selected, band.copy(q = it.toDouble())) }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Band enabled", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Switch(
                                checked = band.enabled,
                                onCheckedChange = {
                                    replaceBand(selected, band.copy(enabled = it))
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("High-pass / Low-pass", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    FilterRow(
                        label = "HPF",
                        band = hpf,
                        defaultFc = 30.0,
                        type = 3,
                        rate = rate,
                        onChange = { commit(newHpf = it) }
                    )
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    FilterRow(
                        label = "LPF",
                        band = lpf,
                        defaultFc = 16000.0,
                        type = 4,
                        rate = rate,
                        onChange = { commit(newLpf = it) }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preamp & headroom", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "effective ${fmtDb(effectivePreamp)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    EqSlider(
                        "Preamp", fmtDb(config.preampDb),
                        value = config.preampDb.toFloat(),
                        range = -12f..12f,
                        onChange = { onConfig(config.copy(preampDb = it.toDouble())) }
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto headroom", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (config.autoHeadroom) "−%.1f dB from max band boost".format(headroom)
                                else "Manual preamp only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.autoHeadroom,
                            onCheckedChange = { onConfig(config.copy(autoHeadroom = it)) }
                        )
                    }
                    if (!config.autoHeadroom && config.preampDb + headroom > 0.0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Clipping possible: boost exceeds 0 dBFS",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFFD27A)
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Presets", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            presetName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DspPresets.builtin) { (name, cfg) ->
                            FilterChip(
                                selected = presetName == name,
                                onClick = {
                                    onPresetName(name)
                                    onConfig(cfg)
                                },
                                label = { Text(name) }
                            )
                        }
                        items(userPresets) { p ->
                            FilterChip(
                                selected = presetName == p.name,
                                onClick = {
                                    onPresetName(p.name)
                                    onConfig(p.config)
                                },
                                label = { Text(p.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            nameDraft = if (presetName in builtinNames) "$presetName custom" else presetName
                            dialog = "save"
                        }) { Text("Save") }
                        TextButton(
                            enabled = isUserPreset,
                            onClick = { nameDraft = presetName; dialog = "rename" }
                        ) { Text("Rename") }
                        TextButton(
                            enabled = isUserPreset,
                            onClick = { onDeletePreset(presetName) }
                        ) { Text("Delete") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Import AutoEQ .txt") }
                        TextButton(onClick = onReset) { Text("Reset") }
                    }
                    if (!importMessage.isNullOrEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            importMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "DSP applies to the USB DAC path only; mixer output is untouched.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (dialog != null) {
        val save = dialog == "save"
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (save) "Save preset" else "Rename preset") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = nameDraft.trim().isNotEmpty(),
                    onClick = {
                        val n = nameDraft.trim()
                        if (n.isNotEmpty()) {
                            if (save) {
                                onSavePreset(n, config)
                                onPresetName(n)
                            } else {
                                onRenamePreset(presetName, n)
                                onPresetName(n)
                            }
                        }
                        dialog = null
                    }
                ) { Text(if (save) "Save" else "Rename") }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EqSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(Modifier.padding(top = 6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                valueText, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive),
               onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun FilterRow(
    label: String,
    band: DspBand?,
    defaultFc: Double,
    type: Int,
    rate: Double,
    onChange: (DspBand?) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             modifier = Modifier.width(42.dp))
        val f = band
        if (f == null) {
            Text(
                "off", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        } else {
            Text(
                "${f.fc.toInt()} Hz", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }
        Switch(
            checked = band != null,
            onCheckedChange = { on ->
                onChange(
                    if (!on) null
                    else DspBand(type, defaultFc.coerceAtMost(rate * 0.4), 0.707, 0.0)
                )
            }
        )
    }
    val f = band ?: return
    EqSlider(
        "Fc", "${f.fc.toInt()} Hz",
        value = log10(f.fc.coerceIn(EQ_FMIN, EQ_FMAX)).toFloat(),
        range = log10(EQ_FMIN).toFloat()..log10(EQ_FMAX).toFloat(),
        onChange = {
            onChange(f.copy(fc = 10.0.pow(it.toDouble()).coerceIn(EQ_FMIN, EQ_FMAX)))
        }
    )
}

@Composable
fun MiniEqCurve(config: DspConfig, rate: Int, modifier: Modifier = Modifier) {
    val curve = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val r = if (rate > 0) rate.toDouble() else 48000.0
    Canvas(modifier) {
        val h = size.height
        val w = size.width
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 1f)
        val path = Path()
        val steps = 120
        for (i in 0..steps) {
            val f = DspMath.logFreq(i.toDouble() / steps, EQ_FMIN, EQ_FMAX)
            val db = DspMath.responseDb(config, r, f)
            val x = w * i / steps
            val y = (h / 2 - (db / EQ_DB_RANGE) * (h / 2 * 0.9)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curve, style = Stroke(width = 2f))
    }
}

@Composable
private fun EqCurve(
    config: DspConfig,
    rate: Double,
    peq: List<DspBand>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onBandChange: (Int, DspBand) -> Unit,
    modifier: Modifier = Modifier
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val zero = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val curve = MaterialTheme.colorScheme.primary
    val node = MaterialTheme.colorScheme.secondary
    val nodeSelected = MaterialTheme.colorScheme.primary

    val currentPeq by rememberUpdatedState(peq)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentBandChange by rememberUpdatedState(onBandChange)

    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                if (w <= 0f || h <= 0f) return@awaitEachGesture

                fun xOf(f: Double) =
                    (lnFrac(f) * w).toFloat()

                fun yOf(db: Double) =
                    (h / 2 - (db / EQ_DB_RANGE) * (h / 2 * 0.9)).toFloat()

                val bands = currentPeq
                var nearest = -1
                var best = 44.dp.toPx()
                bands.forEachIndexed { i, b ->
                    val dx = xOf(b.fc) - down.position.x
                    val dy = yOf(b.gainDb) - down.position.y
                    val d = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (d < best) { best = d; nearest = i }
                }
                if (nearest < 0) return@awaitEachGesture

                currentSelect(nearest)
                val start = down.position
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if ((ch.position - start).getDistance() > 2f) {
                        val fx = (ch.position.x / w).coerceIn(0f, 1f)
                        val f = DspMath.logFreq(fx.toDouble(), EQ_FMIN, EQ_FMAX)
                        val g = ((h / 2 - ch.position.y) / (h / 2 * 0.9)
                                * EQ_DB_RANGE).coerceIn(-EQ_DB_RANGE, EQ_DB_RANGE)
                        val band = bands.getOrNull(nearest) ?: break
                        currentBandChange(nearest, band.copy(fc = f, gainDb = g))
                        ch.consume()
                    }
                }
            }
        }
    ) {
        val w = size.width
        val h = size.height

        // horizontal grid: 0, ±6, ±12 dB
        for (db in listOf(-12.0, -6.0, 0.0, 6.0, 12.0)) {
            val y = (h / 2 - (db / EQ_DB_RANGE) * (h / 2 * 0.9)).toFloat()
            drawLine(
                if (db == 0.0) zero else grid,
                Offset(0f, y), Offset(w, y),
                strokeWidth = if (db == 0.0) 1.5f else 1f
            )
        }
        // vertical grid: 20, 100, 1k, 10k, 20k
        for (f in listOf(20.0, 100.0, 1000.0, 10000.0, 20000.0)) {
            val x = (lnFrac(f) * w).toFloat()
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }

        val path = Path()
        val steps = 240
        for (i in 0..steps) {
            val frac = i.toDouble() / steps
            val f = DspMath.logFreq(frac, EQ_FMIN, EQ_FMAX)
            val db = DspMath.responseDb(config, rate, f)
            val x = (frac * w).toFloat()
            val y = (h / 2 - (db / EQ_DB_RANGE) * (h / 2 * 0.9)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, curve, style = Stroke(width = 2.dp.toPx()))

        peq.forEachIndexed { i, b ->
            val x = (lnFrac(b.fc) * w).toFloat()
            val y = (h / 2 - (b.gainDb / EQ_DB_RANGE) * (h / 2 * 0.9)).toFloat()
            drawCircle(
                color = if (i == selected) nodeSelected else node,
                radius = if (i == selected) 7.dp.toPx() else 5.5.dp.toPx(),
                center = Offset(x, y)
            )
            if (i == selected) {
                drawCircle(
                    color = Color.White.copy(alpha = 0.8f),
                    radius = 2.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }
    }
}

private fun lnFrac(f: Double): Double =
    (kotlin.math.ln(f.coerceIn(EQ_FMIN, EQ_FMAX) / EQ_FMIN) /
        kotlin.math.ln(EQ_FMAX / EQ_FMIN)).coerceIn(0.0, 1.0)
