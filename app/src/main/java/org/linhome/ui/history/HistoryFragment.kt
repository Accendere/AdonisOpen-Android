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

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.linhome.GenericFragment
import org.linhome.LinhomeApplication.Companion.coreContext
import org.linhome.customisation.Texts
import org.linhome.customisation.Theme
import org.linhome.databinding.FragmentHistoryBinding
import org.linhome.utils.DialogUtil


class HistoryFragment : GenericFragment() {

    private lateinit var historyViewModel: HistoryViewModel

    val isEditing: Boolean
        get() = ::historyViewModel.isInitialized && historyViewModel.editing.value == true

    private lateinit var binding: FragmentHistoryBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentHistoryBinding.inflate(inflater, container, false)
        historyViewModel = ViewModelProvider(this).get(HistoryViewModel::class.java)
        binding.lifecycleOwner = this
        binding.model = historyViewModel

        binding.loglist.layoutManager =
            LinearLayoutManager(activity, RecyclerView.VERTICAL, false)
        binding.loglist.adapter =
            HistoryEventsAdapter(historyViewModel.history.value!!, historyViewModel, this)
        binding.loglist.setHasFixedSize(true)
        binding.loglist.viewTreeObserver.addOnGlobalLayoutListener(object :
            OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                hideProgress()
                binding.loglist.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })

        historyViewModel.selectedForDeletion.observe(viewLifecycleOwner, Observer { list ->
            if (historyViewModel.editing.value!! && list.isEmpty()) {
                mainactivity.binding.appbar.toolbarRightButton.isEnabled = false
                mainactivity.binding.appbar.toolbarRightButton.alpha = 0.5f
            } else {
                mainactivity.binding.appbar.toolbarRightButton.isEnabled = true
                mainactivity.binding.appbar.toolbarRightButton.alpha = 1.0f
            }
        })

        historyViewModel.history.observe(viewLifecycleOwner, Observer { list ->
            val hasItems = list.isNotEmpty()
            mainactivity.toolbarViewModel.rightButtonVisible.value = hasItems
            mainactivity.toolbarViewModel.leftButtonVisible.value = hasItems || historyViewModel.editing.value == true
            (binding.loglist.adapter as HistoryEventsAdapter).callLogs = list
            binding.loglist.adapter?.notifyDataSetChanged()
        })

        binding.selectall.setOnClickListener {
            historyViewModel.toggleSelectAllForDeletion()
        }

        return binding.root
    }


    override fun onToolbarRightButtonClicked() {
        if (historyViewModel.editing.value!!) {
            DialogUtil.confirm(
                "delete_history_confirm_message",
                { _: DialogInterface, _: Int ->
                    historyViewModel.deleteSelection()
                    (binding.loglist.adapter as HistoryEventsAdapter).callLogs =
                        historyViewModel.history.value!!
                    binding.loglist.adapter?.notifyDataSetChanged()
                    mainactivity.tabbarViewModel.updateUnreadCount()
                    exitEdition()
                }, (historyViewModel.selectedForDeletion.value!!.size).toString()
            )
        } else {
            val totalCount = historyViewModel.history.value?.size ?: 0
            if (totalCount > 0) {
                DialogUtil.confirm(
                    "delete_all_history_confirm_message",
                    { _: DialogInterface, _: Int ->
                        historyViewModel.deleteAll()
                        (binding.loglist.adapter as HistoryEventsAdapter).callLogs =
                            historyViewModel.history.value!!
                        binding.loglist.adapter?.notifyDataSetChanged()
                        mainactivity.tabbarViewModel.updateUnreadCount()
                        exitEdition()
                    }, totalCount.toString()
                )
            }
        }
    }

    override fun onToolbarLeftButtonClicked() {
        if (historyViewModel.editing.value == true) {
            exitEdition()
        } else {
            enterEdition()
        }
    }

    fun updateToolbarButtons() {
        val isEditing = historyViewModel.editing.value == true
        val hasItems = !historyViewModel.history.value.isNullOrEmpty()
        Theme.setIcon("icons/delete", mainactivity.binding.appbar.toolbarRightButtonImage)
        if (isEditing) {
            Theme.setIcon("icons/cancel", mainactivity.binding.appbar.toolbarLeftButtonImage)
            mainactivity.binding.appbar.toolbarLeftButtonTitle.text = Texts.get("cancel")
            mainactivity.binding.appbar.toolbarRightButtonTitle.text = Texts.get("delete")
            mainactivity.toolbarViewModel.leftButtonVisible.value = true
            mainactivity.toolbarViewModel.rightButtonVisible.value = true
            mainactivity.toolbarViewModel.burgerButtonVisible.value = false
            mainactivity.toolbarViewModel.backButtonVisible.value = false
            mainactivity.binding.appbar.contentmain.tabbar.visibility = View.GONE
        } else {
            Theme.setIcon("icons/edit", mainactivity.binding.appbar.toolbarLeftButtonImage)
            mainactivity.binding.appbar.toolbarLeftButtonTitle.text = Texts.get("select")
            mainactivity.binding.appbar.toolbarRightButtonTitle.text = Texts.get("clear_all")
            mainactivity.toolbarViewModel.leftButtonVisible.value = hasItems
            mainactivity.toolbarViewModel.rightButtonVisible.value = hasItems
            mainactivity.toolbarViewModel.burgerButtonVisible.value = true
            mainactivity.toolbarViewModel.backButtonVisible.value = false
            mainactivity.binding.appbar.contentmain.tabbar.visibility = View.VISIBLE
            mainactivity.binding.appbar.toolbarRightButton.isEnabled = true
            mainactivity.binding.appbar.toolbarRightButton.alpha = 1.0f
        }
    }

    override fun onResume() {
        super.onResume()
        updateToolbarButtons()
        coreContext.notificationsManager.dismissMissedCallNotification()
        binding.loglist.adapter?.notifyDataSetChanged()
        mainactivity.tabBarLayoutClicked(mainactivity.binding.appbar.contentmain.tabbarHistory, mainactivity.binding.appbar.contentmain.tabbarDevices)
    }

    fun enterEdition() {
        historyViewModel.editing.value = true
        updateToolbarButtons()
        historyViewModel.notifyDeleteSelectionListUpdated()
    }

    fun exitEdition() {
        historyViewModel.editing.value = false
        historyViewModel.selectedForDeletion.value!!.clear()
        updateToolbarButtons()
        historyViewModel.notifyDeleteSelectionListUpdated()
    }


    override fun onPause() {
        if (historyViewModel.editing.value == true) {
            exitEdition()
        }
        historyViewModel.markEventsAsRead()
        mainactivity.tabbarViewModel.updateUnreadCount()
        super.onPause()
    }
}
