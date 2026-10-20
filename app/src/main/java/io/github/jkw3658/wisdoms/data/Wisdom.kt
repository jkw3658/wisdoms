package io.github.jkw3658.wisdoms.data

import org.json.JSONArray
import org.json.JSONObject

data class Wisdom(
    val text: String,
    val category: String = "",
    val source: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("category", category)
        .put("source", source)

    companion object {
        fun fromJson(o: JSONObject) = Wisdom(
            text = o.optString("text"),
            category = o.optString("category"),
            source = o.optString("source"),
        )

        fun listFromJson(arr: JSONArray): List<Wisdom> =
            (0 until arr.length())
                .map { fromJson(arr.getJSONObject(it)) }
                .filter { it.text.isNotBlank() }

        fun listToJson(list: List<Wisdom>): JSONArray =
            JSONArray().apply { list.forEach { put(it.toJson()) } }
    }
}

/** カテゴリが空欄の格言を設定画面で選択するときの表示名 */
const val NO_CATEGORY = "（未分類）"

val Wisdom.categoryKey: String get() = category.ifBlank { NO_CATEGORY }
