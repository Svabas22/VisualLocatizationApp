package com.example.visuallocatizationapp.network

import com.example.visuallocatizationapp.Zone
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

interface ApiService {
    // Return raw body so we can parse array or object-wrapped lists manually
    @GET("zones.json")
    suspend fun getZones(): Response<ResponseBody>

    @GET
    suspend fun downloadZone(@Url url: String): Response<ResponseBody>
}

object ApiClient {
    // Public container URL (ends with a slash)
    private const val BASE_URL = "https://zones.blob.core.windows.net/zonekaunas/"


    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(httpClient)
            .build()
            .create(ApiService::class.java)
    }
}
