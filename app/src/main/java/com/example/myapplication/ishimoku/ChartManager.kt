package com.example.myapplication.ishimoku

import android.graphics.Color
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.CombinedChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.formatter.IFillFormatter
import com.github.mikephil.charting.formatter.ValueFormatter
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import java.text.SimpleDateFormat
import java.util.*

class ChartManager(private val container: FrameLayout) {

    private val chart: CombinedChart = CombinedChart(container.context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        description = Description().apply { text = "" }
        setDrawGridBackground(false)
        setPinchZoom(true)
        legend.apply {
            isEnabled = true
            verticalAlignment = Legend.LegendVerticalAlignment.TOP
            horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
        }

        axisRight.isEnabled = false
        axisLeft.setDrawGridLines(true)
        setDrawOrder(arrayOf(
            CombinedChart.DrawOrder.BAR,
            CombinedChart.DrawOrder.BUBBLE,
            CombinedChart.DrawOrder.CANDLE,
            CombinedChart.DrawOrder.LINE
        ))
    }

    init {
        container.removeAllViews()
        container.addView(chart)
    }

    private var currentCandles: List<HistoricCandle> = emptyList()
    private var currentInterval: CandleInterval = CandleInterval.CANDLE_INTERVAL_HOUR

    fun draw(
        candles: List<HistoricCandle>,
        ticker: String,
        interval: CandleInterval,
        showIchimoku: Boolean // Новый параметр
    ) {
        chart.clear()
        if (candles.isEmpty()) {
            chart.invalidate()
            return
        }

        currentCandles = candles
        currentInterval = interval

        chart.xAxis.apply {
            granularity = 1f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index in currentCandles.indices) {
                        val candle = currentCandles[index]
                        val date = Date(candle.time.seconds * 1000)
                        when (currentInterval) {
                            CandleInterval.CANDLE_INTERVAL_WEEK ->
                                SimpleDateFormat("dd MMM", Locale.getDefault()).format(date)

                            CandleInterval.CANDLE_INTERVAL_MONTH ->
                                SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(date)

                            else ->
                                SimpleDateFormat("dd.MM", Locale.getDefault()).format(date)
                        }
                    } else ""
                }
            }
        }


        // 1) соберем свечи
        val candleEntries = candles.mapIndexed { i, c ->
            val o = c.open.units + c.open.nano / 1e9f
            val h = c.high.units + c.high.nano / 1e9f
            val l = c.low.units + c.low.nano / 1e9f
            val cl = c.close.units + c.close.nano / 1e9f
            CandleEntry(i.toFloat(), h, l, o, cl)
        }
        val candleSet = CandleDataSet(candleEntries, "Свечи").apply {
            color = Color.DKGRAY
            shadowColor = Color.BLACK
            decreasingColor = Color.RED
            increasingColor = Color.GREEN
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingPaintStyle = android.graphics.Paint.Style.FILL
            setDrawValues(false)
        }

        // 2) Ichimoku
        val n = candles.size
        // В классе ChartManager.kt, функция draw()
// Tenkan-sen (9 периодов)
        val tenkan = MutableList(n) { i ->
            if (i >= 8) {
                val w = candles.subList(i - 8, i + 1) // Правильный диапазон: 9 свечей (0..8)
                val hh = w.maxOf { it.high.units + it.high.nano / 1e9f }
                val ll = w.minOf { it.low.units + it.low.nano / 1e9f }
                Entry(i.toFloat(), (hh + ll) / 2f)
            } else Entry(i.toFloat(), Float.NaN)
        }

// Kijun-sen (26 периодов)
        val kijun = MutableList(n) { i ->
            if (i >= 25) {
                val w = candles.subList(i - 25, i + 1) // Правильный диапазон: 26 свечей (0..25)
                val hh = w.maxOf { it.high.units + it.high.nano / 1e9f }
                val ll = w.minOf { it.low.units + it.low.nano / 1e9f }
                Entry(i.toFloat(), (hh + ll) / 2f)
            } else Entry(i.toFloat(), Float.NaN)
        }

        val senA = MutableList(n) { Entry(it.toFloat(), Float.NaN) }
        val senB = MutableList(n) { Entry(it.toFloat(), Float.NaN) }
        for (i in 0 until n) {
            if (i >= 26 && i < n - 26) {
                val a = (tenkan[i].y + kijun[i].y) / 2f
                senA[i + 26] = Entry((i + 26).toFloat(), a)
            }
        }

        for (i in 0 until n) {
            if (i >= 52 && i < n - 26) {
                val w = candles.subList(i - 52, i + 1)
                val hh = w.maxOf { it.high.units + it.high.nano / 1e9f }
                val ll = w.minOf { it.low.units + it.low.nano / 1e9f }
                senB[i + 26] = Entry((i + 26).toFloat(), (hh + ll) / 2f)
            }
        }

        // Добавьте логирование для отладки
        Log.d("Ichimoku", "Senkou A: ${senA.filter { !it.y.isNaN() }}")
        Log.d("Ichimoku", "Senkou B: ${senB.filter { !it.y.isNaN() }}")

// Chikou Span (сдвиг на 26 периодов назад)
        val chikou = MutableList(n) { i ->
            if (i + 26 < n) {
                val c = candles[i + 26]
                val cl = c.close.units + c.close.nano / 1e9f
                Entry(i.toFloat(), cl)
            } else Entry(i.toFloat(), Float.NaN)
        }

        fun line(name: String, list: List<Entry>, col: Int) =
            LineDataSet(list.filter { !it.y.isNaN() }, name).apply {
                axisDependency = YAxis.AxisDependency.LEFT
                color = col
                lineWidth = 2f
                setDrawCircles(false)
                setDrawValues(false)
            }

        val setTenkan = line("Tenkan-sen", tenkan, Color.RED)
        val setKijun = line("Kijun-sen", kijun, Color.BLUE)

        val setA = line("Senkou A", senA.filter { !it.y.isNaN() }, Color.GREEN).apply {
            setDrawFilled(true)
            fillAlpha = 64
            fillColor = Color.argb(30, 0, 255, 0)
        }


        val setB = line("Senkou B", senB.filter { !it.y.isNaN() }, Color.BLUE).apply {
            setDrawFilled(true)
            fillAlpha = 128
            fillColor = Color.argb(50, 0, 0, 255)
        }

        val setChikou = line("Chikou", chikou, Color.MAGENTA).apply {
            enableDashedLine(10f, 10f, 0f)
        }

        // 3) Создаем CombinedData только для свечей
        val combined = CombinedData().apply {
            setData(CandleData(candleSet))
        }

        // 4) Добавляем Ишимоку только если галочка активна
        if (showIchimoku) {

            val lineData = LineData().apply {
                addDataSet(setTenkan)
                addDataSet(setKijun)
                addDataSet(setA)
                addDataSet(setB)
                addDataSet(setChikou)
            }

            combined.setData(lineData)
        }

        // 5) Устанавливаем данные в график
        chart.data = combined
        chart.description = Description().apply { text = ticker }
        chart.invalidate()
    }
}