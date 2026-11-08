/*
 * Copyright (c) 2026 Adonis Open Contributors.
 *
 * This file is part of Adonis Open (fork of linhome-android).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linhome.webhooks

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a call leg ended, as `call_ended` and `call_summary` say it (`outcome`). */
enum class CallOutcome(val wireName: String) {
    /** The person answered and talked. */
    ANSWERED("answered"),

    /** The call connected silently only (video preview / door action before answering), never answered. */
    PREVIEWED("previewed"),

    /** The person declined it on this phone. */
    DECLINED("declined"),

    /** It rang and nobody acted on this phone before the caller gave up. */
    MISSED("missed"),
    ANSWERED_ELSEWHERE("answered_elsewhere"),
    DECLINED_ELSEWHERE("declined_elsewhere"),

    /** Refused without alerting: the receive-calls switch was off. */
    REFUSED_PAUSED("refused_paused"),

    /** Refused without alerting: this phone was already in a call. */
    REFUSED_BUSY("refused_busy"),

    /** A SIP or media error. */
    FAILED("failed")
}

/**
 * Narrates each incoming call as the webhooks of the contract (Adonis
 * `docs/features/adonis-open-webhooks.md`): the step events right when they happen (`call_ringing`,
 * `call_preview`, `door_action`, `call_answered`, `call_declined`, `call_ended`) and one `call_summary`
 * with every step when the call is over. Which of them leave the phone is the site's choice
 * ([send] drops what it didn't list); this class always narrates.
 *
 * Plain Kotlin: the app feeds it from Linphone's call states (CoreContext), tests feed it directly.
 * Every method must be called from the same thread.
 */
class CallReporter(
    private val send: (event: String, fields: JSONObject, at: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private class Step(val tMs: Long, val what: String, val extra: JSONObject?)

    private class Record(
        val callId: String,
        val door: String?,
        val from: String?,
        val startedMs: Long,
        val gsmCallActive: Boolean
    ) {
        val steps = mutableListOf<Step>()
        var refused: CallOutcome? = null
        var answeredMs: Long? = null
        var previewed = false
    }

    private val records = LinkedHashMap<String, Record>()

    private fun iso(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun seconds(ms: Long): Double = Math.round(ms / 100.0) / 10.0

    private fun base(record: Record): JSONObject = JSONObject()
        .put("call_id", record.callId)
        .put("door", record.door ?: JSONObject.NULL)
        .put("from", record.from ?: JSONObject.NULL)

    private fun step(record: Record, what: String, event: String, extra: JSONObject? = null) {
        val at = nowMs()
        record.steps += Step(at - record.startedMs, what, extra)
        val fields = base(record)
        extra?.keys()?.forEach { fields.put(it, extra.get(it)) }
        send(event, fields, iso(at))
    }

    /** Whether [callId] is being narrated (an incoming call seen by [onIncoming]). */
    fun isTracking(callId: String): Boolean = records.containsKey(callId)

    /**
     * An incoming call reached the phone. [refused] is set when the app declines it without alerting
     * ([CallOutcome.REFUSED_PAUSED] or [CallOutcome.REFUSED_BUSY]); otherwise it rings.
     */
    fun onIncoming(callId: String, door: String?, from: String?, refused: CallOutcome? = null, gsmCallActive: Boolean = false) {
        if (records.containsKey(callId)) return
        val record = Record(callId, door, from, nowMs(), gsmCallActive)
        records[callId] = record
        record.refused = refused
        val extra = JSONObject()
        when (refused) {
            CallOutcome.REFUSED_PAUSED -> extra.put("refused", "paused")
            CallOutcome.REFUSED_BUSY -> extra.put("refused", "busy")
            else -> {}
        }
        if (gsmCallActive) extra.put("gsm_call_active", true)
        step(record, "ringing", WebhookEvents.CALL_RINGING, extra.takeIf { it.length() > 0 })
    }

    /** The person opened the call silently (video, no audio), typically to act before answering. */
    fun onPreview(callId: String) {
        val record = records[callId] ?: return
        if (record.previewed) return
        record.previewed = true
        step(record, "preview", WebhookEvents.CALL_PREVIEW)
    }

    /**
     * An action button: [action] its type, [code] the DTMF, [early] true before the call was answered.
     * Reported whatever the door does with the DTMF: the site may act on it itself (turn on a light).
     */
    fun onDoorAction(callId: String?, action: String?, code: String?, early: Boolean, sent: Boolean) {
        val extra = JSONObject()
            .put("action", action ?: JSONObject.NULL)
            .put("code", code ?: JSONObject.NULL)
            .put("phase", if (early) "early" else "in_call")
            .put("sent", sent)
        val record = callId?.let { records[it] }
        if (record == null) {
            val fields = JSONObject().put("call_id", callId ?: JSONObject.NULL)
            extra.keys().forEach { fields.put(it, extra.get(it)) }
            send(WebhookEvents.DOOR_ACTION, fields, iso(nowMs()))
            return
        }
        step(record, "door_action", WebhookEvents.DOOR_ACTION, extra)
    }

    /** The person answered (not a silent preview). */
    fun onAnswered(callId: String) {
        val record = records[callId] ?: return
        if (record.answeredMs != null) return
        record.answeredMs = nowMs()
        step(record, "answered", WebhookEvents.CALL_ANSWERED)
    }

    /** The person declined it on this phone. */
    fun onDeclined(callId: String) {
        val record = records[callId] ?: return
        step(record, "declined", WebhookEvents.CALL_DECLINED)
    }

    /**
     * The call is over. [outcome] from Linphone's call log (a refusal recorded by [onIncoming] wins);
     * [byLocal] true when this phone hung up, false when the other side did, null when unknown;
     * [answeredBy] the name the site gave for a call answered elsewhere.
     */
    fun onEnded(callId: String, outcome: CallOutcome, byLocal: Boolean?, answeredBy: String? = null) {
        val record = records.remove(callId) ?: return
        val endedMs = nowMs()
        val finalOutcome = record.refused ?: when {
            outcome == CallOutcome.ANSWERED && record.answeredMs == null && record.previewed -> CallOutcome.PREVIEWED
            else -> outcome
        }
        val endExtra = JSONObject().put("outcome", finalOutcome.wireName)
        byLocal?.let { endExtra.put("by", if (it) "local" else "remote") }
        answeredBy?.let { endExtra.put("answered_by", it) }
        step(record, "ended", WebhookEvents.CALL_ENDED, endExtra)

        val summary = base(record)
            .put("started_at", iso(record.startedMs))
            .put("ended_at", iso(endedMs))
            .put("outcome", finalOutcome.wireName)
            .put("ring_seconds", seconds((record.answeredMs ?: endedMs) - record.startedMs))
            .put("talk_seconds", record.answeredMs?.let { seconds(endedMs - it) } ?: JSONObject.NULL)
        answeredBy?.let { summary.put("answered_by", it) }
        if (record.gsmCallActive) summary.put("gsm_call_active", true)
        val steps = JSONArray()
        record.steps.forEach { s ->
            val json = JSONObject().put("t", seconds(s.tMs)).put("what", s.what)
            s.extra?.keys()?.forEach { json.put(it, s.extra.get(it)) }
            steps.put(json)
        }
        summary.put("steps", steps)
        send(WebhookEvents.CALL_SUMMARY, summary, iso(endedMs))
    }
}

/**
 * Turns SIP registration changes into `registration` events: `failed` once when it starts failing (with
 * the reason), `ok` once when it recovers. Steady states and repeats send nothing.
 */
class RegistrationReporter(
    private val send: (event: String, fields: JSONObject, at: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private var failingSinceMs: Long? = null

    private fun iso(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun onFailed(reason: String?) {
        if (failingSinceMs != null) return
        val now = nowMs()
        failingSinceMs = now
        send(
            WebhookEvents.REGISTRATION,
            JSONObject().put("state", "failed").put("reason", reason ?: JSONObject.NULL).put("since", iso(now)),
            iso(now)
        )
    }

    fun onOk() {
        val since = failingSinceMs ?: return
        failingSinceMs = null
        val now = nowMs()
        send(
            WebhookEvents.REGISTRATION,
            JSONObject().put("state", "ok").put("failed_since", iso(since)),
            iso(now)
        )
    }
}
