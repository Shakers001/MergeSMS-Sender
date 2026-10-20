// MergeSMS Sender - based on android-masssmssender
// Copyright (c) 2025 Jacob "Isreal" Eiler and Isreal Consulting, LLC.
// Modifications Copyright (c) 2026 MergeSMS Sender contributors.
// Licensed under the GNU Affero General Public License v3 or later.

package com.mergesms.sender

import java.util.Locale

object CsvImporter {
    class Summary(
        val rows: Int,
        val added: Int,
        val duplicates: Int,
        val invalid: Int,
        val overLimit: Int
    )

    private val PHONE_HDR = Regex("phone|mobile|cell|tel|number|msisdn")
    private val NAME_HDR = Regex("name|contact|customer")

    fun parseRows(text: String): List<List<String>> {
        val src = text.removePrefix("\uFEFF")
        val firstLine = src.lineSequence().firstOrNull { it.isNotBlank() } ?: return emptyList()
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } } ?: ','

        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false

        fun endField() {
            row.add(sb.toString().trim())
            sb.setLength(0)
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotEmpty() }) rows.add(row)
            row = ArrayList()
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length && src[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else if (c == '"') {
                inQuotes = true
            } else if (c == delim) {
                endField()
            } else if (c == '\n') {
                endRow()
            } else if (c != '\r') {
                sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    private fun guessPhoneCol(data: List<List<String>>): Int {
        val sample = data.take(20)
        val cols = sample.maxOfOrNull { it.size } ?: return -1
        var best = -1
        var bestCount = 0
        for (c in 0 until cols) {
            val n = sample.count { PhoneUtils.normalize(it.getOrNull(c).orEmpty()) != null }
            if (n > bestCount) {
                best = c
                bestCount = n
            }
        }
        return best
    }

    /**
     * Imports rows into [set]. Supports files with or without a header row.
     * With a header, every column becomes a merge field, e.g. {Name}, {Company}.
     */
    fun importInto(set: RecipientSet, text: String): Summary {
        val rows = parseRows(text)
        if (rows.isEmpty()) return Summary(0, 0, 0, 0, 0)

        val size0 = set.size
        val dup0 = set.duplicates
        val inv0 = set.invalid
        val over0 = set.overLimit

        val first = rows[0]
        val hasHeader = first.none { PhoneUtils.normalize(it) != null }
        val headers = if (hasHeader) first.map { it.lowercase(Locale.ROOT).trim() } else emptyList()
        val data = if (hasHeader) rows.drop(1) else rows

        var phoneCol = -1
        var nameCol = -1
        if (hasHeader) {
            phoneCol = headers.indexOfFirst { PHONE_HDR.containsMatchIn(it) }
            nameCol = headers.indexOfFirst { it == "name" || it == "full name" || it == "fullname" }
            if (nameCol < 0) nameCol = headers.indexOfFirst { NAME_HDR.containsMatchIn(it) }
        }
        if (phoneCol < 0) phoneCol = guessPhoneCol(data)
        if (phoneCol < 0) phoneCol = 0
        if (!hasHeader) {
            val maxCols = data.maxOfOrNull { it.size } ?: 1
            nameCol = (0 until maxCols).firstOrNull { it != phoneCol } ?: -1
        }
        if (nameCol == phoneCol) nameCol = -1

        for (r in data) {
            val phone = r.getOrNull(phoneCol).orEmpty()
            val name = if (nameCol >= 0) r.getOrNull(nameCol).orEmpty() else ""
            val fields = HashMap<String, String>()
            if (hasHeader) {
                headers.forEachIndexed { idx, h ->
                    if (h.isNotEmpty()) fields[h] = r.getOrNull(idx).orEmpty()
                }
            }
            set.add(name, phone, fields)
        }

        return Summary(
            rows = data.size,
            added = set.size - size0,
            duplicates = set.duplicates - dup0,
            invalid = set.invalid - inv0,
            overLimit = set.overLimit - over0
        )
    }
}
