package com.vitorpamplona.amethyst.model

import android.content.res.Resources
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.LiveData
import com.vitorpamplona.amethyst.service.model.*
import com.vitorpamplona.amethyst.service.relays.*
import com.vitorpamplona.amethyst.service.relays.Constants.defaultSearchRelays
import kotlinx.coroutines.*
import nostr.postr.Contact
import nostr.postr.Persona
import nostr.postr.Utils
import nostr.postr.events.*
import nostr.postr.toHex
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

val DefaultChannels = setOf(
    "25e5c82273a271cb1a840d0060391a0bf4965cafeb029d5ab55350b418953fbb", // -> Anigma's Nostr
    "42224859763652914db53052103f0b744df79dfc4efef7e950fc0802fc3df3c5"  // -> Amethyst's Group
)

fun getLanguagesSpokenByUser(): Set<String> {
    val languageList = ConfigurationCompat.getLocales(Resources.getSystem().getConfiguration())
    val codedList = mutableSetOf<String>()
    for (i in 0 until languageList.size()) {
        languageList.get(i)?.let { codedList.add(it.language) }
    }
    return codedList
}

class Account(
    val loggedIn: Persona,
    var followingChannels: Set<String> = DefaultChannels,
    var hiddenUsers: Set<String> = setOf(),
    var localRelays: Set<RelaySetupInfo> = Constants.defaultRelays.toSet(),
    var dontTranslateFrom: Set<String> = getLanguagesSpokenByUser(),
    var languagePreferences: Map<String, String> = mapOf(),
    var translateTo: String = Locale.getDefault().language,
    var zapAmountChoices: List<Long> = listOf(500L, 1000L, 5000L),
    var backupContactList: ContactListEvent? = null
) {
    var transientHiddenUsers: Set<String> = setOf()

    // Observers line up here.
    val live: AccountLiveData = AccountLiveData(this)
    val liveLanguages: AccountLiveData = AccountLiveData(this)
    val saveable: AccountLiveData = AccountLiveData(this)

    fun userProfile(): User {
        return LocalCache.getOrCreateUser(loggedIn.pubKey.toHexKey())
    }

    fun followingChannels(): List<Channel> {
        return followingChannels.map { LocalCache.getOrCreateChannel(it) }
    }

    fun hiddenUsers(): List<User> {
        return (hiddenUsers + transientHiddenUsers).map { LocalCache.getOrCreateUser(it) }
    }

    fun isWriteable(): Boolean {
        return loggedIn.privKey != null
    }

    fun sendNewRelayList(relays: Map<String, ContactListEvent.ReadWrite>) {
        if (!isWriteable()) return

        val contactList = userProfile().latestContactList

        if (contactList != null && contactList.follows.size > 0) {
            val event = ContactListEvent.create(
                contactList.follows,
                relays,
                loggedIn.privKey!!
            )

            Client.send(event)
            LocalCache.consume(event)
        } else {
            val event = ContactListEvent.create(listOf(), relays, loggedIn.privKey!!)

            // Keep this local to avoid erasing a good contact list.
            // Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun sendNewUserMetadata(toString: String) {
        if (!isWriteable()) return

        loggedIn.privKey?.let {
            val createdAt = Date().time / 1000
            val content = toString
            val pubKey = Utils.pubkeyCreate(it)
            val tags = listOf<List<String>>()
            val id = Event.generateId(pubKey, createdAt, MetadataEvent.kind, tags, content)
            val sig = Utils.sign(id, it)
            val event = MetadataEvent(id, pubKey, createdAt, tags, content, sig)

            Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun reactionTo(note: Note): List<Note> {
        return note.reactedBy(userProfile(), "+")
    }

    fun hasBoosted(note: Note): Boolean {
        return boostsTo(note).isNotEmpty()
    }

    fun boostsTo(note: Note): List<Note> {
        return note.boostedBy(userProfile())
    }

    fun hasReacted(note: Note): Boolean {
        return note.hasReacted(userProfile(), "+")
    }

    fun reactTo(note: Note) {
        if (!isWriteable()) return

        if (hasReacted(note)) {
            // has already liked this note
            return
        }

        note.event?.let {
            val event = ReactionEvent.createLike(it, loggedIn.privKey!!)
            Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun createZapRequestFor(note: Note): LnZapRequestEvent? {
        if (!isWriteable()) return null

        note.event?.let {
            return LnZapRequestEvent.create(
                it,
                userProfile().relays?.keys?.ifEmpty { null } ?: localRelays.map { it.url }.toSet(),
                loggedIn.privKey!!
            )
        }

        return null
    }

    fun createZapRequestFor(user: User): LnZapRequestEvent? {
        return createZapRequestFor(user.pubkeyHex)
    }

    fun createZapRequestFor(userPubKeyHex: String): LnZapRequestEvent? {
        if (!isWriteable()) return null

        return LnZapRequestEvent.create(
            userPubKeyHex,
            userProfile().relays?.keys?.ifEmpty { null } ?: localRelays.map { it.url }.toSet(),
            loggedIn.privKey!!
        )
    }

    fun report(note: Note, type: ReportEvent.ReportType) {
        if (!isWriteable()) return

        if (note.hasReacted(userProfile(), "⚠️")) {
            // has already liked this note
            return
        }

        note.event?.let {
            val event = ReactionEvent.createWarning(it, loggedIn.privKey!!)
            Client.send(event)
            LocalCache.consume(event)
        }

        note.event?.let {
            val event = ReportEvent.create(it, type, loggedIn.privKey!!)
            Client.send(event)
            LocalCache.consume(event, null)
        }
    }

    fun report(user: User, type: ReportEvent.ReportType) {
        if (!isWriteable()) return

        if (user.hasReport(userProfile(), type)) {
            // has already reported this note
            return
        }

        val event = ReportEvent.create(user.pubkeyHex, type, loggedIn.privKey!!)
        Client.send(event)
        LocalCache.consume(event, null)
    }

    fun delete(note: Note) {
        delete(listOf(note))
    }

    fun delete(notes: List<Note>) {
        if (!isWriteable()) return

        val myNotes = notes.filter { it.author == userProfile() }.map { it.idHex }

        if (myNotes.isNotEmpty()) {
            val event = DeletionEvent.create(myNotes, loggedIn.privKey!!)
            Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun boost(note: Note) {
        if (!isWriteable()) return

        if (note.hasBoostedInTheLast5Minutes(userProfile())) {
            // has already bosted in the past 5mins
            return
        }

        note.event?.let {
            val event = RepostEvent.create(it, loggedIn.privKey!!)
            Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun broadcast(note: Note) {
        note.event?.let {
            Client.send(it)
        }
    }

    fun follow(user: User) {
        if (!isWriteable()) return

        val contactList = userProfile().latestContactList

        val event = if (contactList != null && contactList.follows.size > 0) {
            ContactListEvent.create(
                contactList.follows.plus(Contact(user.pubkeyHex, null)),
                userProfile().relays,
                loggedIn.privKey!!
            )
        } else {
            val relays = Constants.defaultRelays.associate {
                it.url to ContactListEvent.ReadWrite(
                    it.read,
                    it.write
                )
            }
            ContactListEvent.create(
                listOf(Contact(user.pubkeyHex, null)),
                relays,
                loggedIn.privKey!!
            )
        }

        Client.send(event)
        LocalCache.consume(event)
    }

    fun unfollow(user: User) {
        if (!isWriteable()) return

        val contactList = userProfile().latestContactList

        if (contactList != null && contactList.follows.size > 0) {
            val event = ContactListEvent.create(
                contactList.follows.filter { it.pubKeyHex != user.pubkeyHex },
                userProfile().relays,
                loggedIn.privKey!!
            )

            Client.send(event)
            LocalCache.consume(event)
        }
    }

    fun sendPost(message: String, replyTo: List<Note>?, mentions: List<User>?) {
        if (!isWriteable()) return

        val repliesToHex = replyTo?.map { it.idHex }
        val mentionsHex = mentions?.map { it.pubkeyHex }

        val signedEvent = TextNoteEvent.create(
            msg = message,
            replyTos = repliesToHex,
            mentions = mentionsHex,
            privateKey = loggedIn.privKey!!
        )
        Client.send(signedEvent)
        LocalCache.consume(signedEvent)
    }

    fun sendChannelMeesage(
        message: String,
        toChannel: String,
        replyingTo: Note? = null,
        mentions: List<User>?
    ) {
        if (!isWriteable()) return

        val repliesToHex = listOfNotNull(replyingTo?.idHex).ifEmpty { null }
        val mentionsHex = mentions?.map { it.pubkeyHex }

        val signedEvent = ChannelMessageEvent.create(
            message = message,
            channel = toChannel,
            replyTos = repliesToHex,
            mentions = mentionsHex,
            privateKey = loggedIn.privKey!!
        )
        Client.send(signedEvent)
        LocalCache.consume(signedEvent, null)
    }

    fun createPrivateMessageWithReply(
        recipientPubKey: ByteArray,
        msg: String,
        replyTos: List<String>? = null, mentions: List<String>? = null,
        privateKey: ByteArray,
        createdAt: Long = Date().time / 1000,
        publishedRecipientPubKey: ByteArray? = null,
        advertiseNip18: Boolean = true
    ): PrivateDmEvent {
        val content = Utils.encrypt(
            if (advertiseNip18) {
                PrivateDmEvent.nip18Advertisement
            } else {
                ""
            } + msg,
            privateKey,
            recipientPubKey
        )
        val pubKey = Utils.pubkeyCreate(privateKey)
        val tags = mutableListOf<List<String>>()
        publishedRecipientPubKey?.let {
            tags.add(listOf("p", publishedRecipientPubKey.toHex()))
        }
        replyTos?.forEach {
            tags.add(listOf("e", it))
        }
        mentions?.forEach {
            tags.add(listOf("p", it))
        }
        val id = Event.generateId(pubKey, createdAt, PrivateDmEvent.kind, tags, content)
        val sig = Utils.sign(id, privateKey)
        return PrivateDmEvent(id, pubKey, createdAt, tags, content, sig)
    }

    fun sendPrivateMeesage(message: String, toUser: String, replyingTo: Note? = null) {
        if (!isWriteable()) return
        val user = LocalCache.users[toUser] ?: return

        val repliesToHex = listOfNotNull(replyingTo?.idHex).ifEmpty { null }
        val mentionsHex = emptyList<String>()

        val signedEvent = createPrivateMessageWithReply(
            recipientPubKey = user.pubkey(),
            publishedRecipientPubKey = user.pubkey(),
            msg = message,
            replyTos = repliesToHex,
            mentions = mentionsHex,
            privateKey = loggedIn.privKey!!,
            advertiseNip18 = false
        )
        Client.send(signedEvent)
        LocalCache.consume(signedEvent, null)
    }

    fun sendCreateNewChannel(name: String, about: String, picture: String) {
        if (!isWriteable()) return

        val metadata = ChannelCreateEvent.ChannelData(
            name, about, picture
        )

        val event = ChannelCreateEvent.create(
            channelInfo = metadata,
            privateKey = loggedIn.privKey!!
        )

        Client.send(event)
        LocalCache.consume(event)

        joinChannel(event.id.toHex())
    }

    fun joinChannel(idHex: String) {
        followingChannels = followingChannels + idHex
        live.invalidateData()

        saveable.invalidateData()
    }

    fun leaveChannel(idHex: String) {
        followingChannels = followingChannels - idHex
        live.invalidateData()

        saveable.invalidateData()
    }

    fun hideUser(pubkeyHex: String) {
        hiddenUsers = hiddenUsers + pubkeyHex
        live.invalidateData()
        saveable.invalidateData()
    }

    fun showUser(pubkeyHex: String) {
        hiddenUsers = hiddenUsers - pubkeyHex
        transientHiddenUsers = transientHiddenUsers - pubkeyHex
        live.invalidateData()
        saveable.invalidateData()
    }

    fun changeZapAmounts(newAmounts: List<Long>) {
        zapAmountChoices = newAmounts
        live.invalidateData()
        saveable.invalidateData()
    }

    fun sendChangeChannel(name: String, about: String, picture: String, channel: Channel) {
        if (!isWriteable()) return

        val metadata = ChannelCreateEvent.ChannelData(
            name, about, picture
        )

        val event = ChannelMetadataEvent.create(
            newChannelInfo = metadata,
            originalChannelIdHex = channel.idHex,
            privateKey = loggedIn.privKey!!
        )

        Client.send(event)
        LocalCache.consume(event)

        joinChannel(event.id.toHex())
    }

    fun decryptContent(note: Note): String? {
        val event = note.event
        return if (event is PrivateDmEvent && loggedIn.privKey != null) {
            var pubkeyToUse = event.pubKey

            val recepientPK = event.recipientPubKey

            if (note.author == userProfile() && recepientPK != null)
                pubkeyToUse = recepientPK

            return try {
                val sharedSecret = Utils.getSharedSecret(loggedIn.privKey!!, pubkeyToUse)

                val retVal = Utils.decrypt(event.content, sharedSecret)

                if (retVal.startsWith(PrivateDmEvent.nip18Advertisement)) {
                    retVal.substring(16)
                } else {
                    retVal
                }

            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            event?.content
        }
    }

    fun addDontTranslateFrom(languageCode: String) {
        dontTranslateFrom = dontTranslateFrom.plus(languageCode)
        liveLanguages.invalidateData()

        saveable.invalidateData()
    }

    fun updateTranslateTo(languageCode: String) {
        translateTo = languageCode
        liveLanguages.invalidateData()

        saveable.invalidateData()
    }

    fun prefer(source: String, target: String, preference: String) {
        languagePreferences = languagePreferences + Pair("$source,$target", preference)
        saveable.invalidateData()
    }

    fun preferenceBetween(source: String, target: String): String? {
        return languagePreferences.get("$source,$target")
    }

    private fun updateContactListTo(newContactList: ContactListEvent?) {
        if (newContactList?.follows.isNullOrEmpty()) return

        // Events might be different objects, we have to compare their ids.
        if (backupContactList?.id?.toHex() != newContactList?.id?.toHex()) {
            backupContactList = newContactList
            saveable.invalidateData()
        }
    }

    fun activeRelays(): Array<Relay>? {
        return userProfile().relays?.map {
            val localFeedTypes =
                localRelays.firstOrNull() { localRelay -> localRelay.url == it.key }?.feedTypes
                    ?: FeedType.values().toSet()
            Relay(it.key, it.value.read, it.value.write, localFeedTypes)
        }?.toTypedArray()
    }

    fun convertLocalRelays(): Array<Relay> {
        return localRelays.map {
            Relay(it.url, it.read, it.write, it.feedTypes)
        }.toTypedArray()
    }

    fun reconnectIfRelaysHaveChanged() {
        val newRelaySet = activeRelays() ?: convertLocalRelays()
        if (!Client.isSameRelaySetConfig(newRelaySet)) {
            Client.disconnect()
            Client.connect(newRelaySet, defaultSearchRelays)
            RelayPool.requestAndWatch()
        }
    }

    fun isHidden(user: User) =
        user.pubkeyHex in hiddenUsers || user.pubkeyHex in transientHiddenUsers

    fun isAcceptable(user: User): Boolean {
        return !isHidden(user)  // if user hasn't hided this author
                && user.reportsBy(userProfile()).isEmpty() // if user has not reported this post
                && user.reportAuthorsBy(userProfile().follows).size < 5
    }

    fun isAcceptableDirect(note: Note): Boolean {
        return note.reportsBy(userProfile()).isEmpty()  // if user has not reported this post
                && note.reportAuthorsBy(userProfile().follows).size < 5 // if it has 5 reports by reliable users
    }

    fun isAcceptable(note: Note): Boolean {
        return note.author?.let { isAcceptable(it) } ?: true // if user hasn't hided this author
                && isAcceptableDirect(note)
                && (note.event !is RepostEvent
                || (note.event is RepostEvent && note.replyTo?.firstOrNull { isAcceptableDirect(it) } != null)
                ) // is not a reaction about a blocked post
    }

    fun getRelevantReports(note: Note): Set<Note> {
        val followsPlusMe = userProfile().follows + userProfile()

        val innerReports = if (note.event is RepostEvent) {
            note.replyTo?.map { getRelevantReports(it) }?.flatten() ?: emptyList()
        } else {
            emptyList()
        }

        return (note.reportsBy(followsPlusMe) +
                (note.author?.reportsBy(followsPlusMe) ?: emptyList()) +
                innerReports).toSet()
    }

    fun saveRelayList(value: List<RelaySetupInfo>) {
        localRelays = value.toSet()
        sendNewRelayList(value.associate {
            it.url to ContactListEvent.ReadWrite(
                it.read,
                it.write
            )
        })

        saveable.invalidateData()
    }

    init {
        backupContactList?.let {
            println("Loading saved contacts ${it.toJson()}")
            if (userProfile().latestContactList == null) {
                LocalCache.consume(it)
            }
        }

        // Observes relays to restart connections
        userProfile().live().relays.observeForever {
            GlobalScope.launch(Dispatchers.IO) {
                reconnectIfRelaysHaveChanged()
            }
        }

        // saves contact list for the next time.
        userProfile().live().follows.observeForever {
            updateContactListTo(userProfile().latestContactList)
        }

        // imports transient blocks due to spam.
        LocalCache.antiSpam.liveSpam.observeForever {
            GlobalScope.launch(Dispatchers.IO) {
                it.cache.spamMessages.snapshot().values.forEach {
                    if (it.pubkeyHex !in transientHiddenUsers && it.duplicatedMessages.size >= 5) {
                        val userToBlock = LocalCache.getOrCreateUser(it.pubkeyHex)
                        if (userToBlock != userProfile() && userToBlock !in userProfile().follows) {
                            transientHiddenUsers = transientHiddenUsers + it.pubkeyHex
                        }
                    }
                }
            }
        }
    }
}

class AccountLiveData(private val account: Account) :
    LiveData<AccountState>(AccountState(account)) {
    var handlerWaiting = AtomicBoolean()

    @Synchronized
    fun invalidateData() {
        if (handlerWaiting.getAndSet(true)) return

        handlerWaiting.set(true)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        scope.launch {
            try {
                delay(100)
                refresh()
            } finally {
                withContext(NonCancellable) {
                    handlerWaiting.set(false)
                }
            }
        }
    }

    fun refresh() {
        postValue(AccountState(account))
    }
}

class AccountState(val account: Account)