package com.decikeeper.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.decikeeper.app.R
import com.decikeeper.app.manager.AlarmRingtoneManager
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.RingtoneItem

class AlarmRingtonePickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CURRENT_RINGTONE_URI = "extra_current_ringtone_uri"
        const val EXTRA_SELECTED_RINGTONE_URI = "extra_selected_ringtone_uri"
        const val EXTRA_SELECTED_RINGTONE_TITLE = "extra_selected_ringtone_title"

        fun createIntent(context: Context, currentUri: String?): Intent {
            return Intent(context, AlarmRingtonePickerActivity::class.java).apply {
                putExtra(EXTRA_CURRENT_RINGTONE_URI, currentUri)
            }
        }
    }

    private var selectedUri: String? = null
    private var currentlyPlayingUri: String? = null
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var tvCurrentSelectedHint: TextView
    private lateinit var tvSystemTitle: TextView
    private lateinit var tvCustomTitle: TextView
    private lateinit var etSearchSystem: EditText
    private lateinit var listSystemRingtones: ListView
    private lateinit var listCustomRingtones: ListView
    private lateinit var tvEmptyCustom: TextView
    private lateinit var btnImportCustom: Button
    private lateinit var btnStopPreview: Button
    private lateinit var btnSaveRingtone: Button
    private lateinit var btnBack: TextView

    private var allSystemItems = listOf<RingtoneItem>()
    private var filteredSystemItems = listOf<RingtoneItem>()
    private var customItems = listOf<RingtoneItem>()

    private lateinit var systemAdapter: RingtoneListAdapter
    private lateinit var customAdapter: RingtoneListAdapter

    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val newItem = AlarmRingtoneManager.importCustomRingtone(this, uri)
            if (newItem != null) {
                Toast.makeText(this, "已成功导入「${newItem.title}」", Toast.LENGTH_SHORT).show()
                selectedUri = newItem.uri
                updateSelectedHint()
                loadCustomRingtones()
                startAudition(newItem.uri)
            } else {
                Toast.makeText(this, "音频导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm_ringtone_picker)

        val inputUri = intent.getStringExtra(EXTRA_CURRENT_RINGTONE_URI)
        selectedUri = if (!inputUri.isNullOrBlank()) {
            inputUri
        } else {
            AlarmScheduler.getDefaultRingtoneUri(this)
                ?: RingtoneItem(AlarmRingtoneManager.DEFAULT_SYSTEM_ALARM_ID, "", "").id
        }

        initViews()
        loadSystemRingtones()
        loadCustomRingtones()
        updateSelectedHint()
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btn_back)
        tvCurrentSelectedHint = findViewById(R.id.tv_current_selected_hint)
        tvSystemTitle = findViewById(R.id.tv_system_title)
        tvCustomTitle = findViewById(R.id.tv_custom_title)
        etSearchSystem = findViewById(R.id.et_search_system)
        listSystemRingtones = findViewById(R.id.list_system_ringtones)
        listCustomRingtones = findViewById(R.id.list_custom_ringtones)
        tvEmptyCustom = findViewById(R.id.tv_empty_custom)
        btnImportCustom = findViewById(R.id.btn_import_custom)
        btnStopPreview = findViewById(R.id.btn_stop_preview)
        btnSaveRingtone = findViewById(R.id.btn_save_ringtone)

        btnBack.setOnClickListener { finish() }

        systemAdapter = RingtoneListAdapter(isCustom = false)
        listSystemRingtones.adapter = systemAdapter

        customAdapter = RingtoneListAdapter(isCustom = true)
        listCustomRingtones.adapter = customAdapter

        etSearchSystem.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterSystemRingtones(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnImportCustom.setOnClickListener {
            try {
                importAudioLauncher.launch(arrayOf("audio/*", "application/ogg", "application/octet-stream"))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开系统文件管理器", Toast.LENGTH_SHORT).show()
            }
        }

        btnStopPreview.setOnClickListener {
            stopAudition()
        }

        btnSaveRingtone.setOnClickListener {
            stopAudition()
            val uriToReturn = selectedUri
            val titleToReturn = AlarmRingtoneManager.getRingtoneTitle(this, uriToReturn)
            val resultIntent = Intent().apply {
                putExtra(EXTRA_SELECTED_RINGTONE_URI, uriToReturn)
                putExtra(EXTRA_SELECTED_RINGTONE_TITLE, titleToReturn)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun loadSystemRingtones() {
        allSystemItems = AlarmRingtoneManager.getSystemRingtones(this)
        filterSystemRingtones(etSearchSystem.text.toString().trim())
    }

    private fun filterSystemRingtones(query: String) {
        filteredSystemItems = if (query.isEmpty()) {
            allSystemItems
        } else {
            allSystemItems.filter { it.title.contains(query, ignoreCase = true) }
        }
        tvSystemTitle.text = "系统自带 (${filteredSystemItems.size})"
        systemAdapter.notifyDataSetChanged()
    }

    private fun loadCustomRingtones() {
        customItems = AlarmRingtoneManager.getCustomRingtones(this)
        tvCustomTitle.text = "自己导入 (${customItems.size})"
        tvEmptyCustom.visibility = if (customItems.isEmpty()) View.VISIBLE else View.GONE
        customAdapter.notifyDataSetChanged()
    }

    private fun updateSelectedHint() {
        val title = AlarmRingtoneManager.getRingtoneTitle(this, selectedUri)
        tvCurrentSelectedHint.text = "当前选中：$title"
        systemAdapter.notifyDataSetChanged()
        customAdapter.notifyDataSetChanged()
    }

    private fun isItemSelected(item: RingtoneItem): Boolean {
        if (selectedUri == null) return false
        if (selectedUri == item.uri || selectedUri == item.id) return true
        if (item.id == AlarmRingtoneManager.DEFAULT_SYSTEM_ALARM_ID &&
            (selectedUri == null || selectedUri == item.uri)
        ) {
            return true
        }
        return false
    }

    private fun startAudition(uriString: String) {
        if (currentlyPlayingUri == uriString && mediaPlayer?.isPlaying == true) {
            stopAudition()
            return
        }

        stopAudition()
        try {
            val uri = Uri.parse(uriString)
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmRingtonePickerActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            currentlyPlayingUri = uriString
            systemAdapter.notifyDataSetChanged()
            customAdapter.notifyDataSetChanged()
        } catch (e: Exception) {
            Toast.makeText(this, "试听失败，该音频文件可能无法解码", Toast.LENGTH_SHORT).show()
            stopAudition()
        }
    }

    private fun stopAudition() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentlyPlayingUri = null
        systemAdapter.notifyDataSetChanged()
        customAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        stopAudition()
    }

    override fun onDestroy() {
        stopAudition()
        super.onDestroy()
    }

    private inner class RingtoneListAdapter(private val isCustom: Boolean) : BaseAdapter() {
        override fun getCount(): Int = if (isCustom) customItems.size else filteredSystemItems.size

        override fun getItem(position: Int): RingtoneItem =
            if (isCustom) customItems[position] else filteredSystemItems[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AlarmRingtonePickerActivity)
                .inflate(R.layout.item_ringtone_picker, parent, false)

            val item = getItem(position)
            val rbSelected = view.findViewById<RadioButton>(R.id.rb_selected)
            val tvTitle = view.findViewById<TextView>(R.id.tv_ringtone_title)
            val btnPreview = view.findViewById<TextView>(R.id.btn_preview)
            val btnDelete = view.findViewById<TextView>(R.id.btn_delete)

            tvTitle.text = item.title

            val selected = isItemSelected(item)
            rbSelected.isChecked = selected

            val isPlaying = currentlyPlayingUri == item.uri
            btnPreview.text = if (isPlaying) "⏸ 暂停" else "▶ 试听"
            btnPreview.setBackgroundResource(R.drawable.bg_zz_pill)

            btnPreview.setOnClickListener {
                startAudition(item.uri)
            }

            view.setOnClickListener {
                selectedUri = item.uri
                updateSelectedHint()
                startAudition(item.uri)
            }

            if (isCustom) {
                btnDelete.visibility = View.VISIBLE
                btnDelete.setOnClickListener {
                    AlertDialog.Builder(this@AlarmRingtonePickerActivity)
                        .setTitle("确认删除音频")
                        .setMessage("确定要删除「${item.title}」吗？")
                        .setPositiveButton("删除") { _, _ ->
                            if (currentlyPlayingUri == item.uri) {
                                stopAudition()
                            }
                            if (selectedUri == item.uri) {
                                selectedUri = allSystemItems.firstOrNull()?.uri
                            }
                            AlarmRingtoneManager.deleteCustomRingtone(this@AlarmRingtonePickerActivity, item.id)
                            loadCustomRingtones()
                            updateSelectedHint()
                            Toast.makeText(this@AlarmRingtonePickerActivity, "已删除", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } else {
                btnDelete.visibility = View.GONE
            }

            return view
        }
    }
}
