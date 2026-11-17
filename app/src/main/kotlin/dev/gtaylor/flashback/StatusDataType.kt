package dev.gtaylor.flashback

import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import dev.gtaylor.flashback.radar.FlashbackStatus
import dev.gtaylor.flashback.radar.StatusLevel
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Ride-screen field: is Flashback active, flashing, or broken — and what the light says it's doing. */
class StatusDataType(
    private val status: StateFlow<FlashbackStatus>,
) : DataTypeImpl("flashback", "status") {
    override fun startView(
        context: Context,
        config: ViewConfig,
        emitter: ViewEmitter,
    ) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch {
            status.collect { s ->
                val views = RemoteViews(context.packageName, R.layout.status_field)
                views.setTextViewText(R.id.status_headline, s.headline)
                views.setTextViewText(R.id.status_detail, s.detail)
                views.setInt(R.id.status_root, "setBackgroundColor", colorFor(s.level))
                emitter.updateView(views)
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun colorFor(level: StatusLevel): Int =
        when (level) {
            StatusLevel.IDLE -> Color.rgb(0x44, 0x44, 0x44)
            StatusLevel.ACTIVE -> Color.rgb(0x1B, 0x7F, 0x3A)
            StatusLevel.FLASHING -> Color.rgb(0xE0, 0x8A, 0x00)
            StatusLevel.PROBLEM -> Color.rgb(0xC6, 0x28, 0x28)
        }
}
