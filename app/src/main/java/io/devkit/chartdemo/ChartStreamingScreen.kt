package io.devkit.chartdemo

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.devkit.chartkit.animation.ChartAnimation
import io.devkit.chartkit.charts.AreaChart
import io.devkit.chartkit.charts.ChartPerformance
import io.devkit.chartkit.interaction.ChartInteraction
import io.devkit.chartkit.model.ChartXResolver
import io.devkit.chartkit.state.rememberChartViewportState
import io.devkit.chartkit.stream.ChartUpdatePolicy
import io.devkit.chartkit.stream.ChartWindow
import io.devkit.chartkit.stream.rememberStreamingChartData
import io.devkit.chartkit.viewport.ChartViewport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data class Tick(val atMillis: Long, val value: Double)

/**
 * A local, deterministic stream — no network, no permissions.
 *
 * The emission rate is the control that matters. At 200 events a second an
 * unthrottled chart would recompose two hundred times a second and stop
 * responding to touch; the policy chips are what stops that, and switching
 * between them while the stream runs is the fastest way to see the difference.
 */
@Composable
fun ChartStreamingScreen(modifier: Modifier = Modifier) {
    var rate by rememberSaveable { mutableStateOf(20) }
    var policyIndex by rememberSaveable { mutableStateOf(0) }
    var windowIndex by rememberSaveable { mutableStateOf(0) }

    val viewport = rememberChartViewportState()

    val source: Flow<Tick> = remember(rate) { tickerFlow(rate) }
    val stream = rememberStreamingChartData(
        flow = source,
        window = when (windowIndex) {
            1 -> ChartWindow.Count(2_000)
            2 -> ChartWindow.Duration(10.seconds)
            else -> ChartWindow.Count(300)
        },
        policy = when (policyIndex) {
            1 -> ChartUpdatePolicy.Immediate
            2 -> ChartUpdatePolicy.Batch(100.milliseconds)
            3 -> ChartUpdatePolicy.Aggregate(
                100.milliseconds,
                io.devkit.chartkit.stream.ChartAggregation.MinMax,
            )
            else -> ChartUpdatePolicy.Throttle(16.milliseconds)
        },
        timestamp = { it.atMillis },
        viewport = viewport,
        key = rate to windowIndex,
    )

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Streaming", style = MaterialTheme.typography.titleLarge)
        Text(
            "A local flow, collected by an adapter rather than by the chart. `stream.items` is " +
                "an ordinary list and the chart is an ordinary chart — swapping a live source " +
                "for a static list is one line.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(5, 20, 60, 200).forEach { candidate ->
                FilterChip(
                    selected = rate == candidate,
                    onClick = { rate = candidate },
                    label = { Text("$candidate/s") },
                    modifier = Modifier.testTag("rate-$candidate"),
                )
            }
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("Throttle", "Immediate", "Batch", "Min/Max").forEachIndexed { index, label ->
                FilterChip(
                    selected = policyIndex == index,
                    onClick = { policyIndex = index },
                    label = { Text(label) },
                    modifier = Modifier.testTag("policy-$index"),
                )
            }
        }
        Text(
            when (policyIndex) {
                1 -> "Redraws on every emission. Fine at 5/s; try it at 200/s and then try to " +
                    "drag the chart."
                2 -> "Collects for 100 ms and appends all of them. Nothing is dropped, which " +
                    "is what a stream of discrete events needs."
                3 -> "Collects for 100 ms and keeps the interval's extremes. Two points instead " +
                    "of twenty, and no spike is lost."
                else -> "At most one redraw per frame, showing the newest value. Intermediate " +
                    "readings are discarded — right for a measurement, wrong for a count."
            },
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("300 points", "2,000 points", "10 seconds").forEachIndexed { index, label ->
                FilterChip(
                    selected = windowIndex == index,
                    onClick = { windowIndex = index },
                    label = { Text(label) },
                    modifier = Modifier.testTag("window-$index"),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { if (stream.isPaused) stream.resume() else stream.pause() },
                modifier = Modifier.testTag("pause"),
            ) { Text(if (stream.isPaused) "Resume" else "Pause") }
            Button(
                onClick = {
                    viewport.viewport = ChartViewport.trailing(0.25)
                    stream.jumpToLatest()
                },
                modifier = Modifier.testTag("follow"),
            ) { Text("Follow latest") }
            Button(onClick = { viewport.reset() }, modifier = Modifier.testTag("full")) {
                Text("Full window")
            }
        }

        HorizontalDivider()

        AreaChart(
            data = stream.items,
            x = { it.atMillis },
            y = { it.value },
            xResolver = ChartXResolver.Time,
            // Streaming data changes constantly; interpolating every update
            // would mean the chart never settles.
            animation = ChartAnimation.None,
            performance = ChartPerformance.Default,
            interaction = ChartInteraction.Explorable,
            viewportState = viewport,
            modifier = Modifier.fillMaxWidth().height(260.dp).testTag(CHART),
        )

        HorizontalDivider()
        Text(
            "Received ${stream.receivedCount} · showing ${stream.items.size} · " +
                if (stream.followLatest) "following" else "paused following",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(READOUT),
        )
        Text(
            "Zoom in and then pan backwards: following turns itself off, because otherwise the " +
                "next sample would drag you forward again and the history would be unreadable. " +
                "\"Follow latest\" turns it back on.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * A deterministic local source at roughly [perSecond] events a second.
 *
 * No network and no permissions: a demo that needed either would be a demo of
 * something else. The waveform is a function of the tick index, so the shape is
 * the same on every run.
 */
private fun tickerFlow(perSecond: Int): Flow<Tick> = flow {
    val interval = (1_000L / perSecond).coerceAtLeast(1L)
    var index = 0L
    while (true) {
        val t = index.toDouble()
        val value = 50.0 +
            20.0 * kotlin.math.sin(t / 40.0) +
            6.0 * kotlin.math.sin(t / 6.0) +
            if (index % 500L == 0L) 18.0 else 0.0
        emit(Tick(System.currentTimeMillis(), value))
        index++
        delay(interval)
    }
}

private const val CHART = "streaming-chart"
private const val READOUT = "streaming-readout"
