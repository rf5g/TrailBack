package com.trailback.app.ui.menu

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.trailback.app.R
import com.trailback.app.TrailBackApp
import com.trailback.app.data.db.EntryPoint
import com.trailback.app.databinding.ActivityEntryPointsBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class EntryPointsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEntryPointsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEntryPointsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as TrailBackApp
        binding.list.layoutManager = LinearLayoutManager(this)

        lifecycleScope.launch {
            app.trackingRepository.observeEntryPoints().collect { points ->
                binding.list.adapter = EntryPointsAdapter(points)
            }
        }

        binding.clearAllButton.setOnClickListener {
            showClearConfirmation1(app)
        }
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

class EntryPointsAdapter(private val items: List<EntryPoint>) :
    androidx.recyclerview.widget.RecyclerView.Adapter<EntryPointsAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())

    inner class ViewHolder(val binding: com.trailback.app.databinding.ItemMenuRowBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val binding = com.trailback.app.databinding.ItemMenuRowBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val point = items[position]
        holder.binding.titleText.text =
            "${point.name} — ${dateFormat.format(point.timestamp)}\n" +
            "%.5f, %.5f".format(point.latitude, point.longitude)
    }

    override fun getItemCount() = items.size
}
