package dev.gtaylor.flashback

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.gtaylor.flashback.data.FlashbackSettings
import dev.gtaylor.flashback.data.SettingsRepository
import dev.gtaylor.flashback.ui.SettingsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: SettingsRepository

    // The extension service runs in our process; the activity can come up before Karoo has started it.
    private val extension = MutableStateFlow<FlashbackExtension?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(applicationContext)

        lifecycleScope.launch {
            while (FlashbackExtension.instance == null) delay(500)
            extension.value = FlashbackExtension.instance?.also { it.setSettingsUiActive(true) }
        }

        val lightsFlow = extension.flatMapLatest { it?.lights ?: flowOf(emptyList()) }
        val modesFlow = extension.flatMapLatest { it?.lightControl?.supportedModes ?: flowOf(emptyMap()) }
        val radarFlow = extension.flatMapLatest { it?.radarStatus ?: flowOf("Waiting for Karoo…") }

        setContent {
            val settings by repository.settings.collectAsState(FlashbackSettings())
            val lights by lightsFlow.collectAsState(emptyList())
            val modes by modesFlow.collectAsState(emptyMap())
            val radar by radarFlow.collectAsState("Waiting for Karoo…")

            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    SettingsScreen(
                        settings = settings,
                        lights = lights,
                        supportedModes = modes,
                        radarStatus = radar,
                        onChange = { transform -> lifecycleScope.launch { repository.update(transform) } },
                        onTest = { id, mode -> extension.value?.testFlash(id, mode) },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        extension.value?.setSettingsUiActive(false)
        super.onDestroy()
    }
}
