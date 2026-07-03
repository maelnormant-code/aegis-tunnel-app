package com.example.data.api

import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Body

interface HeimdallApiService {
    @POST("api/chat")
    suspend fun sendChat(@Body request: ChatRequest): ChatResponse

    @POST("api/goal")
    suspend fun sendGoal(@Body request: GoalRequest): GoalResponse

    @GET("api/status")
    suspend fun checkStatus(): StatusResponse
}

data class ChatRequest(val query: String)
data class ChatResponse(val status: String, val response: String? = null, val message: String? = null)

data class GoalRequest(val goal: String)
data class GoalResponse(val status: String, val response: String? = null, val message: String? = null)

data class StatusResponse(
    val status: String,
    val wireguard: String? = null,
    val syncthing: String? = null,
    val aegis_node: String? = null,
    val api_version: String? = null
)
