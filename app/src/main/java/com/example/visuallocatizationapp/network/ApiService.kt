package com.example.visuallocatizationapp.network

import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Streaming
import retrofit2.http.Url

interface ApiService {
    @Multipart
    @POST("/upload")
    suspend fun uploadVideo(
        @Part video: MultipartBody.Part
    ): Response<UploadResponse>

    @GET("zones.json")
    suspend fun getZones(): Response<ResponseBody>

    @Streaming // important for large ZIPs (260 MB)
    @GET
    suspend fun downloadZone(@Url url: String): Response<ResponseBody>
}

object ApiClient {
    private const val BASE_URL = "https://zones.blob.core.windows.net/zonekaunas/"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
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

    val baseUrl: String
        get() = BASE_URL
}
