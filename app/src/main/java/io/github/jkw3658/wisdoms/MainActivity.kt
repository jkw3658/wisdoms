package io.github.jkw3658.wisdoms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jkw3658.wisdoms.data.Settings
import io.github.jkw3658.wisdoms.data.WisdomRepository
import io.github.jkw3658.wisdoms.data.categoryKey
import io.github.jkw3658.wisdoms.widget.SyncWorker
import io.github.jkw3658.wisdoms.widget.WisdomWidget
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val repo = remember { WisdomRepository.get(context) }
    val scope = rememberCoroutineScope()
    val settings by repo.settings.collectAsState(initial = null)
    val current by repo.current.collectAsState(initial = null)
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var count by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    val s = settings ?: return

    LaunchedEffect(s.lastFetchedAt) {
        val all = repo.cachedWisdoms()
        count = all.size
        categories = all.map { it.categoryKey }.distinct()
    }
    LaunchedEffect(Unit) {
        SyncWorker.schedule(context)
    }

    fun showNext() = scope.launch { WisdomWidget.showNext(context) }

    fun refresh() = scope.launch {
        loading = true
        message = runCatching { repo.refresh() }
            .fold({ "${it} 件を読み込みました" }, { "読み込みに失敗しました: ${it.message}" })
        WisdomWidget.showNext(context)
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("今日のひとこと", style = MaterialTheme.typography.headlineSmall)

        Card(
            modifier = Modifier.fillMaxWidth().clickable { showNext() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val w = current
                if (w == null) {
                    Text(
                        if (s.isConfigured) "「シートから再読み込み」を押してください"
                        else "下の「シートの接続」で URL とトークンを設定してください",
                    )
                } else {
                    if (w.category.isNotBlank()) {
                        Text(
                            w.category,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(w.text, fontSize = 26.sp, lineHeight = 39.sp)
                    if (w.source.isNotBlank()) {
                        Text(
                            "— ${w.source}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                Text(
                    "タップで次へ",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { refresh() }, enabled = s.isConfigured && !loading) {
                Text(if (loading) "読み込み中…" else "シートから再読み込み")
            }
            Text(
                lastFetchedLabel(s, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        HorizontalDivider()
        SectionTitle("表示するカテゴリ")
        if (categories.isEmpty()) {
            Text("シートを読み込むとカテゴリが表示されます", style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = s.selectedCategories.isEmpty(),
                    onClick = {
                        scope.launch {
                            repo.saveSelectedCategories(emptySet())
                            WisdomWidget.showNext(context)
                        }
                    },
                    label = { Text("すべて") },
                )
                categories.forEach { cat ->
                    val selected = cat in s.selectedCategories
                    FilterChip(
                        selected = selected,
                        onClick = {
                            val next = if (selected) s.selectedCategories - cat else s.selectedCategories + cat
                            scope.launch {
                                // 全部外した / 全部選んだ場合は「すべて」に戻す
                                repo.saveSelectedCategories(
                                    if (next.isEmpty() || next.containsAll(categories)) emptySet() else next
                                )
                                WisdomWidget.showNext(context)
                            }
                        },
                        label = { Text(cat) },
                    )
                }
            }
        }


        HorizontalDivider()
        ConnectionSection(s) { url, token ->
            scope.launch {
                repo.saveConnection(url, token)
                refresh()
            }
        }

        HorizontalDivider()
        SectionTitle("ウィジェットの追加方法")
        Text(
            "ホーム画面の空いている所を長押し →「ウィジェット」→「格言」を選んで配置してください。ウィジェットをタップすると次の言葉に切り替わります。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ConnectionSection(s: Settings, onSave: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(!s.isConfigured) }
    var url by remember(s.scriptUrl) { mutableStateOf(s.scriptUrl) }
    var token by remember(s.token) { mutableStateOf(s.token) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("シートの接続", Modifier.weight(1f))
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "閉じる" else "編集") }
    }
    if (!expanded) {
        Text(
            if (s.isConfigured) "設定済み" else "未設定",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    OutlinedTextField(
        value = url,
        onValueChange = { url = it },
        label = { Text("Apps Script のウェブアプリ URL") },
        placeholder = { Text("https://script.google.com/macros/s/…/exec") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text("トークン") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = {
            onSave(url, token)
            expanded = false
        },
        enabled = url.isNotBlank() && token.isNotBlank(),
    ) { Text("保存して読み込む") }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)
}

private fun lastFetchedLabel(s: Settings, count: Int): String {
    if (s.lastFetchedAt == 0L) return "未取得"
    val time = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(s.lastFetchedAt))
    return "$count 件 · $time 更新"
}
