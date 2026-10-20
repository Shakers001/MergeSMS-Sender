// MergeSMS Sender - based on android-masssmssender
// Copyright (c) 2025 Jacob "Isreal" Eiler and Isreal Consulting, LLC.
// Modifications Copyright (c) 2026 MergeSMS Sender contributors.
// Licensed under the GNU Affero General Public License v3 or later.

package com.mergesms.sender

import android.os.Handler
import android.os.Looper
import java.util.Locale

object Limits {
    const val MAX_RECIPIENTS = 1000
    const val MAX_CHARS = 500
}

data class Recipient(
    val name: String,
    val phone: String,
    val fields: Map<String, String> = emptyMap()
)

class Item(val name: String, val phone: String, val segments: Int) {
    @Volatile var status: Int = PENDING
    @Volatile var parts: Int = 0
    @Volatile var partsDone: Int = 0
    @Volatile var error: String? = null

    fun label(): String = when (status) {
        SENT -> "Sent"
        FAILED -> "Failed"
        SENDING -> "Unconfirmed"
        else -> "Not sent"
    }

    companion object {
        const val PENDING = 0
        const val SENDING = 1
        const val SENT = 2
        const val FAILED = 3
    }
}

class Batch(val id: Long, val template: String, val items: List<Item>) {
    @Volatile var state: Int = RUNNING

    val sent: Int get() = items.count { it.status == Item.SENT }
    val failed: Int get() = items.count { it.status == Item.FAILED }
    val totalSegments: Int get() = items.sumOf { it.segments }

    fun stateLabel(): String = when (state) {
        RUNNING -> "Sending"
        DONE -> "Completed"
        CANCELLED -> "Cancelled"
        else -> "Interrupted"
    }

    companion object {
        const val RUNNING = 0
        const val DONE = 1
        const val CANCELLED = 2
        const val INTERRUPTED = 3
    }
}

/** Shared state between the UI and the foreground sending service. */
object SendState {
    @Volatile var current: Batch? = null
    @Volatile var texts: List<String> = emptyList()
    @Volatile var running: Boolean = false
    @Volatile var cancelRequested: Boolean = false
    @Volatile var listener: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    fun begin(batch: Batch, messages: List<String>) {
        current = batch
        texts = messages
        cancelRequested = false
        running = true
    }

    fun notifyChanged() {
        main.post { listener?.invoke() }
    }
}

object PhoneUtils {
    private val CLEAN = Regex("[\\s\\-().]")

    /** Returns a normalized number (optional leading +, 7-15 digits) or null if invalid. */
    fun normalize(raw: String): String? {
        var s = raw.trim().replace(CLEAN, "")
        if (s.startsWith("00")) s = "+" + s.substring(2)
        if (!Regex("^\\+?\\d+$").matches(s)) return null
        val digits = s.count { it.isDigit() }
        return if (digits in 7..15) s else null
    }

    /** Duplicate key: last 10 digits, so "+1 555..." and "555..." collapse together. */
    fun key(normalized: String): String {
        val d = normalized.filter { it.isDigit() }
        return if (d.length >= 10) d.takeLast(10) else d
    }
}

class RecipientSet {
    enum class Outcome { ADDED, INVALID, DUPLICATE, OVER_LIMIT }

    private val map = LinkedHashMap<String, Recipient>()
    var invalid = 0
        private set
    var duplicates = 0
        private set
    var overLimit = 0
        private set

    val size: Int get() = map.size

    fun list(): List<Recipient> = map.values.toList()

    fun columns(): Set<String> = map.values.flatMap { it.fields.keys }.toSet()

    fun clear() {
        map.clear()
        invalid = 0
        duplicates = 0
        overLimit = 0
    }

    fun add(name: String, rawPhone: String, fields: Map<String, String>): Outcome {
        val phone = PhoneUtils.normalize(rawPhone)
        if (phone == null) {
            invalid++
            return Outcome.INVALID
        }
        val key = PhoneUtils.key(phone)
        if (map.containsKey(key)) {
            duplicates++
            return Outcome.DUPLICATE
        }
        if (map.size >= Limits.MAX_RECIPIENTS) {
            overLimit++
            return Outcome.OVER_LIMIT
        }
        map[key] = Recipient(name.trim(), phone, fields)
        return Outcome.ADDED
    }
}

object MessageTemplate {
    private val TOKEN = Regex("""\{\s*([^{}|]+?)\s*(?:\|([^{}]*))?\}""")
    private val BUILTIN = setOf("name", "phone", "number", "first name", "firstname", "first_name")

    private fun key(k: String) = k.trim().lowercase(Locale.ROOT)

    /** null = unknown field; "" = known but empty for this recipient. */
    private fun lookup(r: Recipient, rawKey: String): String? {
        val k = key(rawKey)
        if (r.fields.containsKey(k)) return r.fields.getValue(k)
        return when (k) {
            "name" -> r.name
            "phone", "number" -> r.phone
            "first name", "firstname", "first_name" ->
                r.name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            else -> null
        }
    }

    /** Fields used in the template that match neither a built-in nor a CSV column. */
    fun unknownFields(template: String, columns: Set<String>): List<String> =
        TOKEN.findAll(template)
            .map { it.groupValues[1].trim() }
            .distinct()
            .filter { key(it) !in BUILTIN && key(it) !in columns }
            .toList()

    /** {Name} -> value; {Name|there} -> "there" when the value is empty. */
    fun render(template: String, r: Recipient): String =
        TOKEN.replace(template) { m ->
            val value = lookup(r, m.groupValues[1])
            when {
                value == null -> m.value
                value.isBlank() -> m.groupValues[2].trim()
                else -> value.trim()
            }
        }.trim()

    fun segments(text: String): Int = try {
        android.telephony.SmsMessage.calculateLength(text, false)[0].coerceAtLeast(1)
    } catch (e: Exception) {
        maxOf(1, (text.length + 152) / 153)
    }
}
