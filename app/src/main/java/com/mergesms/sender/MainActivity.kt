// MergeSMS Sender - based on android-masssmssender
// Copyright (c) 2025 Jacob "Isreal" Eiler and Isreal Consulting, LLC.
// Modifications Copyright (c) 2026 MergeSMS Sender contributors.
// Licensed under the GNU Affero General Public License v3 or later.

package com.mergesms.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.mergesms.sender.databinding.ActivityMainBinding
import com.mergesms.sender.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryVH(val vb: ItemHistoryBinding) : RecyclerView.ViewHolder(vb.root)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_CONTACTS = 100
        private const val REQ_SEND_SMS = 102
        private const val REQ_NOTIFICATIONS = 103
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var history: HistoryStore
    private val recipients = RecipientSet()
    private var lastImportInfo = ""
    private var pendingExport: Batch? = null
    private var currentTab = 0
    private val adapter = HistoryAdapter()
    private val dateFmt = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

    private val csvLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) importCsv(uri)
        }

    private val contactLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            if (result.resultCode == RESULT_OK && uri != null) loadContactFromUri(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val batch = pendingExport
            pendingExport = null
            if (uri != null && batch != null) writeReport(uri, batch)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        history = HistoryStore(this)

        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reset -> { resetAll(); true }
                R.id.action_exit -> { finishAffinity(); true }
                else -> false
            }
        }

        for (title in listOf("Compose", "Send", "History")) {
            b.tabs.addTab(b.tabs.newTab().setText(title))
        }
        b.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showScreen(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        b.listHistory.layoutManager = LinearLayoutManager(this)
        b.listHistory.adapter = adapter

        b.btnImportCsv.setOnClickListener { openCsvPicker() }
        b.btnPickContact.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS
                )
            } else {
                openContactPicker()
            }
        }
        b.btnInsertName.setOnClickListener {
            val et = b.inputMessage
            val pos = et.selectionStart.coerceAtLeast(0)
            et.text?.insert(pos, "{Name}")
        }
        b.inputMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateMessageInfo()
        })
        b.btnPreview.setOnClickListener { onPreviewClicked() }
        b.btnCancelSend.setOnClickListener { SendState.cancelRequested = true }
        b.btnExportCurrent.setOnClickListener {
            SendState.current?.let { startExport(it) }
        }
        b.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear history?")
                .setMessage("This deletes all saved batches and reports from this device.")
                .setPositiveButton("Clear") { _, _ ->
                    history.clear()
                    refreshHistory()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        updateRecipientViews()
        showScreen(0)
    }

    override fun onStart() {
        super.onStart()
        SendState.listener = {
            refreshSend()
            if (currentTab == 2) refreshHistory()
        }
        refreshSend()
    }

    override fun onStop() {
        SendState.listener = null
        super.onStop()
    }

    // ---------------------------------------------------------------- screens

    private fun showScreen(pos: Int) {
        currentTab = pos
        b.screenCompose.visibility = if (pos == 0) View.VISIBLE else View.GONE
        b.screenSend.visibility = if (pos == 1) View.VISIBLE else View.GONE
        b.screenHistory.visibility = if (pos == 2) View.VISIBLE else View.GONE
        if (pos == 1) refreshSend()
        if (pos == 2) refreshHistory()
    }

    private fun resetAll() {
        recipients.clear()
        lastImportInfo = ""
        b.inputMessage.setText("")
        updateRecipientViews()
        toast("App reset")
    }

    private fun updateRecipientViews() {
        b.txtRecipients.text = if (recipients.size == 0) {
            "No recipients yet. Import a CSV or pick a contact."
        } else {
            "${recipients.size} recipients ready (max ${Limits.MAX_RECIPIENTS})"
        }
        b.txtImportInfo.text = lastImportInfo
        updateMessageInfo()
    }

    private fun updateMessageInfo() {
        val t = b.inputMessage.text?.toString().orEmpty()
        if (t.isEmpty()) {
            b.txtSegments.text = "Tip: {Name}, {First Name} or any CSV column. Use {Name|there} for a fallback."
            return
        }
        val sample = recipients.list().firstOrNull()?.let { MessageTemplate.render(t, it) } ?: t
        val seg = MessageTemplate.segments(sample)
        b.txtSegments.text = "About $seg SMS segment(s) per recipient (sample: ${sample.length} chars)"
    }

    // ------------------------------------------------------------- recipients

    private fun openCsvPicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")
            )
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        csvLauncher.launch(intent)
    }

    private fun importCsv(uri: Uri) {
        Thread {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("Empty file")
                runOnUiThread {
                    val s = CsvImporter.importInto(recipients, text)
                    lastImportInfo = "Last import: ${s.rows} rows, ${s.added} added, " +
                        "${s.duplicates} duplicates removed, ${s.invalid} invalid" +
                        if (s.overLimit > 0) ", ${s.overLimit} skipped (limit ${Limits.MAX_RECIPIENTS})" else ""
                    updateRecipientViews()
                    toast("Imported ${s.added} phone numbers from CSV.")
                }
            } catch (e: Exception) {
                toastOnUiThread("Failed to import CSV")
            }
        }.start()
    }

    private fun openContactPicker() {
        contactLauncher.launch(
            Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        )
    }

    private fun loadContactFromUri(contactUri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        try {
            contentResolver.query(contactUri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)).orEmpty()
                    val number = c.getString(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)).orEmpty()
                    val type = c.getInt(c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                    if (type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE ||
                        type == ContactsContract.CommonDataKinds.Phone.TYPE_WORK_MOBILE ||
                        type == ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
                    ) {
                        when (recipients.add(name, number, emptyMap())) {
                            RecipientSet.Outcome.ADDED -> toast("Added $name")
                            RecipientSet.Outcome.DUPLICATE -> toast("$name is already in the list")
                            RecipientSet.Outcome.INVALID -> toast("Invalid phone number")
                            RecipientSet.Outcome.OVER_LIMIT -> toast("Limit of ${Limits.MAX_RECIPIENTS} reached")
                        }
                        updateRecipientViews()
                    } else {
                        toast("Selected contact is not a mobile number.")
                    }
                }
            }
        } catch (e: Exception) {
            toast("Failed to load contact")
        }
    }

    // ---------------------------------------------------------- preview & send

    private fun hasSmsPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun alert(title: String, msg: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg)
            .setPositiveButton("OK", null).show()
    }

    private fun onPreviewClicked() {
        val template = b.inputMessage.text?.toString().orEmpty().trim()
        if (template.isEmpty()) { toast("Please enter a message before sending."); return }
        if (recipients.size == 0) { toast("No recipients yet."); return }
        if (SendState.running) { toast("A batch is already sending."); return }
        if (!hasSmsPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), REQ_SEND_SMS)
            return
        }

        val unknown = MessageTemplate.unknownFields(template, recipients.columns())
        if (unknown.isNotEmpty()) {
            alert(
                "Unknown field",
                "Not found in your CSV headers: " + unknown.joinToString { "{$it}" } +
                    "\n\nUse {Name} or a column header from your file."
            )
            return
        }

        val list = recipients.list()
        val texts = list.map { MessageTemplate.render(template, it) }
        if (texts.any { it.isBlank() }) {
            alert("Empty message", "At least one personalized message is empty.")
            return
        }
        val tooLong = texts.count { it.length > Limits.MAX_CHARS }
        if (tooLong > 0) {
            alert(
                "Message too long",
                "$tooLong personalized message(s) exceed ${Limits.MAX_CHARS} characters after merging. " +
                    "Shorten the text or the merged fields."
            )
            return
        }

        val segs = texts.map { MessageTemplate.segments(it) }
        val samples = texts.take(3).mapIndexed { i, t -> "${list[i].phone}:\n$t" }.joinToString("\n\n")
        val msg = "Recipients: ${list.size}\n" +
            "Estimated SMS segments: ${segs.sum()} (carriers may bill each segment as one SMS)\n\n" +
            "Sample messages:\n\n$samples"

        MaterialAlertDialogBuilder(this)
            .setTitle("Preview")
            .setMessage(msg)
            .setPositiveButton("Send to ${list.size}") { _, _ -> startSending(template, list, texts, segs) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startSending(template: String, list: List<Recipient>, texts: List<String>, segs: List<Int>) {
        if (SendState.running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS
            )
        }
        val items = list.indices.map { i ->
            Item(list[i].name.ifBlank { list[i].phone }, list[i].phone, segs[i])
        }
        val batch = Batch(System.currentTimeMillis(), template, items)
        SendState.begin(batch, texts)
        history.save(batch)
        ContextCompat.startForegroundService(this, Intent(this, SendService::class.java))

        recipients.clear()
        lastImportInfo = ""
        updateRecipientViews()
        b.tabs.getTabAt(1)?.select()
    }

    // ---------------------------------------------------------------- progress

    private fun refreshSend() {
        val batch = SendState.current
        if (batch == null) {
            b.txtSendTitle.text = "Nothing sent in this session"
            b.txtSendCounts.text = "Start a batch from the Compose tab. Past batches are in History."
            b.progress.progress = 0
            b.txtLog.text = ""
            b.btnCancelSend.visibility = View.GONE
            b.btnExportCurrent.visibility = View.GONE
            return
        }
        val total = batch.items.size
        val sent = batch.sent
        val failed = batch.failed
        b.progress.max = total
        b.progress.progress = sent + failed
        b.txtSendTitle.text = if (SendState.running) "Sending…" else batch.stateLabel()
        b.txtSendCounts.text = "Sent $sent · Failed $failed · Remaining ${total - sent - failed}  (of $total)"

        val sb = StringBuilder()
        var shown = 0
        for (i in batch.items.indices.reversed()) {
            val item = batch.items[i]
            if (item.status == Item.SENT || item.status == Item.FAILED) {
                sb.append(item.label()).append("  ").append(item.phone)
                item.error?.let { sb.append("  (").append(it).append(")") }
                sb.append('\n')
                if (++shown >= 40) break
            }
        }
        b.txtLog.text = sb.toString()
        b.btnCancelSend.visibility = if (SendState.running) View.VISIBLE else View.GONE
        b.btnExportCurrent.visibility = if (SendState.running) View.GONE else View.VISIBLE
    }

    // ----------------------------------------------------------------- history

    private fun refreshHistory() {
        Thread {
            val cur = SendState.current
            val list = history.loadAll().map { if (cur != null && it.id == cur.id) cur else it }
            runOnUiThread {
                adapter.data = list
                adapter.notifyDataSetChanged()
                b.txtHistoryEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                b.btnClearHistory.visibility = if (list.isEmpty() || SendState.running) View.GONE else View.VISIBLE
            }
        }.start()
    }

    private fun showBatchDialog(batch: Batch) {
        val failures = batch.items.filter { it.status == Item.FAILED }
        val sb = StringBuilder()
        sb.append(dateFmt.format(Date(batch.id))).append(" · ").append(batch.stateLabel()).append("\n\n")
        sb.append("Sent ${batch.sent}, failed ${batch.failed}, not sent ${batch.items.size - batch.sent - batch.failed}")
        sb.append(" of ${batch.items.size}\n")
        sb.append("Segments: ${batch.totalSegments}\n\n")
        sb.append("Message template:\n").append(batch.template.take(300))
        if (failures.isNotEmpty()) {
            sb.append("\n\nFailures:\n")
            failures.take(15).forEach { sb.append(it.phone).append(" – ").append(it.error ?: "error").append('\n') }
            if (failures.size > 15) sb.append("…and ${failures.size - 15} more (see CSV report)")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Batch report")
            .setMessage(sb.toString())
            .setPositiveButton("Export CSV") { _, _ -> startExport(batch) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun startExport(batch: Batch) {
        pendingExport = batch
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(batch.id))
        exportLauncher.launch("mergesms_report_$stamp.csv")
    }

    private fun csvCell(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun writeReport(uri: Uri, batch: Batch) {
        try {
            val sb = StringBuilder("\uFEFFname,phone,status,segments,error\n")
            for (i in batch.items) {
                sb.append(csvCell(i.name)).append(',')
                    .append(csvCell(i.phone)).append(',')
                    .append(csvCell(i.label())).append(',')
                    .append(i.segments).append(',')
                    .append(csvCell(i.error.orEmpty())).append('\n')
            }
            contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            toast("Report saved")
        } catch (e: Exception) {
            toast("Failed to save report: ${e.message}")
        }
    }

    inner class HistoryAdapter : RecyclerView.Adapter<HistoryVH>() {
        var data: List<Batch> = emptyList()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HistoryVH(ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: HistoryVH, position: Int) {
            val batch = data[position]
            holder.vb.txtTitle.text = "${dateFmt.format(Date(batch.id))} · ${batch.stateLabel()}"
            holder.vb.txtMessage.text = batch.template
            holder.vb.txtCounts.text =
                "${batch.items.size} recipients · ${batch.sent} sent · ${batch.failed} failed"
            holder.vb.root.setOnClickListener { showBatchDialog(batch) }
        }
    }

    // ------------------------------------------------------------- permissions

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_CONTACTS -> if (granted) openContactPicker() else toast("Contacts permission denied")
            REQ_SEND_SMS -> if (granted) onPreviewClicked() else toast("Send SMS permission denied")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun toastOnUiThread(text: String) = runOnUiThread { toast(text) }
}
