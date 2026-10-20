// MergeSMS Sender - based on android-masssmssender
// Copyright (c) 2025 Jacob "Isreal" Eiler and Isreal Consulting, LLC.
// Modifications Copyright (c) 2026 MergeSMS Sender contributors.
// Licensed under the GNU Affero General Public License v3 or later.

package com.mergesms.sender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "history").apply { mkdirs() }

    @Synchronized
    fun save(b: Batch) {
        val arr = JSONArray()
        for (i in b.items) {
            arr.put(
                JSONObject()
                    .put("n", i.name)
                    .put("p", i.phone)
                    .put("g", i.segments)
                    .put("s", i.status)
                    .put("e", i.error ?: "")
            )
        }
        val o = JSONObject()
            .put("id", b.id)
            .put("t", b.template)
            .put("st", b.state)
            .put("items", arr)
        val tmp = File(dir, "${b.id}.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "${b.id}.json"))
        prune()
    }

    @Synchronized
    fun loadAll(): List<Batch> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { parse(it) }.sortedByDescending { it.id }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun prune() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.name } ?: return
        files.drop(50).forEach { it.delete() }
    }

    private fun parse(f: File): Batch? = try {
        val o = JSONObject(f.readText())
        val arr = o.getJSONArray("items")
        val items = ArrayList<Item>(arr.length())
        for (k in 0 until arr.length()) {
            val j = arr.getJSONObject(k)
            val item = Item(j.getString("n"), j.getString("p"), j.optInt("g", 1))
            item.status = j.getInt("s")
            item.error = j.optString("e").ifEmpty { null }
            items.add(item)
        }
        val id = o.getLong("id")
        val batch = Batch(id, o.getString("t"), items)
        var st = o.getInt("st")
        if (st == Batch.RUNNING && SendState.current?.id != id) st = Batch.INTERRUPTED
        batch.state = st
        batch
    } catch (e: Exception) {
        null
    }
}
