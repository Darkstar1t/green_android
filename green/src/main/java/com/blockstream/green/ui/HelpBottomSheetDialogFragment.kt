package com.blockstream.green.ui

import com.blockstream.green.R
import com.blockstream.green.Urls
import com.blockstream.green.databinding.ListItemHelpBinding
import com.blockstream.green.ui.items.HelpListItem
import com.blockstream.green.utils.openBrowser
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.binding.listeners.addClickListener
import com.mikepenz.fastadapter.ui.utils.StringHolder
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HelpBottomSheetDialogFragment : AbstractHelpBottomSheetDialogFragment() {

    override fun createFastAdapter(): FastAdapter<HelpListItem> {
        val itemAdapter = ItemAdapter<HelpListItem>()

        val list = mutableListOf<HelpListItem>()

        list += HelpListItem(
            StringHolder(R.string.id_i_typed_all_my_recovery_phrase),
            StringHolder(R.string.id_1_double_check_all_of_your),
            StringHolder(R.string.id_visit_the_blockstream_help)
        )

        itemAdapter.add(list)

        val fastAdapter = FastAdapter.with(itemAdapter)

        fastAdapter.addClickListener<ListItemHelpBinding, HelpListItem>({ binding -> binding.button }) { _, _, _, _ ->
            openBrowser(requireContext(), Urls.HELP_MNEMONIC_NOT_WORKING)
        }

        return fastAdapter
    }
}