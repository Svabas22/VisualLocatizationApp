package com.example.visuallocatizationapp.network

import com.example.visuallocatizationapp.Zone
import com.google.android.gms.awareness.snapshot.LocationResponse
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
    private const val BASE_URL = "http://10.0.2.2:5000/"
    //private const val BASE_URL = "http://192.168.1.233:5000/"

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}