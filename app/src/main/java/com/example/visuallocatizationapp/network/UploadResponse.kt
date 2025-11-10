package com.example.visuallocatizationapp.network

data class UploadResponse(
    val latitude: Double,
    val longitude: Double,
    val confidence: Double,
    val status: String? = null
)
