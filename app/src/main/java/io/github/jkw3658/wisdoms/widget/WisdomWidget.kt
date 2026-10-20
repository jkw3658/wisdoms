package io.github.jkw3658.wisdoms.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import io.github.jkw3658.wisdoms.MainActivity
import io.github.jkw3658.wisdoms.data.Wisdom
import io.github.jkw3658.wisdoms.data.WisdomRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WisdomWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val wisdom = WisdomRepository.get(context).currentWisdom()
        provideContent {
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
                Text(
                    text = "⚙",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 14.sp),
                    modifier = GlanceModifier.padding(start = 8.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            Box(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = wisdom?.text ?: "タップしてアプリを開き、シートの接続を設定してください",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 16.sp,
                    ),
                )
            }
            if (!wisdom?.source.isNullOrBlank()) {
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

class WisdomWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WisdomWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                RotateWorker.schedule(context)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RotateWorker.cancel(context)
    }
}
