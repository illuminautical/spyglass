package io.streamcord.spyglass

import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class TwitchClient private constructor(
    val clientID: ClientID, private val clientSecret: ClientSecret, private val callbackUri: String,
    private var accessTokenInfo: ResponseBody.AppAccessToken
) {
    private var awaitingToken: CompletableDeferred<Unit>? = null

    tailrec suspend fun fetchExistingSubscriptions(): List<SubscriptionData> {
        awaitingToken?.await()

        val response = httpClient.get<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        return when {
            response.status == HttpStatusCode.Unauthorized -> {
                refetchToken()
                fetchExistingSubscriptions()
            }
            !response.status.isSuccess() -> {
                logger.error("Failed to fetch subscriptions from Twitch. Error ${response.status}. Attempting refetch in 30 seconds")
                delay(30)
                fetchExistingSubscriptions()
            }
            else -> Json.safeDecodeFromString<ResponseBody.GetSubs>(response.readText()).data
        }
    }

    suspend fun createSubscription(userID: Long, type: String, secret: String): SubscriptionData? {
        awaitingToken?.await()

        val condition = RequestBody.CreateSub.Condition(userID.toString())
        val transport = RequestBody.CreateSub.Transport("webhook", "https://$callbackUri/webhooks/callback", secret)

        val response = httpClient.post<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            contentType(ContentType.Application.Json)
            body = Json.encodeToString(RequestBody.CreateSub(type, "1", condition, transport))
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        return when {
            response.status == HttpStatusCode.Unauthorized -> {
                refetchToken()
                createSubscription(userID, type, secret)
            }
            !response.status.isSuccess() -> {
                logger.error("Failed to create subscription for user ID $userID with type $type. ${response.readText()}")
                null
            }
            else -> Json.safeDecodeFromString<ResponseBody.CreateSub>(response.readText()).data.first()
        }
    }

    suspend fun removeSubscription(subID: String): Boolean {
        awaitingToken?.await()

        val response = httpClient.delete<HttpResponse>("https://api.twitch.tv/helix/eventsub/subscriptions") {
            withDefaults()
            parameter("id", subID)
        }

        // if unauthorized, get a new access token and store it, then rerun the request
        return when {
            response.status == HttpStatusCode.Unauthorized -> {
                refetchToken()
                removeSubscription(subID)
            }
            !response.status.isSuccess() -> {
                logger.error("Failed to delete subscription with ID $subID. Error ${response.readText()}")
                false
            }
            else -> true
        }
    }

    private fun HttpRequestBuilder.withDefaults() {
        expectSuccess = false
        header("Authorization", "Bearer ${accessTokenInfo.access_token}")
        header("Client-ID", clientID.value)
    }

    private suspend fun refetchToken() {
        // if we're already awaiting a token, return immediately to rerun the request
        if (awaitingToken != null) {
            return
        }

        logger.warn("Encountered 401 Unauthorized from Twitch, fetching new access token...")
        awaitingToken = CompletableDeferred()
        accessTokenInfo = httpClient.fetchAccessToken(clientID, clientSecret)
        awaitingToken?.complete(Unit)
        awaitingToken = null
    }

    companion object {
        private val httpClient: HttpClient by lazy { HttpClient(Java) }

        suspend fun create(id: ClientID, secret: ClientSecret, callbackUri: String): Pair<TwitchClient, Long> {
            val accessTokenInfo: ResponseBody.AppAccessToken = httpClient.fetchAccessToken(id, secret)
            return TwitchClient(id, secret, callbackUri, accessTokenInfo) to accessTokenInfo.expires_in
        }

        private suspend fun HttpClient.fetchAccessToken(
            id: ClientID,
            secret: ClientSecret
        ): ResponseBody.AppAccessToken {
            val response = post<HttpResponse>("https://id.twitch.tv/oauth2/token") {
                expectSuccess = false
                parameter("client_id", id.value)
                parameter("client_secret", secret.value)
                parameter("grant_type", "client_credentials")
            }

            if (!response.status.isSuccess()) {
                logger.fatal(
                    ExitCodes.NO_TWITCH_ACCESS_TOKEN,
                    "Failed to fetch access token from Twitch. Error ${response.readText()}"
                )
            }

            return Json.safeDecodeFromString(response.readText())
        }
    }
}

inline class ClientSecret(val value: String)
inline class ClientID(val value: String)

object ResponseBody {
    @Serializable
    data class AppAccessToken(
        val access_token: String,
        val expires_in: Long,
        val token_type: String,
        val refresh_token: String? = null,
        val scope: List<String> = emptyList()
    )

    @Serializable
    data class GetSubs(
        val total: Long, val data: List<SubscriptionData>,
        val limit: Long, val max_total_cost: Long, val total_cost: Long,
        val pagination: JsonObject
    )

    @Serializable
    data class CreateSub(
        val data: List<SubscriptionData>,
        val limit: Long,
        val total: Long,
        val max_total_cost: Long,
        val total_cost: Long
    )
}

private object RequestBody {
    @Serializable
    data class CreateSub(val type: String, val version: String, val condition: Condition, val transport: Transport) {
        @Serializable
        data class Condition(val broadcaster_user_id: String)

        @Serializable
        data class Transport(val method: String, val callback: String, val secret: String)
    }
}

@Serializable
data class SubscriptionData(
    val id: String, val status: String, val type: String, val version: String,
    val condition: Condition,
    val created_at: String,
    val transport: Transport,
    val cost: Int? = null
) {
    @Serializable
    data class Condition(val broadcaster_user_id: Long)

    @Serializable
    data class Transport(val method: String, val callback: String)
}
