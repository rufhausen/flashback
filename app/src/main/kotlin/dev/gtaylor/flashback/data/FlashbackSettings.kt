package dev.gtaylor.flashback.data

import kotlinx.serialization.Serializable

@Serializable
enum class AfterAlert {
    /** Put the light back in whatever mode it was in before the vehicle showed up. */
    RESTORE_PREVIOUS,

    /** Always switch the light off once the road is clear. */
    OFF,
}

@Serializable
data class FlashbackSettings(
    val enabled: Boolean = true,
    /** Karoo device IDs of the ANT+ lights that should flash. */
    val lightIds: Set<String> = emptySet(),
    /** Karoo LightMode name to use while a vehicle is approaching. */
    val alertMode: String = "FAST_FLASH",
    /** Mode for a fast-approaching vehicle; null = same as [alertMode]. */
    val fastAlertMode: String? = null,
    /** In-ride alert when Flashback can't control the light, so you don't ride dark assuming it works. */
    val healthAlerts: Boolean = true,
    val afterAlert: AfterAlert = AfterAlert.RESTORE_PREVIOUS,
    /** Keep flashing this long after the radar reports clear, so back-to-back cars don't strobe the mode. */
    val clearHoldSeconds: Int = 3,
    /** 1 = any approaching vehicle, 2 = fast-approaching only. */
    val minThreatLevel: Int = 1,
)
