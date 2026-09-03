package com.trailback.app.ui.menu
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.trailback.app.data.db.MarkedPlace
import com.trailback.app.databinding.ActivityMarkedPlacesBinding
import com.trailback.app.databinding.ItemMarkedPlaceRowBinding
import kotlinx.coroutines.launch
import java.util.Locale
class MarkedPlacesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMarkedPlacesBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMarkedPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as TrailBackApp
        binding.list.layoutManager = LinearLayoutManager(this)
        lifecycleScope.launch {
            app.database.markedPlaceDao().observeAll().collect { places ->
                binding.list.adapter = MarkedPlacesAdapter(
                    items = places,
                    onOpenInNavigation = ::openInNavigationApp,
                    onCopyCoordinates = ::copyCoordinates,
                    onDelete = { place -> lifecycleScope.launch { app.database.markedPlaceDao().delete(place) } }
                )
            }
        }
    }
    private fun openInNavigationApp(place: MarkedPlace) {
        // geo: URI — система сама предложит установленные навигационные приложения
        val uri = Uri.parse(
            "geo:${place.latitude},${place.longitude}?q=${place.latitude},${place.longitude}(${Uri.encode(place.name)})"
        )
        startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    private fun copyCoordinates(place: MarkedPlace) {
        val text = "%.6f, %.6f".format(place.latitude, place.longitude)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("coordinates", text))
        Toast.makeText(this, R.string.coordinates_copied, Toast.LENGTH_SHORT).show()
    }
}
class MarkedPlacesAdapter(
    private val items: List<MarkedPlace>,
    private val onOpenInNavigation: (MarkedPlace) -> Unit,
    private val onCopyCoordinates: (MarkedPlace) -> Unit,
    private val onDelete: (MarkedPlace) -> Unit
) : RecyclerView.Adapter<MarkedPlacesAdapter.ViewHolder>() {
    inner class ViewHolder(val binding: ItemMarkedPlaceRowBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMarkedPlaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val place = items[position]
        holder.binding.nameText.text = place.name
        holder.binding.coordinatesText.text =
            String.format(Locale.getDefault(), "%.5f, %.5f", place.latitude, place.longitude)
        holder.binding.openInNavigationButton.setOnClickListener { onOpenInNavigation(place) }
        holder.binding.copyButton.setOnClickListener { onCopyCoordinates(place) }
        holder.binding.deleteButton.setOnClickListener {
            AlertDialog.Builder(holder.binding.root.context)
                .setMessage(place.name)
                .setPositiveButton(R.string.arrived_dialog_yes) { _, _ -> onDelete(place) }
                .setNegativeButton(R.string.arrived_dialog_no, null)
                .show()
        }
    }
    override fun getItemCount() = items.size
}
