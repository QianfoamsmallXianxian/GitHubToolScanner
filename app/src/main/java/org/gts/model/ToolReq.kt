package org.gts.model

data class ToolReq(
    val tool: String,
    val version: String?,
    val source: String,
    val confidence: Confidence
)

enum class Confidence { HIGH, MEDIUM, LOW }
