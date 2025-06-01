package com.example.myapplication.ishimoku

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Typeface
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import ru.tinkoff.piapi.contract.v1.CandleInterval
import ru.tinkoff.piapi.contract.v1.HistoricCandle
import ru.tinkoff.piapi.core.InvestApi
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var chartFrame: FrameLayout
    private lateinit var tableFrame: FrameLayout
    private lateinit var companySpinner: Spinner
    private lateinit var intervalSpinner: Spinner
    private lateinit var loadButton: Button
    private lateinit var logoutButton: Button
    private lateinit var showIchimokuCheckbox: CheckBox

    private lateinit var database: FirebaseDatabase
    private lateinit var candlesRef: DatabaseReference

    private val companyList = listOf("Сбербанк", "Газпром", "Яндекс", "Лукойл", "Роснефть", "МТС", "Магнит")
    private val companyMap = mapOf(
        "Сбербанк" to Pair("BBG0047315Y7", "SBER"),
        "Газпром"   to Pair("BBG004730RP0", "GAZP"),
        "Яндекс"    to Pair("TCS00A107T19", "YNDX"),
        "Лукойл"    to Pair("BBG004731032", "LKOH"),
        "Роснефть"  to Pair("BBG004731354", "ROSN"),
        "МТС"       to Pair("BBG004S681W1", "MTSS"),
        "Магнит"    to Pair("BBG004RVFCY3", "MGNT")
    )

    private var currentFIGI = "BBG0047315Y7"
    private var currentTicker = "SBER"
    private var currentInterval = CandleInterval.CANDLE_INTERVAL_HOUR

    private val TOKEN = "t.q-1BDCQZnRBmFiRB4nKRVnTHkOUINUj2QAmVlEzk9HDHBZhPFtRKQzmuvt7qdcQyGcxYSo7ApazWi4ZcQnO_PQ"
    private var api: InvestApi? = null

    private lateinit var chartManager: ChartManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FirebaseAuth.getInstance().currentUser == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        chartFrame = findViewById(R.id.chartFrame)
        tableFrame = findViewById(R.id.tableFrame)
        companySpinner = findViewById(R.id.companySpinner)
        intervalSpinner = findViewById(R.id.intervalSpinner)
        loadButton = findViewById(R.id.loadButton)
        logoutButton = findViewById(R.id.btnLogout)
        showIchimokuCheckbox = findViewById(R.id.showIchimoku)

        chartManager = ChartManager(chartFrame)
        api = InvestApi.createSandbox(TOKEN)

        initFirebase()
        setupSpinners()
        setupButtons()
    }

    private fun initFirebase() {
        try {
            database = FirebaseDatabase.getInstance("https://newishimoku-default-rtdb.asia-southeast1.firebasedatabase.app/")
            candlesRef = database.getReference("FIGIs")
        } catch (e: Exception) {
            Log.e("FirebaseInit", "Ошибка инициализации Firebase", e)
            throw e
        }
    }


    private fun setupSpinners() {
        companySpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, companyList
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        intervalSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            listOf("Час", "4 часа", "День", "Неделя", "Месяц")
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        companySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                val company = companyList[position]
                val (figi, ticker) = companyMap[company]!!
                currentFIGI = figi
                currentTicker = ticker
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        intervalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                currentInterval = when (position) {
                    1 -> CandleInterval.CANDLE_INTERVAL_4_HOUR
                    2 -> CandleInterval.CANDLE_INTERVAL_DAY
                    3 -> CandleInterval.CANDLE_INTERVAL_WEEK
                    4 -> CandleInterval.CANDLE_INTERVAL_MONTH
                    else -> CandleInterval.CANDLE_INTERVAL_HOUR
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        companySpinner.setSelection(0)
        intervalSpinner.setSelection(0)
    }

    private fun setupButtons() {
        loadButton.setOnClickListener {
            loadButton.isEnabled = false
            Thread {
                try {
                    val now = java.time.Instant.now()
                    val from = when (currentInterval) {
                        CandleInterval.CANDLE_INTERVAL_DAY     -> now.minus(java.time.Duration.ofDays(180))
                        CandleInterval.CANDLE_INTERVAL_WEEK    -> now.minus(java.time.Duration.ofDays(365 * 2))
                        CandleInterval.CANDLE_INTERVAL_MONTH   -> now.minus(java.time.Duration.ofDays(365 * 10))
                        CandleInterval.CANDLE_INTERVAL_4_HOUR  -> now.minus(java.time.Duration.ofDays(30))
                        else -> now.minus(java.time.Duration.ofDays(7))
                    }
                    val candles = api!!.marketDataService
                        .getCandles(currentFIGI, from, now, currentInterval)
                        .get()

                    runOnUiThread {
                        chartManager.draw(
                            candles,
                            currentTicker,
                            currentInterval,
                            showIchimokuCheckbox.isChecked
                        )
                        saveCandlesToFirebase(candles)
                        tableFrame.removeAllViews()
                        tableFrame.addView(createTable(candles))
                        loadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, "Ошибка: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        loadButton.isEnabled = true
                    }
                }
            }.start()
        }

        logoutButton.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }
    }

    private fun saveCandlesToFirebase(candles: List<HistoricCandle>) {
        val intervalPath = when (currentInterval) {
            CandleInterval.CANDLE_INTERVAL_HOUR -> "Hour"
            CandleInterval.CANDLE_INTERVAL_4_HOUR -> "4Hour"
            CandleInterval.CANDLE_INTERVAL_DAY -> "Day"
            CandleInterval.CANDLE_INTERVAL_WEEK -> "Week"
            CandleInterval.CANDLE_INTERVAL_MONTH -> "Month"
            else -> "Hour"
        }
        val ref = candlesRef.child(currentTicker).child(intervalPath)
        ref.removeValue().addOnCompleteListener {
            candles.sortedBy { it.time.seconds }.forEach { candle ->
                val timestamp = candle.time.seconds * 1000
                val dateKey = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).format(Date(timestamp))

                ref.child(dateKey).setValue(mapOf(
                    "open" to (candle.open.units + candle.open.nano / 1e9f),
                    "close" to (candle.close.units + candle.close.nano / 1e9f),
                    "high" to (candle.high.units + candle.high.nano / 1e9f),
                    "low" to (candle.low.units + candle.low.nano / 1e9f),
                    "volume" to candle.volume
                ))
            }
        }
    }

    private fun createTable(candles: List<HistoricCandle>): TableLayout {
        val df = when (currentInterval) {
            CandleInterval.CANDLE_INTERVAL_DAY -> SimpleDateFormat("dd.MM", Locale.getDefault())
            CandleInterval.CANDLE_INTERVAL_WEEK -> SimpleDateFormat("ww/yyyy", Locale.getDefault())
            CandleInterval.CANDLE_INTERVAL_MONTH -> SimpleDateFormat("MMM yyyy", Locale.getDefault())
            else -> SimpleDateFormat("HH:mm", Locale.getDefault())
        }

        return TableLayout(this).apply {
            layoutParams = TableLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setPadding(16, 16, 16, 16)
            addView(TableRow(context).apply {
                addView(TextView(context).apply {
                    text = "Дата"
                    setTypeface(null, Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "Открытие"
                    setTypeface(null, Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                })
                addView(TextView(context).apply {
                    text = "Закрытие"
                    setTypeface(null, Typeface.BOLD)
                    setPadding(8, 8, 8, 8)
                    layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                })
            })

            candles.takeLast(20).forEach { c ->
                addView(TableRow(context).apply {
                    addView(TextView(context).apply {
                        text = df.format(Date(c.time.seconds * 1000))
                        setPadding(8, 8, 8, 8)
                        layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                    })
                    addView(TextView(context).apply {
                        text = "%.2f".format(c.open.units + c.open.nano / 1e9f)
                        setPadding(8, 8, 8, 8)
                        layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                    })
                    addView(TextView(context).apply {
                        val close = c.close.units + c.close.nano / 1e9f
                        text = "%.2f".format(close)
                        setTextColor(if (close >= c.open.units) Color.GREEN else Color.RED)
                        setPadding(8, 8, 8, 8)
                        layoutParams = TableRow.LayoutParams(0, MATCH_PARENT, 1f)
                    })
                })
            }
        }
    }
}