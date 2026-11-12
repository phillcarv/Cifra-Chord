package com.cifra.app.network

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

data class TrackInfo(
    @SerializedName("is_recognized") val isRecognized: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val confidence: Float?
)

data class ChordEvent(
    val chord: String,
    @SerializedName("start_time") val startTime: Float,
    @SerializedName("end_time") val endTime: Float
)

data class CifraLine(
    @SerializedName("chords_line") val chordsLine: String,
    @SerializedName("lyrics_line") val lyricsLine: String
)

data class CifraResponse(
    @SerializedName("track_info") val trackInfo: TrackInfo,
    @SerializedName("duration_seconds") val durationSeconds: Float,
    @SerializedName("detected_key") val detectedKey: String?,
    val bpm: Float?,
    val chords: List<ChordEvent>,
    @SerializedName("formatted_cifra") val formattedCifra: String,
    val lines: List<CifraLine>
)

interface ApiService {
    @Multipart
    @POST("api/v1/process-audio")
    suspend fun processAudio(
        @Part file: MultipartBody.Part
    ): CifraResponse
}

object ApiClient {
    // 10.0.2.2 é o endereço do localhost da máquina host no emulador Android oficial.
    // Para testar em dispositivo físico via Wi-Fi, substitua pelo IP da sua máquina (ex: 192.168.1.X:8000).
    var baseUrl: String = "http://10.0.2.2:8000/"

    fun getService(): ApiService {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
