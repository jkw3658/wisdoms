package io.github.jkw3658.wisdoms.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.jkw3658.wisdoms.MainActivity
import io.github.jkw3658.wisdoms.data.Wisdom
import io.github.jkw3658.wisdoms.data.WisdomRepository

class WisdomWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = WisdomRepository.get(context)
        val initial = repo.currentWisdom()
        provideContent {
            // ウィジェット表示中も変更を受け取れるよう、Flow を購読して再描画する
            val wisdom by repo.current.collectAsState(initial = initial)
            GlanceTheme {
                Content(wisdom)
            }
        }
    }

    @Composable
    private fun Content(wisdom: Wisdom?) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(20.dp)
                .padding(14.dp)
                .clickable(
                    if (wisdom == null) actionStartActivity<MainActivity>()
                    else actionRunCallback<NextWisdomAction>()
                ),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = wisdom?.category.orEmpty(),
                    style = TextStyle(
                        color = GlanceTheme.colors.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                )
                if (wisdom != null) {
                    CheckBox(
                        checked = wisdom.checked,
                        onCheckedChange = actionRunCallback<ToggleCheckAction>(),
                        modifier = GlanceModifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "⚙",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(start = 8.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Text(
                text = wisdom?.text ?: "タップしてアプリを開き、シートの接続を設定してください",
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 21.sp,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
            if (!wisdom?.source.isNullOrBlank()) {
                Spacer(GlanceModifier.height(8.dp))
                Text(
                    text = "— ${wisdom!!.source}",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.End,
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }

    companion object {
        suspend fun showNext(context: Context) {
            WisdomRepository.get(context).next()
            WisdomWidget().updateAll(context)
        }
    }
}

class NextWisdomAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        WisdomWidget.showNext(context)
    }
}

class ToggleCheckAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            WisdomRepository.get(context).toggleCurrentChecked()
        } catch (e: Exception) {
            val message = "シートに反映できませんでした: ${e.message}"
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        WisdomWidget().updateAll(context)
    }
}

class WisdomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WisdomWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SyncWorker.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        SyncWorker.cancel(context)
    }
}
