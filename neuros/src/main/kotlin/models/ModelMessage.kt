package ai.models

import kotlinx.serialization.Serializable

@Serializable
data class ModelMessage(
    val type: String,
    val modelId: String? = null,
    val payload: String? = null,
    val requestId: String? = null
)
