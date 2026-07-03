package com.example.data.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

class HeimdallRepository {
    private var cachedApiService: HeimdallApiService? = null
    private var cachedUrl: String? = null

    private fun getApiService(baseUrl: String): HeimdallApiService {
        val formattedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        if (cachedApiService != null && cachedUrl == formattedUrl) {
            return cachedApiService!!
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(130, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })
            .build()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(formattedUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        val service = retrofit.create(HeimdallApiService::class.java)
        cachedApiService = service
        cachedUrl = formattedUrl
        return service
    }

    suspend fun sendChat(baseUrl: String, text: String): Result<String> {
        return try {
            val service = getApiService(baseUrl)
            val response = service.sendChat(ChatRequest(text))
            Result.success(response.response ?: response.message ?: "[Empty Response]")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendGoal(baseUrl: String, goal: String): Result<String> {
        return try {
            val service = getApiService(baseUrl)
            val response = service.sendGoal(GoalRequest(goal))
            Result.success(response.response ?: response.message ?: "[Empty Response]")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkStatus(baseUrl: String): Result<StatusResponse> {
        return try {
            val service = getApiService(baseUrl)
            val response = service.checkStatus()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
