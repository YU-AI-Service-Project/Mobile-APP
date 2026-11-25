package com.wattup.app

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.Gravity
import android.view.ViewGroup.LayoutParams
import android.widget.ProgressBar
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.SearchView
import android.widget.LinearLayout
import android.widget.ToggleButton
import com.kakao.vectormap.*
import com.kakao.vectormap.camera.CameraUpdateFactory
import com.kakao.vectormap.label.Label
import com.kakao.vectormap.label.LabelOptions
import com.kakao.vectormap.label.LabelStyle
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var guest = false
    private var candidate = SearchPoint.DAEGU
    private var candidateName: String? = "대구 기본 위치"
    private var confirmedName: String? = null
    private var confirmed: SearchPoint? = null
    private var mapView: MapView? = null
    private var liveMap: KakaoMap? = null
    private var marker: Label? = null
    private var preview: PreviewMap? = null
    private var filter = NearbyFilter()
    private var listMode = false
    private var backRegistered = false
    private val internalBack by lazy { android.window.OnBackInvokedCallback { returnToPreviousView() } }
    private var recommendationMode = false
    private var rows = emptyList<StationRow>()
    private val stationLabels = mutableListOf<Label>()
    private var detailSheet: Dialog? = null
    private val speedWorker = Executors.newFixedThreadPool(2)
    private val speedLookups = mutableMapOf<String, String?>()
    @Volatile private var requestId = 0
    private val nearbyWork = LatestWork()
    private val recommendationWork = LatestWork()
    private val detailWork = LatestWork()
    private val placeWork = LatestWork()
    private val favoritesWork = LatestWork()
    private var requestedFilter: NearbyFilter? = null
    private var nearbyNotice = ""
    private var recommendationNotice = ""
    private val source: StationSource by lazy {
        val url = getString(R.string.wattup_api_base_url)
        if (url.isBlank()) NearbySource(applicationContext) else HttpStationSource(url, demoForecast = resources.getBoolean(R.bool.demo_server_forecast))
    }
    private val guestLibrary by lazy { GuestLibrary(this) }
    private val manager by lazy { getSystemService(LOCATION_SERVICE) as LocationManager }
    private val handler = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var detailObservation: StationRow? = null
    private var predictionPending = false
    private var displayedDetail: StationDetail? = null
    private var nearbyResult: NearbyResult? = null
    private var recommendationResult: Recommendations? = null
    private var favoritesTick: ((Long) -> Unit)? = null
    private val freshnessTick = object : Runnable {
        override fun run() {
            if (guest) updateFreshness()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateFreshness() {
        val now = System.currentTimeMillis()
        if (rows.any { it.at(now) !== it }) {
            rows = rows.map { it.at(now) }.filterNot { filter.availableOnly && it.status == Availability.UNKNOWN }
            renderStations()
            updateNearbySummary()
        }
        detailSheet?.let { sheet ->
            val row = detailObservation?.at(now)
            if (row != null) {
                detailObservation = row
                renderObservation(sheet, row, displayedDetail, now)
            }
        }
        favoritesTick?.invoke(now)
        val recommendations = recommendationResult ?: return
        val updated = recommendations.at(now, filter.availableOnly)
        if (updated !== recommendations) {
            recommendationResult = updated
            renderRecommendations(updated)
        }
    }

    private fun updateNearbySummary() {
        val summary = listOfNotNull(nearbyResult?.summary(rows.size), nearbyNotice.takeIf { it.isNotEmpty() }).joinToString(" · ")
        val point = confirmed ?: return
        text(R.id.result).text = "${confirmedName ?: "지도 핀 ${format(point)}"} 기준\n${source.description} · 반경 ${filter.radiusText} · ${filter.title} · $summary"
    }

    private fun renderObservation(sheet: Dialog, row: StationRow, detail: StationDetail?, now: Long) {
        fun show(id: Int, value: String) {
            val view = sheet.findViewById<TextView>(id)
            if (view.text.toString() != value) view.text = value
        }
        val stale = row.staleObservation
        show(R.id.detail_current, "현재 상태\n${row.badge}")
        sheet.findViewById<TextView>(R.id.detail_current).setTextColor(row.status.color)
        val timestamp = when {
            row.asOf.isBlank() -> "관측 시각 미제공"
            row.expiresAt == null -> row.observedLabel.orEmpty()
            else -> {
                val age = ((now - (row.expiresAt - 600000L)) / 60000L).coerceAtLeast(0)
                if (age == 0L) "방금 관측" else "${age}분 전 관측"
            }
        }
        val freshness = if (stale || !row.countsReliable) "정보 확인 필요" else "정상 갱신"
        show(R.id.detail_fresh, "$freshness · $timestamp")
        sheet.findViewById<TextView>(R.id.detail_fresh).setTextColor(if (stale || !row.countsReliable) 0xffa63232.toInt() else 0xff12634f.toInt())
        if (detail == null) return
        fun count(value: Int?) = value?.let { "${it}대" } ?: "정보 없음"
        val counts = "전체 ${count(row.total)} · 가용 ${row.available}대 · 충전 중 ${count(row.charging)} · 기타 ${count(row.other)} · 미확인 ${count(row.unknown)}"
        show(R.id.detail_counts, if (stale || !row.countsReliable) "현재 대수 확인 필요" else counts)
        val units = detail.chargers.joinToString("\n") { it.label(now, stale) }.ifEmpty { "정보 없음" }
        show(R.id.detail_units, "충전기별 상태\n$units")
        val forecast = when {
            stale -> "30분 후\n예측 정보 부족"
            predictionPending -> "30분 후\n조회 중"
            else -> detail.forecastLabel
        }
        show(R.id.detail_forecast, forecast)
        val gauge = sheet.findViewById<ProgressBar>(R.id.detail_gauge)
        val percent = detail.percent
        gauge.visibility = if (!stale && percent != null) View.VISIBLE else View.GONE
        if (percent != null) gauge.progress = percent
    }

    private val timeout = Runnable { locationUnavailable() }

    private fun text(id: Int) = findViewById<TextView>(id)
    private fun format(point: SearchPoint) =
        String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("guest") == true) {
            candidate = SearchPoint(savedInstanceState.getDouble("lat"), savedInstanceState.getDouble("lon"))
            candidateName = savedInstanceState.getString("candidateName")
            confirmedName = savedInstanceState.getString("confirmedName")
            filter = NearbyFilter(Speed.valueOf(savedInstanceState.getString("speed") ?: "ALL"),
                savedInstanceState.getBoolean("availableOnly"), savedInstanceState.getBoolean("dcCombo"), savedInstanceState.getInt("radiusM", 3000))
            listMode = savedInstanceState.getBoolean("listMode")
            recommendationMode = savedInstanceState.getBoolean("recommendationMode")
            openSearch()
            if (savedInstanceState.containsKey("queryLat")) {
                search(SearchPoint(savedInstanceState.getDouble("queryLat"), savedInstanceState.getDouble("queryLon")))
            }
        } else {
            setContentView(R.layout.activity_guest)
            findViewById<Button>(R.id.kakao_login).setOnClickListener { showLoginOptions() }
            findViewById<Button>(R.id.google_login).setOnClickListener { showLoginOptions() }
            findViewById<Button>(R.id.browse).setOnClickListener {
                openSearch()
                search(candidate)
                locate()
            }
        }
    }

    private fun openSearch() {
        guest = true
        setContentView(R.layout.activity_search)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        setupStationControls()
        findViewById<Button>(R.id.locate).setOnClickListener { locate() }
        findViewById<Button>(R.id.search).setOnClickListener {
            stopLocation()
            search(candidate)
        }
        startMap()
        renderPin(false)
    }

    private fun startMap() {
        val key = getString(R.string.kakao_native_app_key)
        if (key.isBlank()) {
            openPreview()
        } else {
            KakaoMapSdk.init(applicationContext, key)
            val container = findViewById<FrameLayout>(R.id.map_container)
            val view = layoutInflater.inflate(R.layout.activity_map, container, false) as MapView
            preview = null
            container.removeAllViews()
            container.addView(view)
            text(R.id.hint).setOnClickListener(null)
            mapView = view
            text(R.id.hint).text = "지도를 눌러 검색할 위치를 선택하세요."
            view.start(object : MapLifeCycleCallback() {
                override fun onMapDestroy() {}
                override fun onMapError(error: Exception) {
                    runOnUiThread { if (!isDestroyed && !isFinishing && mapView === view) openPreview() }
                }
            }, object : KakaoMapReadyCallback() {
                override fun getPosition() = LatLng.from(candidate.latitude, candidate.longitude)
                override fun getZoomLevel() = 15
                override fun onMapReady(kakaoMap: KakaoMap) {
                    if (mapView !== view || isDestroyed) return
                    liveMap = kakaoMap
                    renderPin(true)
                    renderStations()
                    kakaoMap.setOnLabelClickListener { _, _, label ->
                        val row = rows.find { it.id == label.labelId }
                        if (row != null) openDetail(row)
                        row != null
                    }
                    kakaoMap.setOnMapClickListener { _, position, _, _ ->
                        selectPin(SearchPoint(position.latitude, position.longitude))
                    }
                }
            })
        }
        renderPin(false)
    }

    private fun openPreview() {
        liveMap = null
        stationLabels.clear()
        marker = null
        mapView = null
        val container = findViewById<FrameLayout>(R.id.map_container)
        container.removeAllViews()
        val picker = PreviewMap(this, ::selectPin, ::openDetail)
        picker.center = candidate
        picker.pin = candidate
        picker.stations = rows
        picker.contentDescription = "개발용 좌표판"
        preview = picker
        container.addView(picker)
        val hint = text(R.id.hint)
        if (getString(R.string.kakao_native_app_key).isBlank()) {
            hint.text = "지도 미연결 · 개발용 좌표판을 눌러 핀을 선택하세요."
        } else {
            hint.text = "지도 연결 실패 · 여기를 눌러 다시 연결"
            hint.setOnClickListener { startMap() }
        }
    }

    private fun setupStationControls() {
        findViewById<Button>(R.id.place_search).setOnClickListener { openPlaceSearch() }
        findViewById<Button>(R.id.my_page).setOnClickListener { openMyPage() }
        findViewById<ListView>(R.id.station_list).setOnItemClickListener { _, _, index, _ ->
            rows.getOrNull(index)?.let(::openDetail)
        }
        val fast = findViewById<ToggleButton>(R.id.fast)
        val slow = findViewById<ToggleButton>(R.id.slow)
        val available = findViewById<ToggleButton>(R.id.available)
        val combo = findViewById<ToggleButton>(R.id.combo)
        fun updateButtons() {
            text(R.id.radius).text = "반경 ${filter.radiusText}"
            combo.isChecked = filter.dcCombo
            fast.isChecked = filter.speed == Speed.FAST
            slow.isChecked = filter.speed == Speed.SLOW
            available.isChecked = filter.availableOnly
        }
        fun applyFilter(value: NearbyFilter) {
            filter = value
            updateButtons()
            confirmed?.let(::search)
        }
        updateButtons()
        findViewById<Button>(R.id.radius).setOnClickListener {
            val radii = listOf(500, 1000, 3000, 5000, 10000)
            val labels = radii.map { filter.copy(radiusM = it).radiusText }.toTypedArray()
            AlertDialog.Builder(this).setTitle("검색 반경").setSingleChoiceItems(labels, radii.indexOf(filter.radiusM)) { dialog, index ->
                dialog.dismiss()
                applyFilter(filter.copy(radiusM = radii[index]))
            }.show()
        }
        fast.setOnClickListener { applyFilter(filter.copy(speed = if (fast.isChecked) Speed.FAST else Speed.ALL)) }
        slow.setOnClickListener { applyFilter(filter.copy(speed = if (slow.isChecked) Speed.SLOW else Speed.ALL, dcCombo = false)) }
        combo.setOnClickListener { applyFilter(filter.copy(dcCombo = combo.isChecked,
            speed = if (combo.isChecked && filter.speed == Speed.SLOW) Speed.ALL else filter.speed)) }
        available.setOnClickListener { applyFilter(filter.copy(availableOnly = available.isChecked)) }
        findViewById<Button>(R.id.reset).setOnClickListener { applyFilter(NearbyFilter()) }
        findViewById<Button>(R.id.refresh).setOnClickListener { confirmed?.let(::search) }
        findViewById<Button>(R.id.toggle_view).setOnClickListener {
            recommendationMode = false
            listMode = !listMode
            updateViewMode()
        }
        findViewById<Button>(R.id.top_three).setOnClickListener {
            recommendationMode = !recommendationMode
            updateViewMode()
        }
        updateViewMode()
    }

    private fun updateViewMode() {
        val mapVisible = !listMode && !recommendationMode
        val handlesBack = !mapVisible
        if (android.os.Build.VERSION.SDK_INT >= 33 && backRegistered != handlesBack) {
            if (mapVisible) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(internalBack)
            else onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, internalBack)
            backRegistered = handlesBack
        }
        text(R.id.page_title).text = when { recommendationMode -> "Top-3 추천"; listMode -> "주변 충전소 목록"; else -> "내 주변 충전소" }
        findViewById<View>(R.id.locate).visibility = if (mapVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.search).visibility = if (mapVisible && confirmed != null && candidate != confirmed) View.VISIBLE else View.GONE
        findViewById<View>(R.id.selection).visibility = if (mapVisible) View.VISIBLE else View.GONE
        if (!mapVisible) findViewById<View>(R.id.notice).visibility = View.GONE
        findViewById<View>(R.id.map_container).visibility = if (mapVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.hint).visibility = if (mapVisible) View.VISIBLE else View.GONE
        findViewById<View>(R.id.station_list).visibility = if (listMode && !recommendationMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recommend_panel).visibility = if (recommendationMode) View.VISIBLE else View.GONE
        text(R.id.top_three).text = if (recommendationMode) "추천 닫기" else "Top-3 추천"
        text(R.id.toggle_view).text = if (listMode) "지도 보기" else "목록 보기"
    }

    private fun renderStations() {
        preview?.stations = rows
        stationLabels.forEach { it.remove() }
        stationLabels.clear()
        val layer = liveMap?.labelManager?.layer
        if (layer != null) {
            for (row in rows) {
                val style = LabelStyle.from(stationBadge(row, resources.displayMetrics.density))
                val point = row.point ?: continue
                val position = LatLng.from(point.latitude, point.longitude)
                layer.addLabel(LabelOptions.from(row.id, position).setStyles(style).setClickable(true))?.let(stationLabels::add)
            }
        }
        findViewById<ListView>(R.id.station_list).adapter =
            object : ArrayAdapter<StationRow>(this, R.layout.station_row, rows) {
                override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                    val card = convertView ?: layoutInflater.inflate(R.layout.station_row, parent, false)
                    val row = getItem(position)!!
                    val distance = String.format(Locale.KOREA, "직선거리 %.2f km", row.meters / 1000.0)
                    val speed = speedLookups[row.id] ?: row.speedText
                    val observed = row.observedLabel?.let { "\n관측 $it" }.orEmpty()
                    card.findViewById<TextView>(R.id.row_name).text = "${row.name}\n$distance · $speed$observed"
                    if (listMode && !recommendationMode && row.needsSpeedLookup && !speedLookups.containsKey(row.id)) {
                        speedLookups[row.id] = null
                        val revision = requestId
                        speedWorker.execute {
                            if (revision != requestId) return@execute
                            val types = try { source.supportedSpeeds(row.id) } catch (_: IOException) { null }
                            runOnUiThread {
                                if (types != null && revision == requestId && !isDestroyed) {
                                    speedLookups[row.id] = types
                                    (findViewById<ListView>(R.id.station_list).adapter as? ArrayAdapter<*>)?.notifyDataSetChanged()
                                }
                            }
                        }
                    }
                    card.findViewById<TextView>(R.id.row_badge).apply {
                        text = row.badge
                        setTextColor(row.status.color)
                    }
                    return card
                }
            }
    }

    private fun openPlaceSearch() {
        stopLocation()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val input = SearchView(this).apply {
            isIconified = false
            queryHint = "장소명 또는 주소"
        }
        val status = TextView(this)
        val list = ListView(this)
        content.addView(input)
        content.addView(status)
        val retry = Button(this).apply { text = "다시 시도"; visibility = View.GONE }
        content.addView(retry)
        content.addView(list)
        val dialog = AlertDialog.Builder(this).setTitle("장소 검색").setView(content)
            .setNegativeButton("닫기", null).create()
        var shown = emptyList<Place>()
        var generation = 0
        fun update(query: String) {
            val revision = ++generation
            placeWork.cancel()
            val term = query.trim()
            val history = guestLibrary.history()
            shown = emptyList()
            list.adapter = null
            retry.visibility = View.GONE
            status.text = "검색 중"
            handler.postDelayed({
                if (!dialog.isShowing || revision != generation) return@postDelayed
                if (isDestroyed || isFinishing) return@postDelayed
                placeWork.submit {
                    val results = try {
                        if (term.isEmpty()) (history + source.places("")).distinctBy { it.point }
                        else source.places(term)
                    } catch (_: IOException) { null }
                    runOnUiThread {
                        if (!dialog.isShowing || revision != generation || isDestroyed) return@runOnUiThread
                        retry.visibility = if (results == null) View.VISIBLE else View.GONE
                        shown = results.orEmpty()
                        status.text = when {
                            results == null -> "장소를 불러오지 못했습니다."
                            shown.isEmpty() && term.isEmpty() -> "검색할 장소명이나 주소를 입력하세요."
                            shown.isEmpty() -> "검색 결과 없음 · 다른 장소명이나 주소를 입력하세요."
                            else -> source.description
                        }
                        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map {
                            val recent = if (term.isEmpty() && it in history) "최근 · " else ""
                            "$recent${it.name}\n${it.address}"
                        })
                    }
                }
            }, 250)
        }
        retry.setOnClickListener { update(input.query.toString()) }
        input.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean { input.clearFocus(); return true }
            override fun onQueryTextChange(text: String): Boolean { update(text); return true }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val place = shown[position]
            guestLibrary.remember(place)
            dialog.dismiss()
            listMode = false
            recommendationMode = false
            updateViewMode()
            candidateName = place.name
            candidate = place.point
            renderPin(true)
            search(candidate)
        }
        dialog.setOnDismissListener { placeWork.cancel() }
        update("")
        dialog.show()
        dialog.window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private fun openMyPage() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        fun action(label: String, run: () -> Unit) {
            page.addView(Button(this).apply { text = label; setOnClickListener { run() } })
        }
        page.addView(TextView(this).apply { text = "비회원 프로필"; textSize = 24f })
        action("로그인 옵션") { showLoginOptions() }
        action("이 기기의 즐겨찾기") { openFavorites() }
        val guide = TextView(this).apply {
            text = "여유·혼잡·만차는 현재 상태입니다. 갱신이 지연되면 정보 확인 필요로 표시합니다. 30분 후 확률은 선택 조건의 충전기가 한 대 이상 이용 가능할 확률입니다."
            visibility = View.GONE
        }
        action("이용 가이드 펼치기 / 접기") { guide.visibility = if (guide.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        page.addView(guide)
        action("위치 권한 설정") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        AlertDialog.Builder(this).setTitle("마이페이지").setView(android.widget.ScrollView(this).apply { addView(page) })
            .setNegativeButton("닫기", null).show().window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private fun showLoginOptions() {
        AlertDialog.Builder(this).setTitle("로그인 안내")
            .setMessage("카카오·Google 계정 연결은 준비 중입니다. 현재는 비회원으로 충전소를 검색하고 즐겨찾기를 저장할 수 있습니다.")
            .setPositiveButton("확인", null).show()
    }

    private fun openFavorites() {
        val favorites = guestLibrary.favorites()
        val builder = AlertDialog.Builder(this).setTitle("이 기기의 즐겨찾기").setNegativeButton("닫기", null)
        if (favorites.isEmpty()) {
            builder.setMessage("충전소 상세에서 ☆를 눌러 저장하세요.").show()
            return
        }
        val labels = favorites.map { "${it.name} · 상태 조회 중" }.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val details = mutableMapOf<String, StationDetail?>()
        val awaitingPrediction = mutableSetOf<String>()
        val dialog = builder.setAdapter(adapter) { _, index ->
            val bookmark = favorites[index]
            openDetail(details[bookmark.id]?.current?.at(System.currentTimeMillis()) ?: bookmark.pendingRow(), NearbyFilter(), bookmark)
        }.create()
        fun render(now: Long) {
            var changed = false
            favorites.forEachIndexed { index, bookmark ->
                if (!details.containsKey(bookmark.id)) return@forEachIndexed
                val detail = details[bookmark.id]
                val row = detail?.current?.at(now)
                val state = row?.badge ?: "현재 상태 확인 필요"
                val forecast = when {
                    row == null || row.status == Availability.UNKNOWN -> "예측 정보 부족"
                    bookmark.id in awaitingPrediction -> "조회 중"
                    else -> detail.forecastText
                }
                val label = "${bookmark.name}\n$state · 30분 후 $forecast"
                if (labels[index] != label) {
                    labels[index] = label
                    changed = true
                }
            }
            if (changed) adapter.notifyDataSetChanged()
        }
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        favoritesTick = ::render
        dialog.setOnDismissListener { active.set(false); favoritesTick = null; favoritesWork.cancel() }
        dialog.show()
        favoritesWork.submit {
            for (bookmark in favorites) {
                if (!active.get()) break
                val detail = try {
                    source.favoriteDetail(bookmark) { current ->
                        runOnUiThread {
                            if (dialog.isShowing && !isDestroyed) {
                                awaitingPrediction.add(bookmark.id)
                                details[bookmark.id] = current
                                render(System.currentTimeMillis())
                            }
                        }
                    }
                } catch (_: IOException) { null }
                runOnUiThread {
                    if (dialog.isShowing && !isDestroyed) {
                        awaitingPrediction.remove(bookmark.id)
                        details[bookmark.id] = detail
                        render(System.currentTimeMillis())
                    }
                }
            }
        }
    }

    private fun openDetail(row: StationRow) = openDetail(row, filter)

    private fun openDetail(row: StationRow, selectedFilter: NearbyFilter, bookmark: Bookmark? = null) {
        stopLocation()
        detailSheet?.dismiss()
        val sheet = Dialog(this)
        detailSheet = sheet
        sheet.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        sheet.setContentView(R.layout.station_detail)
        fun field(id: Int) = sheet.findViewById<TextView>(id)
        field(R.id.detail_title).text = row.name
        detailObservation = row
        displayedDetail = null
        predictionPending = true
        var destination = row.point
        val favorite = field(R.id.detail_favorite)
        favorite.isEnabled = destination != null
        field(R.id.detail_route).isEnabled = destination != null
        fun updateFavorite() {
            val saved = guestLibrary.favorites().any { it.id == row.id }
            favorite.text = if (saved) "★" else "☆"
            favorite.contentDescription = if (saved) "즐겨찾기 해제" else "즐겨찾기 등록"
        }
        updateFavorite()
        favorite.setOnClickListener {
            guestLibrary.toggle(Bookmark(row.id, row.name, destination ?: return@setOnClickListener))
            updateFavorite()
        }
        field(R.id.detail_condition).text = "${source.description} · ${selectedFilter.title}"
        field(R.id.detail_current).text = "현재 상태\n${row.badge}"
        field(R.id.detail_current).setTextColor(row.status.color)
        val forecast = field(R.id.detail_forecast)
        forecast.text = "30분 후\n조회 중"
        field(R.id.detail_help).setOnClickListener {
            AlertDialog.Builder(this).setMessage("기준 시각에서 30분 뒤, 선택한 조건에 맞는 충전기가 한 대 이상 이용 가능할 확률입니다.")
                .setPositiveButton("확인", null).show()
        }
        field(R.id.detail_route).setOnClickListener {
            val point = destination ?: return@setOnClickListener
            val coords = "${point.latitude},${point.longitude}"
            val links = listOf("google.navigation:q=$coords&mode=d", "geo:$coords?q=$coords(" + Uri.encode(row.name) + ")")
            for (link in links) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    return@setOnClickListener
                } catch (_: android.content.ActivityNotFoundException) { /* Try the next destination handler. */ }
            }
            AlertDialog.Builder(this).setTitle("목적지 좌표").setMessage("${row.name}\n$coords").setPositiveButton("확인", null).show()
        }
        val unitToggle = field(R.id.detail_toggle_units)
        unitToggle.setOnClickListener {
            val units = field(R.id.detail_units)
            val opening = units.visibility != View.VISIBLE
            units.visibility = if (opening) View.VISIBLE else View.GONE
            unitToggle.text = if (opening) "충전기별 상태 접기" else "충전기별 상태 보기"
        }
        field(R.id.detail_close).setOnClickListener { sheet.dismiss() }
        sheet.setOnDismissListener {
            if (detailSheet === sheet) {
                detailSheet = null
                detailWork.cancel()
            }
        }
        sheet.show()
        sheet.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        fun bindCurrent(detail: StationDetail) {
            val now = System.currentTimeMillis()
            val current = (detail.current ?: row).at(now)
            detailObservation = current
            displayedDetail = detail
            destination = current.point
            favorite.isEnabled = destination != null
            field(R.id.detail_route).isEnabled = destination != null
            field(R.id.detail_location).text = "${detail.address ?: "주소 정보 없음"} · ${detail.speedText ?: row.speedText}"
            renderObservation(sheet, current, detail, now)
            field(R.id.detail_specs).text = detail.asOf.takeIf { it.isNotBlank() }?.let { "관측 기준  ${koreanTime(it)} (한국시간)" } ?: "관측 시각 미제공"
            unitToggle.visibility = View.VISIBLE
            for ((name, value) in listOf("이용 제한" to detail.restrictions, "운영 시간" to detail.hours, "운영사" to detail.operator, "문의 전화" to detail.phone, "커넥터" to detail.connector,
                    "출력" to detail.output, "충전 요금" to detail.chargingRate,
                    "주차 요금" to detail.parkingFee)) {
                val table = sheet.findViewById<LinearLayout>(R.id.detail_table)
                val line = LinearLayout(this).apply { minimumHeight = (30 * resources.displayMetrics.density).toInt() }
                line.addView(TextView(this).apply { text = name }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                line.addView(TextView(this).apply { text = value ?: "정보 없음" }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f))
                table.addView(line)
            }
        }
        detailWork.submit {
            val publishCurrent: (StationDetail) -> Unit = { current ->
                runOnUiThread {
                    if (!isDestroyed && sheet.isShowing && detailSheet === sheet) bindCurrent(current)
                }
            }
            var unsupported = false
            val detail = try {
                if (bookmark != null) source.favoriteDetail(bookmark, publishCurrent)
                else source.detail(row, selectedFilter, publishCurrent)
            } catch (_: UnsupportedFilter) {
                unsupported = true
                null
            } catch (_: IOException) { null }
            runOnUiThread {
                if (isDestroyed || !sheet.isShowing || detailSheet !== sheet) return@runOnUiThread
                predictionPending = false
                if (detail == null) {
                    forecast.text = "30분 후\n예측 정보 부족"
                    field(R.id.detail_specs).apply {
                        text = if (unsupported) "지원하지 않는 조건 · 닫고 필터를 변경하세요." else "상세 조회 실패 · 다시 시도"
                        setOnClickListener { if (unsupported) sheet.dismiss() else openDetail(row, selectedFilter, bookmark) }
                    }
                    return@runOnUiThread
                }
                if (displayedDetail == null) bindCurrent(detail)
                displayedDetail = detail
                detailObservation = detail.current ?: row
                field(R.id.detail_specs).text = detail.asOf.takeIf { it.isNotBlank() }?.let { "관측 기준  ${koreanTime(it)} (한국시간)" } ?: "관측 시각 미제공"
                renderObservation(sheet, (detail.current ?: row).at(System.currentTimeMillis()), detail, System.currentTimeMillis())
            }
        }
    }

    private fun selectPin(point: SearchPoint) {
        stopLocation()
        candidateName = null
        candidate = point
        renderPin(false)
        updateViewMode()
    }

    private fun renderPin(recenter: Boolean) {
        text(R.id.selection).text = candidateName ?: "선택한 핀 · ${format(candidate)}"
        preview?.apply {
            if (recenter) center = candidate
            pin = candidate
            invalidate()
        }
        liveMap?.let { map ->
            val position = LatLng.from(candidate.latitude, candidate.longitude)
            marker?.remove()
            marker = map.labelManager?.layer?.addLabel(LabelOptions.from(position)
                .setStyles(LabelStyle.from(android.R.drawable.ic_menu_mylocation)))
            if (recenter) map.moveCamera(CameraUpdateFactory.newCenterPosition(position))
        }
    }

    private fun locate() {
        if (checkSelfPermission(ACCESS_COARSE_LOCATION) != PERMISSION_GRANTED &&
            checkSelfPermission(ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) {
            requestPermissions(arrayOf(ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION), 1)
        } else startLocation()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != 1) return
        if (results.any { it == PERMISSION_GRANTED }) startLocation()
        else locationUnavailable()
    }

    @Suppress("DEPRECATION")
    private fun startLocation() {
        stopLocation()
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (listener !== this) return
                stopLocation()
                candidateName = "내 위치"
                candidate = SearchPoint(location.latitude, location.longitude)
                renderPin(true)
                search(candidate)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        listener = callback
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var started = false
        for (provider in providers) {
            if (provider == LocationManager.GPS_PROVIDER && checkSelfPermission(ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) continue
            try {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestSingleUpdate(provider, callback, Looper.getMainLooper())
                    started = true
                }
            } catch (_: SecurityException) { /* Permission changed while checking providers. */ }
        }
        if (started) {
            text(R.id.selection).text = "현재 위치 확인 중 · 핀을 직접 선택할 수도 있습니다."
            handler.postDelayed(timeout, 12000)
        } else locationUnavailable()
    }

    private fun locationUnavailable() {
        stopLocation()
        text(R.id.selection).text = "현재 위치 확인 불가 · 장소를 검색하거나 핀을 선택하세요."
    }

    private fun stopLocation() {
        handler.removeCallbacks(timeout)
        listener?.let {
            try { manager.removeUpdates(it) } catch (_: SecurityException) { }
        }
        listener = null
    }

    private fun search(point: SearchPoint) {
        val moved = confirmed != null && confirmed != point
        val sameQuery = confirmed == point && requestedFilter == filter
        val delay = if (confirmed != null && !sameQuery) 180L else 0L
        if (candidate == point) confirmedName = candidateName
        confirmed = point
        requestedFilter = filter
        findViewById<View>(R.id.notice).visibility = View.GONE
        updateViewMode()
        val id = ++requestId
        val queryFilter = filter
        if (!sameQuery) {
            nearbyResult = null
            recommendationResult = null
            speedLookups.clear()
            rows = emptyList()
            renderStations()
            findViewById<LinearLayout>(R.id.recommend_cards).removeAllViews()
            findViewById<View>(R.id.recommend_scroll).scrollTo(0, 0)
        }
        nearbyNotice = if (nearbyResult == null) "주변 조회 중" else "갱신 중 · 이전 결과 표시"
        recommendationNotice = if (recommendationResult == null) "추천 조회 중" else "갱신 중 · 이전 추천 표시"
        updateNearbySummary()
        updateRecommendationSummary()
        findViewById<View>(R.id.nearby_loading).visibility = if (nearbyResult == null) View.VISIBLE else View.GONE
        nearbyWork.submit(delay) {
            var failure = "조회 실패 · 새로고침으로 다시 시도하세요."
            val result = try { source.nearby(point, queryFilter) } catch (_: UnsupportedFilter) {
                failure = "지원하지 않는 충전 조건 · 필터를 변경하세요."
                null
            } catch (_: IOException) { null }
            runOnUiThread {
                if (id == requestId && !isDestroyed && !isFinishing) {
                    if (result != null) {
                        nearbyResult = result
                        rows = result.rows
                        speedLookups.clear()
                        renderStations()
                    }
                    nearbyNotice = if (result != null) "" else failure + if (nearbyResult != null) " · 이전 결과 표시" else ""
                    findViewById<View>(R.id.nearby_loading).visibility = View.GONE
                    updateNearbySummary()
                    if (result == null && getString(R.string.wattup_api_base_url).isNotBlank() &&
                        getSystemService(android.net.ConnectivityManager::class.java)?.activeNetwork == null) {
                        AlertDialog.Builder(this).setMessage("네트워크 연결 상태를 확인해 주세요.")
                            .setPositiveButton("다시 시도") { _, _ -> confirmed?.let(::search) }
                            .setNegativeButton("닫기", null).show()
                    }
                }
            }
        }
        recommendationWork.submit(delay) {
            var failure = "추천 조회 실패 · 새로고침으로 다시 시도하세요."
            val result = try { source.recommendations(point, queryFilter) } catch (_: UnsupportedFilter) {
                failure = "추천을 지원하지 않는 조건 · 필터를 변경하세요."
                null
            } catch (_: IOException) { null }
            runOnUiThread {
                if (id != requestId || isDestroyed || isFinishing) return@runOnUiThread
                if (result != null) {
                    recommendationNotice = ""
                    val updated = result.at(System.currentTimeMillis(), queryFilter.availableOnly)
                    recommendationResult = updated
                    renderRecommendations(updated)
                } else {
                    recommendationNotice = failure + if (recommendationResult != null) " · 이전 추천 표시" else ""
                    updateRecommendationSummary()
                }
                if (moved && result != null && !listMode && !recommendationMode) {
                    val notice = text(R.id.notice)
                    notice.text = "새 위치의 Top-3 갱신 · 보기"
                    notice.visibility = View.VISIBLE
                    notice.setOnClickListener { recommendationMode = true; updateViewMode() }
                    notice.postDelayed({ if (id == requestId) notice.visibility = View.GONE }, 6000)
                }
            }
        }
    }

    private fun updateRecommendationSummary() {
        val result = recommendationResult
        if (result == null) {
            text(R.id.recommend_status).text = recommendationNotice
            return
        }
        val count = if (result.items.isEmpty()) "표시할 추천 없음 · 반경이나 필터를 변경해 주세요." else "추천 ${result.items.size}곳"
        val extra = listOfNotNull(result.totalCandidates?.takeIf { it != result.items.size }?.let { "전체 후보 ${it}곳" }) + result.warnings
        text(R.id.recommend_status).text = (listOf("${result.method} · $count") + extra + listOf(recommendationNotice).filter { it.isNotBlank() }).joinToString("\n")
    }

    private fun renderRecommendations(result: Recommendations) {
        val cards = findViewById<LinearLayout>(R.id.recommend_cards)
        cards.removeAllViews()
        updateRecommendationSummary()
        result.items.forEach { item ->
            val card = layoutInflater.inflate(R.layout.recommendation_card, cards, false)
            val row = item.row
            card.findViewById<TextView>(R.id.card_title).apply {
                text = "${item.rank}위 · ${row.name}"
                textSize = if (item.rank == 1) 21f else 18f
            }
            val distance = String.format(Locale.KOREA, "직선거리 %.2f km", row.meters / 1000.0)
            val quality = if (getString(R.string.wattup_api_base_url).isNotBlank() && row.asOf.isEmpty()) " · 관측 시각 미제공" else ""
            card.findViewById<TextView>(R.id.card_state).text =
                "$distance · ${item.detail.output ?: "출력 정보 없음"}\n${row.badge}$quality"
            card.findViewById<TextView>(R.id.card_forecast).text = item.detail.forecastLabel
            card.findViewById<ProgressBar>(R.id.card_gauge).apply {
                val percent = item.detail.percent
                if (percent == null) visibility = View.GONE else progress = percent
            }
            card.findViewById<TextView>(R.id.card_reason).text = item.reason
            card.findViewById<View>(R.id.card_body).setOnClickListener { openDetail(row.at(System.currentTimeMillis())) }
            val width = (resources.displayMetrics.widthPixels * (if (item.rank == 1) 0.83f else 0.73f)).toInt()
            cards.addView(card, width, LayoutParams.MATCH_PARENT)
        }
    }

    @Deprecated("Android Activity back navigation")
    override fun onBackPressed() {
        if (recommendationMode || listMode) returnToPreviousView() else super.onBackPressed()
    }

    private fun returnToPreviousView() {
        if (recommendationMode) recommendationMode = false else listMode = false
        updateViewMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("guest", guest)
        outState.putString("candidateName", candidateName)
        outState.putString("confirmedName", confirmedName)
        outState.putInt("radiusM", filter.radiusM)
        outState.putString("speed", filter.speed.name)
        outState.putBoolean("availableOnly", filter.availableOnly)
        outState.putBoolean("dcCombo", filter.dcCombo)
        outState.putBoolean("listMode", listMode)
        outState.putBoolean("recommendationMode", recommendationMode)
        outState.putDouble("lat", candidate.latitude)
        outState.putDouble("lon", candidate.longitude)
        confirmed?.let {
            outState.putDouble("queryLat", it.latitude)
            outState.putDouble("queryLon", it.longitude)
        }
    }

    override fun onResume() { super.onResume(); mapView?.resume(); freshnessTick.run() }
    override fun onPause() { handler.removeCallbacks(freshnessTick); mapView?.pause(); super.onPause() }
    override fun onStop() {
        if (listener != null) locationUnavailable()
        super.onStop()
    }
    override fun onDestroy() {
        detailSheet?.dismiss()
        stopLocation()
        speedWorker.shutdownNow()
        nearbyWork.close()
        recommendationWork.close()
        detailWork.close()
        placeWork.close()
        favoritesWork.close()
        super.onDestroy()
    }
}
