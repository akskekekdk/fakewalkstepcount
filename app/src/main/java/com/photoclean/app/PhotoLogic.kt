package com.photoclean.app

import java.util.Calendar

/** 화면과 상관없는 계산들 (단위 테스트 대상). */
object PhotoLogic {

    /** 2026년 10월 → 2026*12+9 */
    fun monthKey(millis: Long, cal: Calendar = Calendar.getInstance()): Int {
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    fun monthLabel(key: Int): String = "${key / 12}년 ${key % 12 + 1}월"

    fun monthLabelOf(millis: Long): String = monthLabel(monthKey(millis))

    /** 사진 목록 → 월별 머리말이 들어간 줄 목록 (withHeaders=false 면 사진만). */
    fun buildRows(photos: List<Photo>, withHeaders: Boolean): List<GridRow> {
        if (!withHeaders) return photos.indices.map { GridRow.Item(it) }
        val cal = Calendar.getInstance()
        val keys = IntArray(photos.size) { monthKey(photos[it].dateMillis, cal) }
        val rows = ArrayList<GridRow>(photos.size + 32)
        var i = 0
        while (i < photos.size) {
            var j = i
            while (j < photos.size && keys[j] == keys[i]) j++
            rows.add(GridRow.Header(monthLabel(keys[i]), j - i))
            for (k in i until j) rows.add(GridRow.Item(k))
            i = j
        }
        return rows
    }

    /** 폴더별로 묶기 (사진 많은 폴더 먼저). 대표 사진 = 폴더에서 가장 최근 사진. */
    fun groupFolders(all: List<Photo>): List<Folder> =
        all.groupBy { it.bucketId }
            .map { (id, list) -> Folder(id, folderDisplayName(list.first().bucketName), list.size, list.first()) }
            .sortedByDescending { it.count }

    fun folderDisplayName(raw: String): String = when (raw.lowercase()) {
        "camera" -> "카메라"
        "screenshots" -> "스크린샷"
        "download", "downloads" -> "다운로드"
        "kakaotalk" -> "카카오톡"
        "pictures" -> "사진"
        "instagram" -> "인스타그램"
        "" -> "기타"
        else -> raw
    }

    /**
     * 선명도 점수: 라플라시안(윤곽선) 값의 분산.
     * 초점이 맞은 사진은 윤곽이 뚜렷해 값이 크고, 흐린 사진은 작다.
     * gray: 0~255 밝기, w*h 크기.
     */
    fun laplacianVariance(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val v = gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] - 4 * gray[i]
                sum += v
                sumSq += v.toDouble() * v
                n++
            }
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** 민감도 0=낮음, 1=보통, 2=높음 → 이 점수보다 낮으면 '흐린 사진' */
    fun blurThreshold(sensitivity: Int): Double = when (sensitivity) {
        0 -> 40.0
        2 -> 140.0
        else -> 80.0
    }

    /**
     * 끌어서 선택: 시작한 사진(anchor)부터 지금 손가락 아래 사진(current)까지를
     * select 상태로 맞춘다. 항상 끌기 전 선택(base)에서 다시 계산하므로
     * 손가락을 되돌리면 범위 밖 사진은 원래 상태로 돌아간다.
     */
    fun dragSelect(base: Set<Long>, ids: List<Long>, anchor: Int, current: Int, select: Boolean): Set<Long> {
        val out = LinkedHashSet(base)
        if (ids.isEmpty()) return out
        val from = minOf(anchor, current).coerceIn(0, ids.size - 1)
        val to = maxOf(anchor, current).coerceIn(0, ids.size - 1)
        for (i in from..to) {
            if (select) out.add(ids[i]) else out.remove(ids[i])
        }
        return out
    }

    /** 최신순(날짜 내림차순, 같으면 id 내림차순)을 유지하며 끼워 넣기. */
    fun insertSorted(list: MutableList<Photo>, p: Photo) {
        var idx = list.indexOfFirst { it.dateMillis < p.dateMillis || (it.dateMillis == p.dateMillis && it.id < p.id) }
        if (idx < 0) idx = list.size
        list.add(idx, p)
    }

    /** 지금 보기 조건(폴더·모드)에 맞는 사진만. 흐린 사진은 가장 흐린 것부터. */
    fun applyFilter(
        all: List<Photo>,
        mode: ViewMode,
        bucketId: String?,
        blurScores: Map<Long, Float>,
        threshold: Double,
    ): List<Photo> {
        val base = if (bucketId == null) all else all.filter { it.bucketId == bucketId }
        return when (mode) {
            ViewMode.ALL -> base
            ViewMode.FAVORITES -> base.filter { it.favorite }
            ViewMode.BLURRY -> base
                .filter { p -> blurScores[p.id]?.let { it < threshold } == true }
                .sortedBy { blurScores[it.id] }
        }
    }
}
