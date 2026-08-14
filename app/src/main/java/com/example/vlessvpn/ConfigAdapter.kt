package com.example.vlessvpn

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.vlessvpn.databinding.ItemConfigBinding

class ConfigAdapter(
    private val onSelect: (VpnConfig) -> Unit,
    private val onEdit: (VpnConfig) -> Unit,
    private val onDelete: (VpnConfig) -> Unit,
    private val onExport: (VpnConfig) -> Unit
) : RecyclerView.Adapter<ConfigAdapter.ViewHolder>() {

    private val items = mutableListOf<VpnConfig>()
    private var selectedId: String? = null

    fun submitList(newItems: List<VpnConfig>, selectedId: String?) {
        items.clear()
        items.addAll(newItems)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConfigBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], items[position].id == selectedId)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemConfigBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(config: VpnConfig, isSelected: Boolean) {
            binding.nameText.text = config.name.ifBlank { config.host }
            binding.subtitleText.text = "${config.host}  ${config.transportTag()}"

            binding.dotIndicator.setBackgroundResource(
                if (isSelected) R.drawable.bg_dot_selected else R.drawable.bg_dot_unselected
            )
            binding.cardRoot.setBackgroundResource(
                if (isSelected) R.drawable.bg_config_card_selected else R.drawable.bg_config_card
            )

            binding.root.setOnClickListener { onSelect(config) }
            binding.exportButton.setOnClickListener { onExport(config) }
            binding.editButton.setOnClickListener { onEdit(config) }
            binding.deleteButton.setOnClickListener { onDelete(config) }
        }
    }
}
