package com.trailback.app.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.data.repository.TrackingMode
import com.trailback.app.databinding.ActivityEntryPointsBinding
import com.trailback.app.databinding.ItemMenuRowBinding
import com.trailback.app.service.TrackingService
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class EntryPointsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEntryPointsBinding
    private var trackingService: TrackingService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            trackingService = (binder as TrackingService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            trackingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntryPointsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as TrailBackApp
        binding.list.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            app.trackingRepository.observeEntryPoints().collect { points ->
                binding.list.adapter = EntryPointsAdapter(
                    items = points,
                    onTap = { point -> onEntryPointTapped(app, point) },
                    onLongPress = { point -> copyCoordinates(point) }
                )
            }
        }

        binding.clearAllButton.setOnClickListener {
            showClearConfirmation1(app)
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TrackingService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            isServiceBound = true
        }
    }

    override fun onStop() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onStop()
    }

    /**
     * По тапу — диалог "Выбрать эту точку?". Смена активной точки
     * заблокирована, пока активен режим "Домой" (см. решение по ТЗ).
     * При подтверждении выбора приложение сразу переходит в режим "Домой"
     * на выбранную точку (прямая пунктирная линия — маршрутных данных
     * может не быть в памяти устройства, это ожидаемо, см. решение по ТЗ).
     */
    private fun onEntryPointTapped(app: TrailBackApp, point: EntryPoint) {
        if (app.trackingStateStore.mode == TrackingMode.RETURNING) {
            Toast.makeText(this, R.string.select_entry_point_locked_in_returning, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.select_entry_point_title)
            .setMessage(point.name)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                lifecycleScope.launch {
                    app.trackingRepository.selectActiveEntryPoint(point.id)
                    app.trackingRepository.enterReturningMode()
                    trackingService?.updateMode(TrackingMode.RETURNING)
                    Toast.makeText(this@EntryPointsActivity, R.string.entry_point_set_active, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun copyCoordinates(point: EntryPoint) {
        val text = "%.6f, %.6f".format(point.latitude, point.longitude)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
        Toast.makeText(this, R.string.coordinates_copied, Toast.LENGTH_SHORT).show()
    }

    /** Тройное подтверждение массовой очистки (см. п.6.3 ТЗ и решение по формулировкам). */
    private fun showClearConfirmation1(app: TrailBackApp) {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_entry_points_warning_1)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showClearConfirmation2(app) }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun showClearConfirmation2(app: TrailBackApp) {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_entry_points_warning_2)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> showClearConfirmation3(app) }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }

    private fun showClearConfirmation3(app: TrailBackApp) {
        AlertDialog.Builder(this)
            .setMessage(R.string.clear_entry_points_warning_3)
            .setPositiveButton(R.string.arrived_dialog_yes) { _, _ ->
                lifecycleScope.launch { app.trackingRepository.clearAllEntryPoints() }
            }
            .setNegativeButton(R.string.arrived_dialog_no, null)
            .show()
    }
}

class EntryPointsAdapter(
    private val items: List<EntryPoint>,
    private val onTap: (EntryPoint) -> Unit,
    private val onLongPress: (EntryPoint) -> Unit
) : RecyclerView.Adapter<EntryPointsAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: ItemMenuRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMenuRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = items[position]
        holder.binding.titleText.text =
            "${point.name} — ${dateFormat.format(point.timestamp)}\n" +
            "%.5f, %.5f".format(point.latitude, point.longitude)

        holder.binding.root.setOnClickListener { onTap(point) }
        holder.binding.root.setOnLongClickListener {
            onLongPress(point)
            true
        }
    }

    override fun getItemCount() = items.size
}
