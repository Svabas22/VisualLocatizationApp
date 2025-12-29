package com.example.visuallocatizationapp.network

import com.example.visuallocatizationapp.Zone
import com.google.android.gms.awareness.snapshot.LocationResponse
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path


data class LocationResult(
    val status: String,
    val latitude: Double,
    val longitude: Double,
    val confidence: Double
)


interface ApiService {
    @Multipart
    @POST("/upload")
    suspend fun uploadVideo(
        @Part video: MultipartBody.Part
    ): Response<UploadResponse>

    @GET("/zones")
    suspend fun getZones(): Response<List<Zone>>

    @GET("/zones/{zoneId}")
    suspend fun downloadZone(@Path("zoneId") zoneId: String): Response<ResponseBody>
}



object ApiClient {
    //private const val BASE_URL = "http://10.0.2.2:5000/"
    //private const val BASE_URL = "http://192.168.1.233:5000/"
    //private const val BASE_URL = "http://192.168.1.105:5000/"
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
}
