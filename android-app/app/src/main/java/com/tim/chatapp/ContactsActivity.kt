package com.tim.chatapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tim.chatapp.databinding.ActivityContactsBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class ChatListItem {
    data class ContactItem(val contact: Contact) : ChatListItem()
    data class GroupItem(val group: Group) : ChatListItem()
}

class ContactsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactsBinding
    private lateinit var deviceId: String
    private lateinit var token: String
    private val onlineStates = mutableMapOf<String, Boolean>()
    private lateinit var chatAdapter: ChatListAdapter
    private var fabOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityContactsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = DeviceStorage.getDeviceId(this) ?: ""
        token = DeviceStorage.getToken(this) ?: ""

        chatAdapter = ChatListAdapter(onlineStates) { item ->
            when (item) {
                is ChatListItem.ContactItem -> {
                    startActivity(Intent(this, ChatActivity::class.java).apply {
                        putExtra("contact_id", item.contact.deviceId)
                        putExtra("contact_name", item.contact.displayName)
                    })
                }
                is ChatListItem.GroupItem -> {
                    startActivity(Intent(this, GroupChatActivity::class.java).apply {
                        putExtra("group_id", item.group.groupId)
                        putExtra("group_name", item.group.name)
                    })
                }
            }
        }
        binding.recyclerContacts.layoutManager = LinearLayoutManager(this)
        binding.recyclerContacts.adapter = chatAdapter

        binding.buttonAddContact.setOnClickListener { toggleFabMenu() }
        binding.fabOverlay.setOnClickListener { closeFabMenu() }
        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.fabOptionContact.setOnClickListener {
            closeFabMenu()
            startActivity(Intent(this, AddContactActivity::class.java))
        }
        binding.fabOptionGroup.setOnClickListener {
            closeFabMenu()
            startActivity(Intent(this, CreateGroupActivity::class.java))
        }

        loadAll()
        startOnlinePolling()
        startMessagePolling()
    }

    override fun onResume() {
        super.onResume()
        loadAll()
    }

    private fun toggleFabMenu() {
        if (fabOpen) closeFabMenu() else openFabMenu()
    }

    private fun openFabMenu() {
        fabOpen = true
        binding.fabMenu.visibility = View.VISIBLE
        binding.fabOverlay.visibility = View.VISIBLE

        val overshoot = OvershootInterpolator(1.5f)

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.fabMenu, "alpha", 0f, 1f),
                ObjectAnimator.ofFloat(binding.fabMenu, "scaleX", 0.5f, 1f),
                ObjectAnimator.ofFloat(binding.fabMenu, "scaleY", 0.5f, 1f),
                ObjectAnimator.ofFloat(binding.fabOverlay, "alpha", 0f, 0.5f),
                ObjectAnimator.ofFloat(binding.buttonAddContact, "rotation", 0f, 45f),
            )
            duration = 280
            interpolator = overshoot
            start()
        }
    }

    private fun closeFabMenu() {
        fabOpen = false

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(binding.fabMenu, "alpha", 1f, 0f),
                ObjectAnimator.ofFloat(binding.fabMenu, "scaleX", 1f, 0.5f),
                ObjectAnimator.ofFloat(binding.fabMenu, "scaleY", 1f, 0.5f),
                ObjectAnimator.ofFloat(binding.fabOverlay, "alpha", 0.5f, 0f),
                ObjectAnimator.ofFloat(binding.buttonAddContact, "rotation", 45f, 0f),
            )
            duration = 200
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.fabMenu.visibility = View.INVISIBLE
                    binding.fabOverlay.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun loadAll() {
        refreshList()
        lifecycleScope.launch {
            try {
                val resp = Api.service.getContacts(deviceId, token)
                resp.body()?.contacts?.forEach { c ->
                    ContactStore.addOrUpdate(this@ContactsActivity, Contact(c.device_id, c.display_name))
                }
            } catch (_: Exception) {}
            try {
                val resp = Api.service.getGroups(deviceId, token)
                resp.body()?.groups?.forEach { g ->
                    GroupStore.addOrUpdate(this@ContactsActivity, Group(
                        groupId = g.group_id, name = g.name, adminId = g.admin_id,
                        members = g.members.map { GroupMember(it.device_id, it.display_name) }
                    ))
                }
            } catch (_: Exception) {}
            refreshList()
        }
    }

    private fun refreshList() {
        val contacts = ContactStore.loadAll(this).map { ChatListItem.ContactItem(it) }
        val groups = GroupStore.loadAll(this).map { ChatListItem.GroupItem(it) }
        val all = contacts + groups
        chatAdapter.setItems(all)
        binding.textEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerContacts.visibility = if (all.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun startMessagePolling() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    val response = Api.service.pendingMessages(deviceId, token)
                    if (response.isSuccessful) {
                        response.body()?.messages?.forEach { item ->
                            if (item.message_type == "group_key" && item.group_id != null) {
                                val decrypted = try {
                                    if (!RatchetStateStore.hasSession(this@ContactsActivity, item.sender_device_id)) {
                                        val h = item.x3dh_header ?: return@forEach
                                        val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                                        RatchetManager.initAsReceiver(this@ContactsActivity, item.sender_device_id,
                                            X3DHHeaderData(dec(h.ikd_pub), dec(h.ek_pub), h.opk_id,
                                                if (h.iks_pub.isNotEmpty()) dec(h.iks_pub) else null))
                                    }
                                    RatchetManager.decrypt(this@ContactsActivity, item.sender_device_id,
                                        RatchetManager.EncryptedMessage(item.ciphertext, item.nonce,
                                            item.ratchet_pub, item.message_index, item.prev_send_index))
                                } catch (_: Exception) { null }
                                if (decrypted != null) {
                                    GroupKeyStore.save(this@ContactsActivity, item.group_id,
                                        Base64.decode(decrypted, Base64.NO_WRAP))
                                }
                                Api.service.ackMessage(AckRequest(deviceId, item.message_id), token)
                            }
                            // All other message types are left for ChatActivity / GroupChatActivity
                        }
                    }
                } catch (_: Exception) {}
                delay(5000)
            }
        }
    }

    private fun startOnlinePolling() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    Api.service.ping(PingRequest(deviceId), token)
                    ContactStore.loadAll(this@ContactsActivity).forEach { contact ->
                        try {
                            val resp = Api.service.onlineStatus(contact.deviceId, deviceId, token)
                            onlineStates[contact.deviceId] = resp.body()?.online == true
                        } catch (_: Exception) {}
                    }
                    chatAdapter.notifyDataSetChanged()
                } catch (_: Exception) {}
                delay(10000)
            }
        }
    }
}

class ChatListAdapter(
    private val onlineStates: Map<String, Boolean>,
    private val onClick: (ChatListItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ChatListItem>()

    fun setItems(list: List<ChatListItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is ChatListItem.ContactItem -> 0
        is ChatListItem.GroupItem -> 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_contact, parent, false)
        return ContactHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder as ContactHolder
        when (val item = items[position]) {
            is ChatListItem.ContactItem -> {
                val c = item.contact
                holder.textName.text = c.displayName
                holder.textDeviceId.text = c.deviceId.take(8) + "..."
                holder.textAvatar.text = c.displayName.firstOrNull()?.uppercase() ?: "?"
                val online = onlineStates[c.deviceId] == true
                holder.textOnline.visibility = if (online) View.VISIBLE else View.INVISIBLE
            }
            is ChatListItem.GroupItem -> {
                val g = item.group
                holder.textName.text = g.name
                holder.textDeviceId.text = "${g.members.size} Mitglieder"
                holder.textAvatar.text = "#"
                holder.textOnline.visibility = View.INVISIBLE
            }
        }
        holder.itemView.setOnClickListener { onClick(items[position]) }
    }

    override fun getItemCount() = items.size

    class ContactHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textName)
        val textDeviceId: TextView = view.findViewById(R.id.textDeviceId)
        val textAvatar: TextView = view.findViewById(R.id.textAvatar)
        val textOnline: View = view.findViewById(R.id.textOnline)
    }
}
