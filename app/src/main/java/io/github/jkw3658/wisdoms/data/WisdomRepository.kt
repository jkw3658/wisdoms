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
        val list = withContext(Dispatchers.IO) { download(s.scriptUrl, s.token) }
        withContext(Dispatchers.IO) { cacheFile.writeText(Wisdom.listToJson(list).toString()) }
        context.dataStore.edit {
            it[Keys.LAST_FETCHED] = System.currentTimeMillis()
            it.remove(Keys.DECK)
        }
        return list.size
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
        }
        wisdom
    }

    private fun download(scriptUrl: String, token: String): List<Wisdom> {
        val sep = if ('?' in scriptUrl) '&' else '?'
        val url = URL(scriptUrl + sep + "token=" + URLEncoder.encode(token, "UTF-8"))
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
            return Wisdom.listFromJson(json.getJSONArray("items"))
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
    }

    companion object {
        @Volatile private var instance: WisdomRepository? = null

        fun get(context: Context): WisdomRepository =
            instance ?: synchronized(this) {
                instance ?: WisdomRepository(context.applicationContext).also { instance = it }
            }
    }
}
