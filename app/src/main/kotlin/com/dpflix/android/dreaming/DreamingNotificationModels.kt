package com.dpflix.android.dreaming

import kotlinx.serialization.Serializable

@Serializable
data class DreamingNotification(
    val id: String,
    val type: String = "announcement",
    val titre: String = "",
    val texte: String = "",
    val images: List<String> = emptyList(),
    val videoUrl: String = "",
    val actionLabel: String = "Regarder",
    val startAt: String = "",
    val endAt: String? = null,
    val active: Boolean = true,
    val priority: Int = 0,
    val createdAt: String = "",
    val updatedAt: String = ""
)

@Serializable
data class DreamingNotificationResponse(
    val ok: Boolean = false,
    val version: Long = 0,
    val items: List<DreamingNotification> = emptyList()
)
