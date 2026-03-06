package com.android.tv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class PhoneChannelAdapter(
    private val onClick: (Movie) -> Unit
) : ListAdapter<Movie, PhoneChannelAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.channel_title)
        private val image: ImageView = itemView.findViewById(R.id.channel_image)

        fun bind(movie: Movie) {
            title.text = movie.title
            if (movie.cardImageUrl != null) {
                Glide.with(itemView.context)
                    .load(movie.cardImageUrl)
                    .into(image)
            } else {
                image.setBackgroundColor(0xFF333333.toInt())
            }
            itemView.setOnClickListener { onClick(movie) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Movie>() {
        override fun areItemsTheSame(oldItem: Movie, newItem: Movie) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Movie, newItem: Movie) = oldItem == newItem
    }
}
