package com.tim.chatapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

// ── Auth ──────────────────────────────────────────────────────────────────────
data class CheckRequest(val code: String)
data class CheckResponse(val valid: Boolean)
data class RedeemRequest(val code: String, val device_id: String, val display_name: String)
data class RedeemResponse(val success: Boolean, val message: String, val token: String, val recipient_device_id: String)
data class RefreshResponse(val token: String, val expires_at: String)
data class PingRequest(val device_id: String)
data class OnlineResponse(val online: Boolean)

// ── PreKey Bundle ─────────────────────────────────────────────────────────────
data class OPKItem(val id: Int, val pub: String)
data class SPKData(val id: Int, val pub: String, val sig: String)
data class UploadPreKeysRequest(
    val device_id: String,
    val iks_pub: String,
    val ikd_pub: String,
    val spk: SPKData,
    val opks: List<OPKItem>,
)
data class UploadPreKeysResponse(val success: Boolean)
data class PreKeyBundleResponse(
    val device_id: String,
    val iks_pub: String,
    val ikd_pub: String,
    val spk: SPKData,
    val opk: OPKItem?,
)
data class ReplenishOPKsRequest(val device_id: String, val opks: List<OPKItem>)
data class OPKCountResponse(val count: Int)
data class RotateSPKRequest(val device_id: String, val spk: SPKData)

// ── Messages ──────────────────────────────────────────────────────────────────
data class X3DHHeader(
    val ikd_pub: String,
    val ek_pub: String,
    val opk_id: Int,
    val iks_pub: String = "",   // Sender's IKS pub für Safety Numbers
)
data class SendRequest(
    val sender_device_id: String,
    val recipient_device_id: String,
    val ciphertext: String,
    val nonce: String,
    val ratchet_pub: String,
    val message_index: Int,
    val prev_send_index: Int,
    val x3dh_header: X3DHHeader? = null,
    val group_id: String? = null,
    val message_type: String = "text",
)
data class SendResponse(val success: Boolean, val message_id: String)
data class MessageItem(
    val message_id: String,
    val sender_device_id: String,
    val ciphertext: String,
    val nonce: String,
    val ratchet_pub: String,
    val message_index: Int,
    val prev_send_index: Int,
    val created_at: String,
    val x3dh_header: X3DHHeader?,
    val group_id: String? = null,
    val message_type: String = "text",
)
data class PendingResponse(val messages: List<MessageItem>)
data class AckRequest(val device_id: String, val message_id: String)

// ── Groups ────────────────────────────────────────────────────────────────────
data class CreateGroupRequest(val name: String, val member_ids: List<String>)
data class CreateGroupResponse(val group_id: String)
data class GroupMemberResponse(val device_id: String, val display_name: String)
data class GroupInfoResponse(val group_id: String, val name: String, val admin_id: String, val members: List<GroupMemberResponse>)
data class GroupListResponse(val groups: List<GroupInfoResponse>)

// ── Contacts ──────────────────────────────────────────────────────────────────
data class CreateInviteResponse(val code: String, val expires_at: String)
data class AcceptInviteRequest(val code: String)
data class AcceptInviteResponse(val contact_device_id: String, val display_name: String)
data class ContactItem(val device_id: String, val display_name: String)
data class ContactListResponse(val contacts: List<ContactItem>)

// ── Files ─────────────────────────────────────────────────────────────────────
data class UploadFileResponse(val file_id: String)

fun ByteArray.toOctetStreamBody(): RequestBody =
    this.toRequestBody("application/octet-stream".toMediaType())

// ── Interface ─────────────────────────────────────────────────────────────────

interface ApiService {
    @POST("activation/check")
    suspend fun checkCode(@Body body: CheckRequest): Response<CheckResponse>

    @POST("activation/redeem")
    suspend fun redeemCode(@Body body: RedeemRequest): Response<RedeemResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<RefreshResponse>

    @POST("devices/prekeys")
    suspend fun uploadPreKeys(
        @Body body: UploadPreKeysRequest,
        @Header("x-device-token") token: String,
    ): Response<UploadPreKeysResponse>

    @GET("devices/{device_id}/prekey_bundle")
    suspend fun getPreKeyBundle(
        @Path("device_id") deviceId: String,
        @Header("x-device-id") myDeviceId: String,
        @Header("x-device-token") token: String,
    ): Response<PreKeyBundleResponse>

    @POST("devices/{device_id}/prekeys/replenish")
    suspend fun replenishOPKs(
        @Path("device_id") deviceId: String,
        @Body body: ReplenishOPKsRequest,
        @Header("x-device-token") token: String,
    ): Response<UploadPreKeysResponse>

    @GET("devices/{device_id}/opk_count")
    suspend fun getOPKCount(
        @Path("device_id") deviceId: String,
        @Header("x-device-id") myDeviceId: String,
        @Header("x-device-token") token: String,
    ): Response<OPKCountResponse>

    @POST("keys/spk_rotate")
    suspend fun rotateSPK(
        @Body body: RotateSPKRequest,
        @Header("x-device-token") token: String,
    ): Response<UploadPreKeysResponse>

    @POST("contacts/invite")
    suspend fun createInvite(
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<CreateInviteResponse>

    @POST("contacts/accept")
    suspend fun acceptInvite(
        @Body body: AcceptInviteRequest,
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<AcceptInviteResponse>

    @GET("contacts")
    suspend fun getContacts(
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<ContactListResponse>

    @POST("devices/ping")
    suspend fun ping(@Body body: PingRequest, @Header("x-device-token") token: String): Response<Unit>

    @GET("devices/online/{device_id}")
    suspend fun onlineStatus(
        @Path("device_id") deviceId: String,
        @Header("x-device-id") myDeviceId: String,
        @Header("x-device-token") token: String,
    ): Response<OnlineResponse>

    @POST("messages/send")
    suspend fun sendMessage(@Body body: SendRequest, @Header("x-device-token") token: String): Response<SendResponse>

    @GET("messages/pending/{device_id}")
    suspend fun pendingMessages(
        @Path("device_id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<PendingResponse>

    @POST("messages/ack")
    suspend fun ackMessage(@Body body: AckRequest, @Header("x-device-token") token: String): Response<Unit>

    @POST("files/upload")
    suspend fun uploadFile(
        @Body body: RequestBody,
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<UploadFileResponse>

    @Streaming
    @GET("files/{file_id}")
    suspend fun downloadFile(
        @Path("file_id") fileId: String,
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<ResponseBody>

    @POST("groups/create")
    suspend fun createGroup(
        @Body body: CreateGroupRequest,
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<CreateGroupResponse>

    @GET("groups")
    suspend fun getGroups(
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<GroupListResponse>

    @GET("groups/{group_id}")
    suspend fun getGroup(
        @retrofit2.http.Path("group_id") groupId: String,
        @Header("x-device-id") deviceId: String,
        @Header("x-device-token") token: String,
    ): Response<GroupInfoResponse>
}

object Api {
    private val BASE_URL = if (Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator") || Build.MODEL.contains("Android SDK"))
        "http://10.0.2.2:8001/"
    else
        "http://192.168.0.138:8001/"

    val service: ApiService = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
                .build()
        )
        .build()
        .create(ApiService::class.java)
}
