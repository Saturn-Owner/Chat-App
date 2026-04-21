package com.tim.chatapp

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tim.chatapp.databinding.ActivityCreateGroupBinding
import kotlinx.coroutines.launch

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var deviceId: String
    private lateinit var token: String
    private lateinit var selectAdapter: SelectContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = DeviceStorage.getDeviceId(this) ?: ""
        token = DeviceStorage.getToken(this) ?: ""

        val contacts = ContactStore.loadAll(this)
        selectAdapter = SelectContactAdapter(contacts)
        binding.recyclerSelectContacts.layoutManager = LinearLayoutManager(this)
        binding.recyclerSelectContacts.adapter = selectAdapter

        binding.buttonBack.setOnClickListener { finish() }

        binding.buttonCreate.setOnClickListener {
            val name = binding.editGroupName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Gruppenname eingeben", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selected = selectAdapter.getSelected()
            if (selected.isEmpty()) {
                Toast.makeText(this, "Mindestens einen Kontakt auswählen", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            createGroup(name, selected)
        }
    }

    private fun createGroup(name: String, members: List<Contact>) {
        binding.buttonCreate.isClickable = false
        lifecycleScope.launch {
            try {
                // 1. Create group on server
                val resp = Api.service.createGroup(
                    CreateGroupRequest(name = name, member_ids = members.map { it.deviceId }),
                    deviceId, token,
                )
                if (!resp.isSuccessful || resp.body() == null) {
                    Toast.makeText(this@CreateGroupActivity, "Fehler beim Erstellen", Toast.LENGTH_SHORT).show()
                    binding.buttonCreate.isClickable = true
                    return@launch
                }
                val groupId = resp.body()!!.group_id

                // 2. Generate group key
                val groupKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
                GroupKeyStore.save(this@CreateGroupActivity, groupId, groupKey)
                val groupKeyB64 = Base64.encodeToString(groupKey, Base64.NO_WRAP)

                // 3. Store group locally
                val myName = DeviceStorage.getDisplayName(this@CreateGroupActivity) ?: "Ich"
                val allMembers = (members.map { GroupMember(it.deviceId, it.displayName) } +
                        GroupMember(deviceId, myName))
                GroupStore.addOrUpdate(this@CreateGroupActivity, Group(
                    groupId = groupId, name = name, adminId = deviceId, members = allMembers
                ))

                // 4. Distribute group key to each member via pairwise ratchet
                members.forEach { contact ->
                    try {
                        val hasSession = RatchetStateStore.hasSession(this@CreateGroupActivity, contact.deviceId)
                        var x3dhHeader: X3DHHeader? = null
                        if (!hasSession) {
                            val bundleResp = Api.service.getPreKeyBundle(contact.deviceId, deviceId, token)
                            if (bundleResp.isSuccessful && bundleResp.body() != null) {
                                val b = bundleResp.body()!!
                                val dec = { s: String -> Base64.decode(s, Base64.NO_WRAP) }
                                val bundle = RatchetManager.RecipientBundle(
                                    iksPub = dec(b.iks_pub), ikdPub = dec(b.ikd_pub),
                                    spkId = b.spk.id, spkPub = dec(b.spk.pub), spkSig = dec(b.spk.sig),
                                    opkId = b.opk?.id ?: -1, opkPub = b.opk?.let { dec(it.pub) },
                                )
                                val ekPub = RatchetManager.initAsSender(this@CreateGroupActivity, contact.deviceId, bundle)
                                val enc = { by: ByteArray -> Base64.encodeToString(by, Base64.NO_WRAP) }
                                x3dhHeader = X3DHHeader(
                                    ikd_pub = enc(PreKeyStore.getIKDPub(this@CreateGroupActivity)),
                                    ek_pub = enc(ekPub),
                                    opk_id = bundle.opkId,
                                    iks_pub = enc(PreKeyStore.getIKSPublicKeyBytes()),
                                )
                            }
                        }
                        val encrypted = RatchetManager.encrypt(this@CreateGroupActivity, contact.deviceId, groupKeyB64)
                        Api.service.sendMessage(
                            SendRequest(
                                sender_device_id = deviceId,
                                recipient_device_id = contact.deviceId,
                                ciphertext = encrypted.ciphertext,
                                nonce = encrypted.nonce,
                                ratchet_pub = encrypted.ratchetPub,
                                message_index = encrypted.messageIndex,
                                prev_send_index = encrypted.prevSendIndex,
                                x3dh_header = x3dhHeader,
                                group_id = groupId,
                                message_type = "group_key",
                            ),
                            token,
                        )
                    } catch (_: Exception) {}
                }

                // 5. Open group chat
                val intent = Intent(this@CreateGroupActivity, GroupChatActivity::class.java).apply {
                    putExtra("group_id", groupId)
                    putExtra("group_name", name)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
                finish()
            } catch (_: Exception) {
                Toast.makeText(this@CreateGroupActivity, "Server nicht erreichbar", Toast.LENGTH_SHORT).show()
                binding.buttonCreate.isClickable = true
            }
        }
    }
}

class SelectContactAdapter(private val contacts: List<Contact>) :
    RecyclerView.Adapter<SelectContactAdapter.Holder>() {

    private val selected = mutableSetOf<String>()

    fun getSelected(): List<Contact> = contacts.filter { it.deviceId in selected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_select_contact, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val contact = contacts[position]
        holder.textName.text = contact.displayName
        holder.textAvatar.text = contact.displayName.firstOrNull()?.uppercase() ?: "?"
        holder.checkbox.isChecked = contact.deviceId in selected
        holder.itemView.setOnClickListener {
            if (contact.deviceId in selected) selected.remove(contact.deviceId)
            else selected.add(contact.deviceId)
            notifyItemChanged(position)
        }
        holder.checkbox.setOnClickListener {
            if (contact.deviceId in selected) selected.remove(contact.deviceId)
            else selected.add(contact.deviceId)
            notifyItemChanged(position)
        }
    }

    override fun getItemCount() = contacts.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val textName: TextView = view.findViewById(R.id.textName)
        val textAvatar: TextView = view.findViewById(R.id.textAvatar)
        val checkbox: CheckBox = view.findViewById(R.id.checkbox)
    }
}
