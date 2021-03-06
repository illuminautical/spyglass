package io.streamcord.spyglass

import com.mongodb.client.MongoCollection
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.util.pipeline.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import org.bson.Document
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.system.exitProcess

class TwitchServer(private val subscriptions: MongoCollection<Document>, private val sender: Sender) {
    private val httpServer = embeddedServer(CIO, port = 8080) {
        routing {
            post("webhooks/callback") {
                logger.trace("Request received")
                handleCallback(call.receiveText())
            }
        }
    }

    fun start() {
        httpServer.start(wait = false)
    }

    private suspend fun PipelineContext<Unit, ApplicationCall>.handleCallback(text: String) {
        val messageID = call.request.header("Twitch-Eventsub-Message-Id") ?: run {
            call.respond(HttpStatusCode.NotFound)
            return
        }

        when (call.request.header("Twitch-Eventsub-Message-Type")) {
            "webhook_callback_verification" -> {
                val verificationBody = Json.safeDecodeFromString<CallbackVerificationBody>(text)

                if (verifyRequest(subscriptions, verificationBody.subscription.id, text)) {
                    val timestamp = call.request.header("Twitch-Eventsub-Message-Timestamp") ?: nowInUtc()
                    subscriptions.verifySubscription(verificationBody.subscription.id, timestamp)?.let {
                        subscriptions.updateSubscription(verificationBody.subscription.id, messageID)
                        call.respond(HttpStatusCode.Accepted, verificationBody.challenge)
                        logger.info("Verified subscription with ID ${verificationBody.subscription.id}")
                    } ?: run {
                        // we don't have that subscription in the DB, so we can't verify it
                        call.respond(HttpStatusCode.NotFound)
                    }
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
            "notification" -> {
                val notification: TwitchNotification = Json.safeDecodeFromString(text)

                if (verifyRequest(subscriptions, notification.subscription.id, text)) {
                    logger.trace("Request verified, handling notification")
                    handleNotification(sender, notification)
                    subscriptions.updateSubscription(notification.subscription.id, messageID)
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
            "revocation" -> {
                val subscription = Json.safeDecodeFromString<RevocationBody>(text).subscription

                if (verifyRequest(subscriptions, subscription.id, text)) {
                    logger.warn("Received revocation request from Twitch for subscription ID ${subscription.id}. Reason: ${subscription.status}")
                    val timestamp = call.request.header("Twitch-Eventsub-Message-Timestamp") ?: nowInUtc()
                    subscriptions.revokeSubscription(subscription.id, timestamp, subscription.status)
                    subscriptions.updateSubscription(subscription.id, messageID)
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.Unauthorized)
                }
            }
        }
    }

    companion object {
        fun create(subsCollection: MongoCollection<Document>, aqmpConfig: AppConfig.Amqp): TwitchServer {
            val sender = Sender.Amqp.create(aqmpConfig).getOrElse {
                exitProcess(ExitCodes.AMQP_CONNECTION_FAILED)
            }

            return TwitchServer(subsCollection, sender)
        }

        private fun nowInUtc() = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
    }
}

private fun PipelineContext<Unit, ApplicationCall>.verifyRequest(
    subsCollection: MongoCollection<Document>,
    subID: String,
    text: String
): Boolean {
    val subscription = subsCollection.find(Document("sub_id", subID)).firstOrNull() ?: run {
        logger.warn("Request failed verification: no sub ID $subID in database")
        return false
    }

    val expectedSecret = subscription.getString("secret")

    val hmacMessage = call.request.headers.let {
        it["Twitch-Eventsub-Message-Id"] + it["Twitch-Eventsub-Message-Timestamp"] + text
    }

    val secretKey = SecretKeySpec(expectedSecret.encodeToByteArray(), "HmacSHA256")
    val hMacSHA256 = Mac.getInstance("HmacSHA256").apply { init(secretKey) }
    val expectedSignatureHeader =
        "sha256=" + hMacSHA256.doFinal(hmacMessage.encodeToByteArray()).toHexString()
    val actualSignatureHeader = call.request.header("Twitch-Eventsub-Message-Signature")

    return if (expectedSignatureHeader != actualSignatureHeader) {
        logger.warn("Received request to webhooks/callback that failed verification")
        logger.warn("Expected signature header: $expectedSignatureHeader")
        logger.warn("Actual signature header:   $actualSignatureHeader")
        false
    } else true
}

private fun PipelineContext<Unit, ApplicationCall>.handleNotification(
    sender: Sender,
    notification: TwitchNotification
) {
    when (val subType = call.request.header("Twitch-Eventsub-Subscription-Type")) {
        "stream.online" -> {
            val eventData = Json.decodeFromJsonElement<TwitchEvent.StreamOnline>(notification.event)

            if (eventData.type == "live") {
                sender.sendOnlineEvent(eventData.id, eventData.broadcaster_user_id, eventData.broadcaster_user_name)
            }
        }
        "stream.offline" -> {
            val eventData = Json.decodeFromJsonElement<TwitchEvent.StreamOffline>(notification.event)
            sender.sendOfflineEvent(eventData.broadcaster_user_id, eventData.broadcaster_user_name)
        }
        else -> logger.warn("Received notification for subscription type $subType, ignoring")
    }
}

@Serializable
private data class TwitchNotification(val subscription: SubscriptionData, val event: JsonElement)

private interface TwitchEvent {
    @Serializable
    data class StreamOnline(
        val id: String,
        val broadcaster_user_id: String,
        val broadcaster_user_login: String,
        val broadcaster_user_name: String,
        val type: String,
        val started_at: String
    ) : TwitchEvent

    @Serializable
    data class StreamOffline(
        val broadcaster_user_id: String,
        val broadcaster_user_login: String,
        val broadcaster_user_name: String
    )
}

@Serializable
class CallbackVerificationBody(val challenge: String, val subscription: SubscriptionData)

@Serializable
class RevocationBody(val subscription: SubscriptionData)

@OptIn(ExperimentalUnsignedTypes::class) // just to make it clear that the experimental unsigned types are used
private fun ByteArray.toHexString() = asUByteArray().joinToString("") { it.toString(16).padStart(2, '0') }
