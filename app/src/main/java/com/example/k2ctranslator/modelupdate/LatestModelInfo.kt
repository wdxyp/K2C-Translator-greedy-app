package com.example.k2ctranslator.modelupdate

data class LatestModelInfo(
    val modelVersion: String,
    val zipUrl: String,
    val zipSha256: String,
    val notes: String?,
)

