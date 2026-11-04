/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
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
package org.linphone.ui.main.contacts.fragment

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.loader.app.LoaderManager
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.linphone.LinphoneApplication.Companion.coreContext
import java.io.File
import org.linphone.R
import org.linphone.core.FriendList
import org.linphone.core.tools.Log
import org.linphone.databinding.ContactsListFilterPopupMenuBinding
import org.linphone.databinding.ContactsListFragmentBinding
import org.linphone.ui.fileviewer.FileViewerActivity
import org.linphone.ui.fileviewer.MediaViewerActivity
import org.linphone.ui.main.MainActivity
import org.linphone.ui.main.contacts.adapter.ContactsListAdapter
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.contacts.viewmodel.ContactsListViewModel
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.utils.ConfirmationDialogModel
import org.linphone.utils.DialogUtils
import org.linphone.utils.Event

@UiThread
class ContactsListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Contacts List Fragment]"
    }

    private lateinit var binding: ContactsListFragmentBinding

    private lateinit var listViewModel: ContactsListViewModel

    private lateinit var allContactsAdapter: ContactsListAdapter
    private lateinit var favouritesContactsAdapter: ContactsListAdapter

    private var bottomSheetDialog: BottomSheetDialogFragment? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("$TAG READ_CONTACTS permission has been granted, start contacts loader")
            (requireActivity() as MainActivity).loadContacts()
        } else {
            Log.w("$TAG READ_CONTACTS permission has been denied")
        }
    }

    private val swipeToRefreshListener = SwipeRefreshLayout.OnRefreshListener {
        Log.i("$TAG Swipe to refresh triggered, updating CardDAV friend lists")
        listViewModel.refreshCardDavContacts()
    }

    override fun onDefaultAccountChanged() {
        Log.i(
            "$TAG Default account changed, updating avatar in top bar & refreshing contacts list"
        )
        listViewModel.applyCurrentDefaultAccountFilter()
    }

    override fun onSlidingPaneClosed() {
        listViewModel.currentlyDisplayedItemId = ""
        allContactsAdapter.resetSelection()
    }

    // BizVoIP: a new contact goes into the phone's address book, through the phone's own contact editor, so it is in
    // the phone's Contacts and survives the app; the app then shows it like any other phone contact (the loader
    // reloads when the address book changes). Without the permission to read the phone's contacts the app couldn't
    // show it, so it stays in the app's own address book, as before, and so it does if no editor is installed.
    // A number of 6 digits or more is saved as a phone number; a short extension (201) as a SIP address, so that the
    // phone's own dialer doesn't call it over the mobile network.
    private fun openPhoneContactEditor(): Boolean {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("$TAG READ_CONTACTS not granted, keeping the new contact in the app's own address book")
            return false
        }

        // The app shows phone contacts once its loader runs: from the first registration on, or once the app itself
        // asked for the permission. Access granted later in the phone's settings starts nothing, so start it here, or
        // the contact saved there would show only after a restart.
        val activity = requireActivity()
        if (activity is MainActivity && LoaderManager.getInstance(activity).getLoader<Cursor>(0) == null) {
            Log.i("$TAG Phone contacts not loaded yet, starting the loader")
            activity.loadContacts()
        }

        val address = sharedViewModel.sipAddressToAddToNewContact.removePrefix("sip:").removePrefix("sips:")
        val user = address.substringBefore("@").substringBefore(";")
        val intent = Intent(ContactsContract.Intents.Insert.ACTION).apply {
            type = ContactsContract.RawContacts.CONTENT_TYPE
            putExtra("finishActivityOnSaveCompleted", true) // back to the app once saved
            if (Regex("^\\+?[0-9]{6,}$").matches(user)) {
                putExtra(ContactsContract.Intents.Insert.PHONE, user)
            } else if (address.isNotEmpty()) {
                val sip = ContentValues().apply {
                    put(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE
                    )
                    put(ContactsContract.CommonDataKinds.SipAddress.SIP_ADDRESS, address)
                }
                putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, arrayListOf(sip))
            }
        }
        return try {
            startActivity(intent)
            sharedViewModel.sipAddressToAddToNewContact = ""
            Log.i("$TAG Opened the phone's contact editor for creating new contact")
            true
        } catch (anfe: ActivityNotFoundException) {
            Log.w("$TAG No contact editor on this phone, keeping the new contact in the app: $anfe")
            false
        }
    }

    override fun onCreateAnimation(transit: Int, enter: Boolean, nextAnim: Int): Animation? {
        if (findNavController().currentDestination?.id == R.id.newContactFragment
        ) {
            // Holds fragment in place while new fragment slides over it
            return AnimationUtils.loadAnimation(activity, R.anim.hold)
        }
        return super.onCreateAnimation(transit, enter, nextAnim)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allContactsAdapter = ContactsListAdapter()
        favouritesContactsAdapter = ContactsListAdapter(favourites = true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ContactsListFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listViewModel = ViewModelProvider(this)[ContactsListViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = listViewModel
        observeToastEvents(listViewModel)

        // Disabled by default, may be enabled in onResume()
        binding.contactsListSwipeRefresh.isEnabled = false
        binding.contactsListSwipeRefresh.setOnRefreshListener(swipeToRefreshListener)
        binding.contactsListSwipeRefresh.outlineProvider = outlineProvider
        binding.contactsListSwipeRefresh.clipToOutline = true

        binding.contactsList.setHasFixedSize(true)
        binding.contactsList.layoutManager = LinearLayoutManager(requireContext())

        binding.favouritesContactsList.setHasFixedSize(true)
        val favouritesLayoutManager = LinearLayoutManager(requireContext())
        favouritesLayoutManager.orientation = LinearLayoutManager.HORIZONTAL
        binding.favouritesContactsList.layoutManager = favouritesLayoutManager

        configureAdapter(allContactsAdapter)
        configureAdapter(favouritesContactsAdapter)

        listViewModel.isListFiltered.observe(viewLifecycleOwner) { filtered ->
            binding.contactsList.clipToOutline = filtered
        }

        listViewModel.contactsList.observe(viewLifecycleOwner) {
            allContactsAdapter.submitList(it)

            // Wait for adapter to have items before setting it in the RecyclerView,
            // otherwise scroll position isn't retained
            if (binding.contactsList.adapter != allContactsAdapter) {
                binding.contactsList.adapter = allContactsAdapter

                highlightSelectedContactIfAny()
            }

            Log.i("$TAG Contacts list updated with [${it.size}] items")
            listViewModel.fetchInProgress.value = false
        }

        listViewModel.favouritesList.observe(viewLifecycleOwner) {
            favouritesContactsAdapter.submitList(it)

            // Wait for adapter to have items before setting it in the RecyclerView,
            // otherwise scroll position isn't retained
            if (binding.favouritesContactsList.adapter != favouritesContactsAdapter) {
                binding.favouritesContactsList.adapter = favouritesContactsAdapter
            }

            Log.i("$TAG Favourites contacts list updated with [${it.size}] items")
        }

        listViewModel.vCardTerminatedEvent.observe(viewLifecycleOwner) {
            it.consume { pair ->
                val contactName = pair.first
                val file = pair.second
                Log.i(
                    "$TAG Friend [$contactName] was exported as vCard file [${file.absolutePath}], sharing it"
                )
                shareContact(contactName, file)
            }
        }

        listViewModel.cardDavSynchronizationCompletedEvent.observe(viewLifecycleOwner) {
            it.consume {
                Log.i("$TAG CardDAV synchronization has completed")
                binding.contactsListSwipeRefresh.isRefreshing = false
            }
        }

        binding.setOnNewContactClicked {
            sharedViewModel.showNewContactEvent.value = Event(true)
        }

        binding.setFilterClickListener {
            showFilterPopupMenu(binding.topBar.extraAction)
        }

        sharedViewModel.showContactEvent.observe(viewLifecycleOwner) {
            it.consume { refKey ->
                showContact(refKey)
            }
        }

        sharedViewModel.showNewContactEvent.observe(viewLifecycleOwner) {
            it.consume {
                if (findNavController().currentDestination?.id == R.id.contactsListFragment) {
                    if (openPhoneContactEditor()) return@consume
                    Log.i("$TAG Opening contact editor for creating new contact")
                    val action =
                        ContactsListFragmentDirections.actionContactsListFragmentToNewContactFragment()
                    findNavController().navigate(action)
                }
            }
        }

        sharedViewModel.forceRefreshContactsList.observe(viewLifecycleOwner) {
            it.consume {
                listViewModel.filter()
            }
        }

        sharedViewModel.displayFileEvent.observe(viewLifecycleOwner) {
            it.consume { bundle ->
                if (findNavController().currentDestination?.id == R.id.contactsListFragment) {
                    val path = bundle.getString("path", "")
                    val isMedia = bundle.getBoolean("isMedia", false)
                    if (path.isEmpty()) {
                        Log.e("$TAG Can't navigate to file viewer for empty path!")
                        return@consume
                    }

                    Log.i(
                        "$TAG Navigating to [${if (isMedia) "media" else "file"}] viewer fragment with path [$path]"
                    )
                    if (isMedia) {
                        val intent = Intent(requireActivity(), MediaViewerActivity::class.java)
                        intent.putExtras(bundle)
                        startActivity(intent)
                    } else {
                        val intent = Intent(requireActivity(), FileViewerActivity::class.java)
                        intent.putExtras(bundle)
                        startActivity(intent)
                    }
                }
            }
        }

        // AbstractMainFragment related

        listViewModel.title.value = getString(R.string.bottom_navigation_contacts_label)
        setViewModel(listViewModel)
        initViews(
            binding.slidingPaneLayout,
            binding.topBar,
            binding.bottomNavBar,
            R.id.contactsListFragment
        )

        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("$TAG READ_CONTACTS permission wasn't granted yet, asking for it now")
            requestPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    override fun onPause() {
        super.onPause()

        bottomSheetDialog?.dismiss()
        bottomSheetDialog = null
    }

    override fun onResume() {
        super.onResume()

        coreContext.postOnCoreThread { core ->
            val cardDavFriendList = core.friendsLists.find {
                it.type == FriendList.Type.CardDAV
            }
            val cardDavFriendListFound = cardDavFriendList != null
            if (cardDavFriendListFound) {
                Log.i("$TAG CardDAV friend list [${cardDavFriendList.displayName}] found, enabling swipe to refresh")
            } else {
                Log.i("$TAG No CardDAV friend list was found, disabling swipe to refresh")
            }
            coreContext.postOnMainThread {
                binding.contactsListSwipeRefresh.isEnabled = cardDavFriendListFound
            }
        }
    }

    private fun configureAdapter(adapter: ContactsListAdapter) {
        adapter.contactLongClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                val modalBottomSheet = ContactsListMenuDialogFragment(
                    model.isFavourite.value == true,
                    model.isStored,
                    isReadOnly = model.isReadOnly,
                    isNative = model.isNative,
                    { // onDismiss
                        adapter.resetActivated()
                    },
                    { // onFavourite
                        listViewModel.toggleContactFavoriteFlag(model)
                    },
                    { // onShare
                        Log.i(
                            "$TAG Sharing friend [${model.name.value}], exporting it as vCard file first"
                        )
                        listViewModel.exportContactAsVCard(model.friend)
                    },
                    { // onDelete
                        showDeleteConfirmationDialog(model)
                    }
                )
                modalBottomSheet.show(parentFragmentManager, ContactsListMenuDialogFragment.TAG)
                bottomSheetDialog = modalBottomSheet
            }
        }

        adapter.favouriteContactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                sharedViewModel.displayedFriend = model.friend
                allContactsAdapter.resetSelection()
                showContact(model.id)
                highlightSelectedContactIfAny()
            }
        }

        adapter.contactClickedEvent.observe(viewLifecycleOwner) {
            it.consume { model ->
                sharedViewModel.displayedFriend = model.friend
                showContact(model.id)
            }
        }
    }

    private fun showContact(id: String) {
        Log.i("$TAG Displaying contact with ref key [$id]")
        listViewModel.currentlyDisplayedItemId = id

        val navController = binding.contactsNavContainer.findNavController()
        val action = ContactFragmentDirections.actionGlobalContactFragment(
            id
        )
        navController.navigate(action)
    }

    private fun shareContact(name: String, file: File) {
        val publicUri = FileProvider.getUriForFile(
            requireContext(),
            requireContext().getString(R.string.file_provider),
            file
        )
        Log.i("$TAG Public URI for vCard file is [$publicUri], starting intent chooser")

        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, publicUri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            type = ContactsContract.Contacts.CONTENT_VCARD_TYPE
        }

        val shareIntent = Intent.createChooser(sendIntent, null)
        try {
            startActivity(shareIntent)
        } catch (anfe: ActivityNotFoundException) {
            Log.e("$TAG Failed to start intent chooser: $anfe")
        }
    }

    private fun showFilterPopupMenu(view: View) {
        val popupView: ContactsListFilterPopupMenuBinding = DataBindingUtil.inflate(
            LayoutInflater.from(requireContext()),
            R.layout.contacts_list_filter_popup_menu,
            null,
            false
        )
        popupView.seeAllSelected = listViewModel.areAllContactsDisplayed.value == true
        popupView.showLinphoneFilter = listViewModel.isDefaultAccountLinphone.value == true

        val popupWindow = PopupWindow(
            popupView.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        popupView.setNoFilterClickListener {
            if (listViewModel.areAllContactsDisplayed.value != true) {
                listViewModel.changeContactsFilter(
                    onlyLinphoneContacts = false,
                    onlySipContacts = false
                )
            }
            popupWindow.dismiss()
        }

        popupView.setLinphoneOnlyClickListener {
            if (listViewModel.areAllContactsDisplayed.value == true) {
                listViewModel.changeContactsFilter(
                    onlyLinphoneContacts = true,
                    onlySipContacts = false
                )
            }
            popupWindow.dismiss()
        }

        popupView.setSipOnlyClickListener {
            if (listViewModel.areAllContactsDisplayed.value == true) {
                listViewModel.changeContactsFilter(
                    onlyLinphoneContacts = false,
                    onlySipContacts = true
                )
            }
            popupWindow.dismiss()
        }

        // Elevation is for showing a shadow around the popup
        popupWindow.elevation = 20f
        popupWindow.showAsDropDown(view, 0, 0, Gravity.BOTTOM)
    }

    private fun showDeleteConfirmationDialog(contactModel: ContactAvatarModel) {
        val model = ConfirmationDialogModel()
        val dialog = DialogUtils.getDeleteContactConfirmationDialog(
            requireActivity(),
            model,
            contactModel.contactName.orEmpty()
        )

        model.dismissEvent.observe(viewLifecycleOwner) {
            it.consume {
                dialog.dismiss()
            }
        }

        model.confirmEvent.observe(viewLifecycleOwner) {
            it.consume {
                listViewModel.deleteContact(contactModel)
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun highlightSelectedContactIfAny() {
        val index = listViewModel.contactsList.value.orEmpty().indexOfFirst { contact ->
            contact.id == listViewModel.currentlyDisplayedItemId
        }
        Log.i("$TAG Found contact with RefKey [${listViewModel.currentlyDisplayedItemId}] at index [$index]")
        allContactsAdapter.notifyItemHasBeenSelected(index)
    }
}
