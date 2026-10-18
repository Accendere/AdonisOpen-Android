/*
 * Copyright (c) 2010-2020 Belledonne Communications SARL.
 *
 * This file is part of linhome-android
 * (see https://www.linhome.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.linhome.ui.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView
import org.linhome.BR
import org.linhome.GenericFragment
import org.linhome.LinhomeApplication
import org.linhome.R
import org.linhome.customisation.Texts
import org.linhome.databinding.ItemHistoryBinding
import org.linhome.ui.player.PlayerActivity
import org.linphone.core.CallLog

class HistoryEventsAdapter(
    var callLogs: MutableList<CallLog>,
    val historyViewModel: HistoryViewModel,
    val linhomeFragment: GenericFragment
) :
    RecyclerView.Adapter<HistoryEventsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = DataBindingUtil.inflate(
            LayoutInflater.from(parent.context),
            R.layout.item_history,
            parent,
            false
        ) as ItemHistoryBinding
        return ViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return callLogs.size
    }

    override fun getItemId(position: Int): Long {
        return callLogs.get(position).startDate
    }

    class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            callLog: CallLog,
            showDate: Boolean,
            historyViewModel: HistoryViewModel,
            linhomeFragment: GenericFragment
        ) {
            val entryViewModel = HistoryEventViewModel(callLog, showDate, historyViewModel)
            binding.lifecycleOwner = linhomeFragment
            binding.setVariable(BR.model, entryViewModel)
            binding.setVariable(BR.historymodel, historyViewModel)
            binding.executePendingBindings()
            entryViewModel.viewMedia.observe(linhomeFragment, Observer { play ->
                if (play)
                    playMedia(callLog, linhomeFragment)
            })
            entryViewModel.addDevice.observe(linhomeFragment, Observer { add ->
                if (add) {
                    entryViewModel.addDevice.value = false
                    val bundle = Bundle().apply {
                        putString("address", callLog.remoteAddress.asStringUriOnly())
                    }
                    linhomeFragment.mainactivity.navController.navigate(R.id.navigation_device_edit, bundle)
                }
            })
            entryViewModel.copyNumberEvent.observe(linhomeFragment, Observer { copied ->
                if (copied) {
                    entryViewModel.copyNumberEvent.value = false
                    val number = entryViewModel.getSipNumber()
                    if (number.isNotEmpty()) {
                        val context = linhomeFragment.context ?: LinhomeApplication.instance
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("SIP Number", number)
                        clipboard?.setPrimaryClip(clip)
                        val msg = Texts.get("history_number_copied", number)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        fun playMedia(callLog: CallLog, linhomeFragment: GenericFragment) {
            val intent = Intent(linhomeFragment.activity, PlayerActivity::class.java)
            intent.putExtra("callId", callLog.callId)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            linhomeFragment.activity?.startActivity(intent)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val callLog = callLogs.get(position)
        holder.bind(
            callLog,
            position == 0 || (callLogs.get(position - 1).startDate / 86400 != callLog.startDate / 86400),
            historyViewModel,
            linhomeFragment
        )
    }


}