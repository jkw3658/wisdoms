package io.github.jkw3658.wisdoms.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 「1 つ前に戻る」で遡れる件数 */
private const val MAX_HISTORY = 30

data class Settings(
    val scriptUrl: String = "",
    val token: String = "",
    /** 空集合 = すべてのカテゴリを表示 */
    val selectedCategories: Set<String> = emptySet(),
    val lastFetchedAt: Long = 0L,
) {
    val isConfigured: Boolean get() = scriptUrl.isNotBlank() && token.isNotBlank()
}

class WisdomRepository private constructor(private val context: Context) {

    private val cacheFile = File(context.filesDir, "wisdoms.json")
    private val mutex = Mutex()

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    val current: Flow<Wisdom?> = context.dataStore.data.map { prefs ->
        prefs[Keys.CURRENT]?.let { Wisdom.fromJson(JSONObject(it)) }
    }

    /** 「1 つ前に戻る」ができるか */
    val canGoBack: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.HISTORY]?.let { decodeDeck(it).isNotEmpty() } ?: false
    }

    suspend fun currentWisdom(): Wisdom? = current.first()

    suspend fun saveConnection(scriptUrl: String, token: String) {
        context.dataStore.edit {
            it[Keys.SCRIPT_URL] = scriptUrl.trim()
            it[Keys.TOKEN] = token.trim()
        }
    }

    suspend fun saveSelectedCategories(categories: Set<String>) {
        context.dataStore.edit {
            it[Keys.CATEGORIES] = categories
            it.remove(Keys.DECK)
        }
    }

    suspend fun cachedWisdoms(): List<Wisdom> = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) return@withContext emptyList()
        runCatching { Wisdom.listFromJson(JSONArray(cacheFile.readText())) }.getOrDefault(emptyList())
    }

    /** Apps Script から最新の一覧を取得してキャッシュする。件数を返す。 */
    suspend fun refresh(): Int {
        val s = settings.first()
        require(s.isConfigured) { "設定画面で URL とトークンを入力してください" }
        val json = withContext(Dispatchers.IO) { callScript(s, emptyMap()) }
        val list = Wisdom.listFromJson(json.getJSONArray("items"))
        mutex.withLock {
            writeCache(list)
            context.dataStore.edit { prefs ->
                prefs[Keys.LAST_FETCHED] = System.currentTimeMillis()
                prefs.remove(Keys.DECK)
                // 表示中の言葉もシートの最新状態（チェックなど）に合わせる
                val cur = prefs[Keys.CURRENT]?.let { Wisdom.fromJson(JSONObject(it)) }
                list.firstOrNull { it.text == cur?.text }?.let { prefs[Keys.CURRENT] = it.toJson().toString() }
            }
        }
        return list.size
    }

    /**
     * 表示中の言葉のチェックを切り替え、シートの「チェック」列に書き込む。
     * 画面はすぐに切り替え、書き込みに失敗したら元に戻して例外を投げる。
     */
    suspend fun toggleCurrentChecked() {
        val target = currentWisdom() ?: return
        val checked = !target.checked
        mutex.withLock { updateLocal(target, checked) }
        try {
            val s = settings.first()
            require(s.isConfigured) { "設定画面で URL とトークンを入力してください" }
            val res = withContext(Dispatchers.IO) {
                callScript(
                    s,
                    mapOf(
                        "action" to "check",
                        "row" to target.row.toString(),
                        "head" to target.text.take(30),
                        "checked" to checked.toString(),
                    ),
                )
            }
            // 古い Apps Script は action=check を知らず一覧を返すだけなので検出する
            check(res.optBoolean("ok")) { "Apps Script を最新版に更新してください" }
        } catch (e: Exception) {
            mutex.withLock { updateLocal(target, target.checked) }
            throw e
        }
    }

    private suspend fun updateLocal(target: Wisdom, checked: Boolean) {
        writeCache(cachedWisdoms().map { if (it.isSameRow(target)) it.copy(checked = checked) else it })
        context.dataStore.edit { prefs ->
            val cur = prefs[Keys.CURRENT]?.let { Wisdom.fromJson(JSONObject(it)) }
            if (cur != null && cur.isSameRow(target)) {
                prefs[Keys.CURRENT] = cur.copy(checked = checked).toJson().toString()
            }
        }
    }

    private suspend fun writeCache(list: List<Wisdom>) = withContext(Dispatchers.IO) {
        cacheFile.writeText(Wisdom.listToJson(list).toString())
    }

    /**
     * 次の格言に進める。山札方式でシャッフルし、全件を一巡するまで同じものは出さない。
     */
    suspend fun next(): Wisdom? = mutex.withLock {
        val s = settings.first()
        val all = cachedWisdoms()
        val pool = if (s.selectedCategories.isEmpty()) all
        else all.filter { it.categoryKey in s.selectedCategories }.ifEmpty { all }
        if (pool.isEmpty()) return null

        val prefs = context.dataStore.data.first()
        val currentText = prefs[Keys.CURRENT]?.let { JSONObject(it).optString("text") }
        val poolTexts = pool.map { it.text }.toSet()
        var deck = prefs[Keys.DECK]?.let { decodeDeck(it) }
            ?.filter { it in poolTexts }
            .orEmpty()
        if (deck.isEmpty()) {
            deck = poolTexts.shuffled()
            // 山札を作り直した直後に直前と同じものが出ないようにする
            if (deck.size > 1 && deck.first() == currentText) deck = deck.drop(1) + deck.first()
        }
        val text = deck.first()
        val wisdom = pool.first { it.text == text }
        context.dataStore.edit {
            it[Keys.DECK] = encodeDeck(deck.drop(1))
            it[Keys.CURRENT] = wisdom.toJson().toString()
            // 戻れるように、それまで表示していた言葉を履歴に積む
            prefs[Keys.CURRENT]?.let { cur ->
                val history = decodeDeck(prefs[Keys.HISTORY] ?: "[]") + cur
                it[Keys.HISTORY] = encodeDeck(history.takeLast(MAX_HISTORY))
            }
        }
        wisdom
    }

    /**
     * 1 つ前の言葉に戻る。いま表示している言葉は山札の先頭に戻し、
     * 次へ進むと再び表示されるようにする。
     */
    suspend fun previous(): Wisdom? = mutex.withLock {
        val prefs = context.dataStore.data.first()
        val history = decodeDeck(prefs[Keys.HISTORY] ?: "[]")
        if (history.isEmpty()) return null
        val stored = Wisdom.fromJson(JSONObject(history.last()))
        // チェック状態などはキャッシュの最新値を使う
        val wisdom = cachedWisdoms().firstOrNull { it.isSameRow(stored) } ?: stored
        val currentText = prefs[Keys.CURRENT]?.let { JSONObject(it).optString("text") }
        val deck = decodeDeck(prefs[Keys.DECK] ?: "[]")
        context.dataStore.edit {
            it[Keys.HISTORY] = encodeDeck(history.dropLast(1))
            it[Keys.CURRENT] = wisdom.toJson().toString()
            if (currentText != null) it[Keys.DECK] = encodeDeck(listOf(currentText) + deck)
        }
        wisdom
    }

    private fun callScript(s: Settings, params: Map<String, String>): JSONObject {
        val query = (mapOf("token" to s.token) + params).entries
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val sep = if ('?' in s.scriptUrl) '&' else '?'
        val url = URL(s.scriptUrl + sep + query)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("サーバーエラー (HTTP $code)")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = runCatching { JSONObject(body) }.getOrElse {
                error("応答を読み取れません。Apps Script のアクセス権が「全員」になっているか確認してください")
            }
            if (json.has("error")) {
                val e = json.getString("error")
                error(if (e == "unauthorized") "トークンが一致しません" else e)
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun encodeDeck(deck: List<String>): String = JSONArray(deck).toString()

    private fun decodeDeck(s: String): List<String> = runCatching {
        val arr = JSONArray(s)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    private fun Preferences.toSettings() = Settings(
        scriptUrl = this[Keys.SCRIPT_URL].orEmpty(),
        token = this[Keys.TOKEN].orEmpty(),
        selectedCategories = this[Keys.CATEGORIES].orEmpty(),
        lastFetchedAt = this[Keys.LAST_FETCHED] ?: 0L,
    )

    private object Keys {
        val SCRIPT_URL = stringPreferencesKey("script_url")
        val TOKEN = stringPreferencesKey("token")
        val CATEGORIES = stringSetPreferencesKey("categories")
        val LAST_FETCHED = longPreferencesKey("last_fetched_at")
        val CURRENT = stringPreferencesKey("current")
        val DECK = stringPreferencesKey("deck")
        val HISTORY = stringPreferencesKey("history")
    }

    companion object {
        @Volatile private var instance: WisdomRepository? = null

        fun get(context: Context): WisdomRepository =
            instance ?: synchronized(this) {
                instance ?: WisdomRepository(context.applicationContext).also { instance = it }
            }
    }
}
