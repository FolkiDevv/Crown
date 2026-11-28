package io.nekohasekai.sagernet.simple.servers

import android.app.Application
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.simple.config.SimpleConfigRepo
import io.nekohasekai.sagernet.simple.modes.Managed
import io.nekohasekai.sagernet.simple.modes.TrafficModeApplier
import io.nekohasekai.sagernet.simple.modes.TrafficModes
import io.nekohasekai.sagernet.simple.subs.Notice
import io.nekohasekai.sagernet.simple.subs.NoticeLevel
import io.nekohasekai.sagernet.simple.subs.SubscriptionFacts
import io.nekohasekai.sagernet.simple.subs.SubscriptionNotices
import io.nekohasekai.sagernet.simple.subs.SubscriptionState
import io.nekohasekai.sagernet.ui.test.RowPhase
import io.nekohasekai.sagernet.ui.test.TestFailure
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.ui.test.TestUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the card of one subscription shows (simple-ui spec "Сведения о подписке"). */
data class SubscriptionCard(
    val groupId: Long,
    val name: String,
    /** Bytes left, null when the provider reports no quota or an unlimited one. */
    val remaining: Long?,
    val total: Long,
    val traffic: SubscriptionHealth.Traffic,
    /** Epoch seconds, 0 when not reported. */
    val expire: Long,
    val expiry: SubscriptionHealth.Expiry,
    /** Epoch seconds of the last successful update, 0 = never. */
    val lastUpdate: Long,
    val refreshing: Boolean,
    /** The subscription link (for «Поделиться ссылкой»). */
    val url: String = "",
    /** Its servers in the list (placeholders aside). */
    val servers: Int = 0,
    /** What its messages need (simple-ui "Сообщения подписки"): the provider's pages and announcement. */
    val facts: SubscriptionFacts? = null,
    /** A red message: the VPN cannot work through this subscription; its countries are hidden then. */
    val blocked: Notice? = null,
)

/** «Поделиться»: [link] as a QR code and to copy, with [warning] about who may use it. */
data class ShareRequest(@StringRes val title: Int, val name: String, val link: String, @StringRes val warning: Int)

/** The server the running auto-selector picked, for «Авто» or the chosen country. */
data class RunningPick(val selectorId: Long, val name: String, val country: String?)

data class ServersUiState(
    /** null until the first read. */
    val sections: ServerSections? = null,
    val cards: Map<Long, SubscriptionCard> = emptyMap(),
    val selected: Long = 0L,
    val managed: Managed = Managed(0L, 0L, 0L, 0L),
    val choice: CountryChoice? = null,
    val pick: RunningPick? = null,
    /** The pull-to-refresh indicator: a manual update of the subscriptions runs. */
    val refreshing: Boolean = false,
    /** A choice is being applied. */
    val busy: Boolean = false,
    /** The advanced mode: the menus also lead to the classic editors. */
    val advanced: Boolean = false,
    /** The share dialog, null while it is closed. */
    val share: ShareRequest? = null,
) {
    val autoSelected: Boolean get() = selected > 0L && (selected == managed.auto || selected == managed.autoWhitelist)
    val countrySelected: Boolean get() = selected > 0L && selected == managed.country && choice != null
}

/** A snackbar text: [text] with [arg] as its only argument, if any, and an optional action. */
data class ServersMessage(
    @StringRes val text: Int,
    val arg: String? = null,
    /** A snackbar action ([actionLabel]) and what it does. */
    @StringRes val actionLabel: Int = 0,
    val action: (() -> Unit)? = null,
)

/**
 * The «Серверы» section (simple-ui spec "Структура списка серверов", "Выбор сервера в списке", "Определение страны
 * сервера", "Сведения о подписке"): the list read from the database, the choice of «Авто», a country or a server
 * (applied to a running connection at once), the manual update of subscriptions, and the background checks run while
 * the section is shown (simple-ui "Фоновая проверка серверов") — a URL test of what the list needs ([RoundPlan]), then
 * an exit IP check of those servers whose name names no country. Both run as ordinary test sessions of `:bg`
 * (TestSessionClient), which keeps their results in the profiles; this section's own session stops once the section is
 * hidden for [HIDE_STOP_MS], and what it left is tested at the next show ([RoundRecord], kept on disk).
 */
class ServersViewModel(application: Application) : AndroidViewModel(application), ProfileManager.Listener {

    private val mutableState = MutableStateFlow(ServersUiState())
    val state: StateFlow<ServersUiState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<ServersMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<ServersMessage> = mutableMessages.asSharedFlow()

    private val mutableEvents = MutableSharedFlow<ServersEvent>(extraBufferCapacity = 4)

    /** For the activity: the other sections follow a choice or an update. */
    val events: SharedFlow<ServersEvent> = mutableEvents.asSharedFlow()

    private var loadJob: Job? = null
    private val choosing = Mutex()

    /** Subscription ids being updated (SubscriptionClient's queue). */
    private var updating: Set<Long> = emptySet()

    /** The subscriptions this section asked to update, with the time of the request (elapsed realtime). */
    private val requested = HashMap<Long, Long>()

    /** Those of [requested] that pull to refresh asked for. */
    private val pulled = HashSet<Long>()
    private var pullUntil = 0L

    /** The background test session this section started, and the state before it (to tell its states apart). */
    private var ownTest: Int = NONE
    private var stateBeforeTest: TestUiState? = null

    /** [TestUiState.startedAt] of that session: tells it from a session another screen started afterwards. */
    private var ownStartedAt = 0L

    /** The servers of that session. */
    private var sessionIds: List<Long> = emptyList()

    /** The last round (kept in `simple_ui`); null until the first [reload] has read it. */
    private var record: RoundRecord? = null

    /** The section is on screen: composed while the activity is started ([shown]). */
    private var visible = false
    private var hideJob: Job? = null

    /** The add flow asked to test the new servers: that test runs even while the section is hidden. */
    private var addedTest = false

    /** Server rows the user scrolled to or opened, to test next ([serverShown]); each row is asked for once a round. */
    private val wanted = LinkedHashSet<Long>()
    private val shownAsked = HashSet<Long>()
    private var wantJob: Job? = null

    /**
     * The servers whose country this section has tried to find once since it was created (a URL test, then an exit
     * IP check if they answered), whatever the outcome; later rounds check them again without «Страна определяется…».
     */
    private val countryChecked = HashSet<Long>()

    /** Servers of the current round shown as «Страна определяется…» until the round is through. */
    private val roundPending = HashSet<Long>()

    /** Servers whose exit IP was checked at least once (they come after the others in the next batch). */
    private val ipAttempted = HashSet<Long>()

    /** Servers added while a round ran, tested right after it ([testAgain]). */
    private val queued = HashSet<Long>()
    private var testAgain = false

    /**
     * A country round runs: from the start of its URL test until its exit IP check (if any) is through. A test asked
     * for meanwhile waits for the round ([queued]) instead of starting one the ending round would then overwrite.
     */
    private var roundBusy = false

    /** A [reload] asked to test afterwards. */
    private var testAfterLoad = false
    private var detecting: Set<Long> = emptySet()

    /** Live results of the running URL test, until the database has them. */
    private val liveLatency = HashMap<Long, Int>()

    private var autoStatus = AutoSelectorStatus.IDLE
    /** The running pick as the list shows it: (profile id, (country, name)). */
    private var pickInfo: Pair<Long, Pair<String?, String>>? = null

    /** The pick lookup under way; a newer one cancels it, so an older result never overwrites a fresher one. */
    private var pickJob: Job? = null

    init {
        ProfileManager.addListener(this)
        viewModelScope.launch {
            SubscriptionClient.states.collect { states -> onSubscriptionStates(states.keys) }
        }
        viewModelScope.launch {
            SubscriptionClient.reports.collect { report ->
                if (report.error && requested.remove(report.gid) != null) {
                    publishCards()
                    mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_update_failed, report.text.trim()))
                }
            }
        }
        viewModelScope.launch {
            TestSessionClient.state.collect(::onTestState)
        }
        viewModelScope.launch {
            AutoSelectorClient.status.collect { status ->
                autoStatus = status
                publishPick()
            }
        }
        reload()
    }

    // ------------------------------------------------------------------------------------------------ screen events

    /**
     * The section is shown ([on]: composed while the activity is started) or hidden. Shown, the list is read again and
     * the background checks run (a new round at most every [TEST_INTERVAL_MS]); hidden for [HIDE_STOP_MS], the
     * section's own session stops and nothing more is started until it is shown again.
     */
    fun shown(on: Boolean) {
        if (on == visible) return
        visible = on
        hideJob?.cancel()
        if (on) {
            reload(thenTest = true)
            return
        }
        hideJob = viewModelScope.launch {
            delay(HIDE_STOP_MS)
            stopOwnTest()
        }
    }

    /** The add flow added servers: they are tested now, even while the section is hidden (simple-ui 5.3). */
    fun testAdded() {
        // A hide just before must not stop this test.
        hideJob?.cancel()
        addedTest = true
        reload(thenTest = true)
    }

    /** A server row came into view (a country opened, the list scrolled): tested soon unless this round covered it. */
    fun serverShown(id: Long) {
        if (!visible || !shownAsked.add(id)) return
        wanted += id
        if (wantJob?.isActive == true) return
        wantJob = viewModelScope.launch {
            // Rows come into view a few at a time: one test for those of the moment.
            delay(SHOWN_DELAY_MS)
            loadJob?.join()
            startUrlTest()
        }
    }

    /** Reads the list again (another section or the classic interface may have changed it). */
    fun reload(thenTest: Boolean = false) {
        // A newer read replaces an older one, but not its request to test afterwards.
        if (thenTest) testAfterLoad = true
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val stored = record
            val (loaded, read) = withContext(Dispatchers.IO) {
                try {
                    load() to (stored ?: readRecord())
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            } ?: return@launch
            if (record == null) record = read
            // The live results of a running test go over the stored ones here, on the main thread that owns them.
            val live = loaded.copy(sections = loaded.sections?.let(::withLiveLatency))
            mutableState.update { live.copy(pick = it.pick, refreshing = it.refreshing, busy = it.busy, share = it.share) }
            publishCards()
            // Names and countries may have changed (an update in :bg): the running pick is looked up again.
            pickInfo = null
            publishPick()
            if (testAfterLoad) {
                testAfterLoad = false
                startUrlTest()
            }
        }
    }

    fun chooseAuto() = choose {
        val auto = mutableState.value.managed.auto.takeIf { it > 0L }
            ?: withContext(Dispatchers.IO) { TrafficModeApplier.ensure(getApplication()).ids.auto }
        if (auto <= 0L) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
            return@choose
        }
        TrafficModeApplier.selectServer(auto)
    }

    fun chooseCountry(row: CountryRow) {
        val code = row.code ?: return
        choose {
            // 0: no server of that country can be told apart by name, or the selector could not be made.
            if (TrafficModeApplier.selectCountry(getApplication(), CountryChoice(row.groupId, code)) <= 0L) {
                mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
            }
        }
    }

    fun chooseServer(server: ServerEntry) = choose { TrafficModeApplier.selectServer(server.id) }

    /** Updates one subscription now (the card's button). */
    fun update(groupId: Long) = update(groupId, pulled = false)

    /** [pulled]: asked for by pull to refresh, whose indicator then follows this update. */
    private fun update(groupId: Long, pulled: Boolean) {
        // An update that is queued or running already is not asked for again (repeated pulls); a pull still shows
        // its indicator until that update is through.
        if (groupId in updating || groupId in requested) {
            if (pulled) {
                requested.putIfAbsent(groupId, SystemClock.elapsedRealtime())
                this.pulled += groupId
            }
            return
        }
        requested[groupId] = SystemClock.elapsedRealtime()
        if (pulled) this.pulled += groupId
        SubscriptionClient.refreshGroup(groupId, false)
        publishCards()
        // Until the queue reports it, the card shows the update for a moment; then it follows the queue.
        viewModelScope.launch {
            delay(PULL_GRACE_MS)
            publishCards()
        }
    }

    /** Pull to refresh: updates every subscription of the list. */
    fun updateAll() {
        val ids = mutableState.value.cards.keys
        if (ids.isEmpty()) {
            reload(thenTest = true)
            return
        }
        // The indicator shows at once and stays until the queue reports the updates (or briefly, if it never does).
        pullUntil = SystemClock.elapsedRealtime() + PULL_GRACE_MS
        mutableState.update { it.copy(refreshing = true) }
        ids.forEach { update(it, pulled = true) }
        publishCards()
        // Every deadline gets its publication: the indicator goes away then unless a pulled update still runs.
        viewModelScope.launch {
            delay(PULL_GRACE_MS)
            publishCards()
        }
    }

    override fun onCleared() {
        // The activity is finishing (not rotating): its pending hide never fires, so the own session stops now.
        stopOwnTest()
        ProfileManager.removeListener(this)
        super.onCleared()
    }

    // ------------------------------------------------------------------------------------------------ management

    /** «Переименовать» of a subscription: its own name, which later updates keep (Profile-Title names only a new one). */
    fun renameSubscription(groupId: Long, name: String) = manage {
        val group = GroupRepo.get(groupId) ?: return@manage
        val text = name.trim().takeIf { it.isNotEmpty() && it != group.name } ?: return@manage
        // Only the name: the rest of the row is :bg's to write (an update may be storing its metadata meanwhile).
        SagerDatabase.instance.openHelper.writableDatabase
            .execSQL("UPDATE `groups` SET `name` = ? WHERE `id` = ?", arrayOf<Any>(text, groupId))
        GroupRepo.postUpdate(groupId)
        // Home shows the subscription by its name too.
        mutableEvents.tryEmit(ServersEvent.Updated)
    }

    /** «Удалить подписку» (after its dialog): the group and its servers; «Авто» follows the groups left. */
    fun deleteSubscription(groupId: Long) = manage {
        val name = GroupRepo.get(groupId)?.displayName() ?: return@manage
        if (GroupRepo.delete(groupId)) {
            SubscriptionState.forget(groupId)
            // A running «Авто» may still connect through its servers.
            TrafficModeApplier.serversChanged()
            mutableMessages.tryEmit(ServersMessage(R.string.simple_sub_deleted, name))
            mutableEvents.tryEmit(ServersEvent.Updated)
        } else {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_delete_failed))
        }
    }

    /** «Переименовать» of an own server. */
    fun renameServer(id: Long, name: String) = manage {
        val profile = ProfileManager.getProfile(id) ?: return@manage
        val text = name.trim().takeIf { it.isNotEmpty() && it != profile.outbound.name } ?: return@manage
        val outbound = profile.outbound
        outbound.name = text
        ProfileManager.updateOutbound(profile.putOutbound(outbound))
        mutableEvents.tryEmit(ServersEvent.Chosen)
    }

    /** «Удалить» of an own server: at once, with «Вернуть» in the snackbar (a new copy, chosen again if it was). */
    fun deleteServer(id: Long) = manage {
        val profile = ProfileManager.getProfile(id) ?: return@manage
        val wasSelected = DataStore.selectedProxy == id
        val name = ServerCatalog.nameOf(profile, getApplication<Application>().getString(R.string.simple_server_unnamed))
        // The running server itself stops with it (as a subscription update deleting it does); any other deletion leaves
        // the connection, and a running «Авто» is rebuilt without it.
        val outcome = ProfileManager.batchDeleteProfiles(listOf(id), stopRunning = true)
        if (!outcome.ok || id !in outcome.deleted) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_delete_failed))
            return@manage
        }
        TrafficModeApplier.serversChanged()
        mutableEvents.tryEmit(ServersEvent.Chosen)
        mutableMessages.tryEmit(
            ServersMessage(R.string.simple_server_deleted, name, R.string.simple_server_undo) {
                manage {
                    val copy = ProfileManager.createProfile(profile.groupId, profile.outbound)
                    TrafficModeApplier.serverRestored(copy.id, wasSelected)
                    mutableEvents.tryEmit(ServersEvent.Chosen)
                }
            }
        )
    }

    /** «Проверить задержку» of one server, unless a check of this section or another one runs. */
    fun testServer(id: Long) {
        if (ownTest != NONE || roundBusy) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_server_test_busy))
            return
        }
        val before = TestSessionClient.state.value
        if (!TestSessionClient.startProfiles(TestSpec.KIND_URL, listOf(id))) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_server_test_busy))
            return
        }
        owned(TestSpec.KIND_URL, listOf(id), before)
        roundBusy = true
        liveLatency.clear()
    }

    /** «Поделиться ссылкой» of a subscription. */
    fun shareSubscription(card: SubscriptionCard) {
        if (card.url.isBlank()) return
        mutableState.update {
            it.copy(share = ShareRequest(R.string.simple_share_sub_title, card.name, card.url.trim(), R.string.simple_share_sub_warning))
        }
    }

    /** «Поделиться» of an own server: its link, when its type has one. */
    fun shareServer(id: Long) = manage {
        val profile = ProfileManager.getProfile(id) ?: return@manage
        val link = profile.exportLink()
        if (link.isEmpty()) {
            mutableMessages.tryEmit(ServersMessage(R.string.simple_share_no_link))
            return@manage
        }
        val name = ServerCatalog.nameOf(profile, getApplication<Application>().getString(R.string.simple_server_unnamed))
        mutableState.update {
            it.copy(share = ShareRequest(R.string.simple_share_server_title, name, link, R.string.simple_share_server_warning))
        }
    }

    /** A classic editor saved a change the running connection must take. */
    fun settingsChanged() = manage { TrafficModeApplier.settingsChanged() }

    fun closeShare() = mutableState.update { it.copy(share = null) }

    /** A change of the user's in the database, then the list read again. */
    private fun manage(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                Logs.w("simple servers: management failed", e)
                mutableMessages.tryEmit(ServersMessage(R.string.simple_change_failed))
            }
            reload()
        }
    }

    // ------------------------------------------------------------------------------------------------ choice

    private fun choose(block: suspend () -> Unit) {
        if (choosing.isLocked) return
        viewModelScope.launch {
            choosing.withLock {
                mutableState.update { it.copy(busy = true) }
                try {
                    block()
                } catch (e: Exception) {
                    Logs.w("simple servers: choice failed", e)
                    mutableMessages.tryEmit(ServersMessage(R.string.simple_servers_choose_failed))
                } finally {
                    mutableState.update { it.copy(busy = false) }
                    reload()
                    mutableEvents.tryEmit(ServersEvent.Chosen)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    private fun load(): ServersUiState {
        val context = getApplication<Application>()
        val keywords = SimpleConfigRepo.current().wlKeywords
        val snapshot = ServerCatalog.load(keywords, detecting, context.getString(R.string.simple_server_unnamed))
        val servers = snapshot.servers
        val locale = context.resources.configuration.locales[0]
        val sections = ServerList.build(snapshot.entries, servers) { CountryLabels.name(it, locale) }
        val now = System.currentTimeMillis() / 1000
        val byGroup = servers.groupBy { it.groupId }
        val cards = snapshot.groups.filter { it.isSubscription }.associate { group ->
            val info = group.subInfo
            val valid = info.valid
            val (placeholders, usable) = byGroup[group.id].orEmpty().partition { it.placeholder }
            val facts = SubscriptionState.factsOf(group, usable.size, placeholders.map { it.name })
            group.id to SubscriptionCard(
                url = group.url,
                servers = usable.size,
                facts = facts,
                blocked = SubscriptionNotices.of(facts, now).firstOrNull { it.level == NoticeLevel.BLOCKED },
                groupId = group.id,
                name = group.displayName(),
                remaining = if (valid && info.hasQuota && info.total > 0L) info.remaining else null,
                total = info.total,
                traffic = SubscriptionHealth.traffic(valid && info.hasQuota, info.total, info.used),
                expire = if (valid) info.expire else 0L,
                expiry = SubscriptionHealth.expiry(if (valid) info.expire else 0L, now),
                lastUpdate = group.subLastUpdate,
                refreshing = group.id in updating,
            )
        }
        return ServersUiState(
            sections = sections,
            cards = cards,
            selected = DataStore.selectedProxy,
            managed = TrafficModes.managedIds(),
            choice = TrafficModes.countryChoice(),
            advanced = SimpleUi.isAdvanced(SimpleUi.store(context)),
        )
    }

    // ProfileManager calls these for changes made in this process (the classic editors, the add flow).
    override suspend fun onAdd(profile: ProxyEntity) = reload()
    override suspend fun onUpdated(data: List<TrafficData>) = Unit
    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) = Unit
    override suspend fun onRemoved(groupId: Long, profileId: Long) = reload()

    // ------------------------------------------------------------------------------------------------ subscriptions

    private fun onSubscriptionStates(ids: Set<Long>) {
        val finished = updating - ids
        updating = ids
        if (ids.any { it in pulled }) pullUntil = 0L
        requested.keys.removeAll(finished)
        pulled.removeAll(finished)
        publishCards()
        if (finished.isEmpty()) return
        // The update ran in :bg: the selectors follow the new servers. The list is read again, and the servers the
        // update added are tested, only while the section is shown; hidden, its next show reads and tests them.
        mutableEvents.tryEmit(ServersEvent.Updated)
        if (visible) reload(thenTest = true)
    }

    private fun publishCards() {
        val now = SystemClock.elapsedRealtime()
        val pulling = now < pullUntil
        fun justRequested(id: Long) = requested[id]?.let { now - it < PULL_GRACE_MS } == true
        // A request that never reached the queue (it failed at once) is forgotten after the grace period.
        requested.entries.removeAll { (id, at) -> id !in updating && now - at >= PULL_GRACE_MS }
        pulled.retainAll(requested.keys)
        mutableState.update { state ->
            state.copy(
                cards = state.cards.mapValues { (id, card) -> card.copy(refreshing = id in updating || justRequested(id)) },
                // Only pull to refresh shows the indicator at the top; a card's own button shows its spinner.
                refreshing = pulling || state.cards.keys.any { it in updating && it in pulled },
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ background tests

    /**
     * A round, or within the interval what is left to test ([RoundPlan]). Only while the section is shown, or for the
     * servers the add flow just added ([testAdded]).
     */
    private fun startUrlTest() {
        if (!visible && !addedTest) return
        val sections = mutableState.value.sections ?: return
        val last = record ?: return
        val now = System.currentTimeMillis()
        val fresh = last.due(now, TEST_INTERVAL_MS)
        val ids = if (fresh) {
            (wanted.asSequence() + RoundPlan.round(sections).asSequence()).distinct().take(RoundPlan.LIMIT).toList()
        } else {
            RoundPlan.followUp(sections, last, last.at / 1000, wanted)
        }
        if (ids.isEmpty()) {
            wanted.clear()
            if (ownTest == NONE && !roundBusy) addedTest = false
            return
        }
        if (ownTest != NONE || roundBusy) {
            // A round runs: the servers it does not cover (one just added) are tested right after it, and those whose
            // country is still open are «being detected» meanwhile.
            val running = sessionIds.toHashSet()
            val waiting = sections.allServers
                .filter { it.id in ids && it.id !in running && it.country !is ServerCountry.Known && it.id !in countryChecked }
                .map { it.id }
            testAgain = true
            if (waiting.isNotEmpty() && !detecting.containsAll(waiting)) {
                queued += waiting
                detecting = detecting + waiting
                reload()
            }
            return
        }
        val before = TestSessionClient.state.value
        // Another session (the user's, «Починка»'s) runs: this round is skipped, and nothing is recorded as tested.
        if (!TestSessionClient.startProfiles(TestSpec.KIND_URL, ids)) {
            addedTest = false
            return
        }
        val all = sections.allServers.map { it.id }
        Logs.i("simple servers: background ${if (fresh) "round" else "follow-up"} of ${ids.size} of ${all.size} servers")
        // Every listed server counts as known: those the limit left out wait for their row to show or the next round.
        saveRecord(if (fresh) RoundRecord(now, all.toSet(), ids.toSet()) else last.copy(known = last.known + all, left = last.left + ids))
        if (fresh) shownAsked.retainAll(wanted)
        wanted.removeAll(ids.toSet())
        // Rows asked for beyond the limit are tested next.
        if (wanted.isNotEmpty()) testAgain = true
        owned(TestSpec.KIND_URL, ids, before)
        roundBusy = true
        liveLatency.clear()
        // A server whose country neither the name nor an earlier exit IP check gave is «being detected» until its
        // checks are through, rather than «unknown» at first.
        val pending = mutableState.value.sections?.allServers
            ?.filter { it.id in ids && it.country !is ServerCountry.Known && it.id !in countryChecked }
            ?.map { it.id }.orEmpty()
        if (pending.isNotEmpty()) {
            roundPending += pending
            detecting = detecting + pending
            reload()
        }
    }

    /** The exit IP check of the servers of the URL test just over whose country the name does not give, which answered. */
    private fun startIpTest() {
        if (!visible && !addedTest) {
            // Hidden meanwhile: the round ends here; the next one checks them.
            finishRound()
            return
        }
        val tested = sessionIds.toHashSet()
        val candidates = mutableState.value.sections?.allServers
            ?.filter {
                it.id in tested && it.country !is ServerCountry.Known &&
                    (it.latency > 0 || it.latency == ProxyEntity.LATENCY_CONNECT_ONLY)
            }
            ?.map { it.id }
            .orEmpty()
        // At most IP_TEST_LIMIT per round, those never checked first, so a long list is covered over the rounds.
        val ids = candidates.sortedBy { it in ipAttempted }.take(IP_TEST_LIMIT)
        // Servers left for a later round are not through with their first check yet.
        roundPending.removeAll((candidates - ids.toSet()).toSet())
        val before = TestSessionClient.state.value
        if (ids.isEmpty() || !TestSessionClient.startProfiles(TestSpec.KIND_IP, ids)) {
            // Nothing answered (or another session runs): this round is through, what was detected is unknown.
            finishRound()
            return
        }
        owned(TestSpec.KIND_IP, ids, before)
        ipAttempted += ids
        // The servers that did not answer are through; only those checked for the first time are «being detected».
        countryChecked += roundPending - ids.toSet()
        detecting = ids.filterTo(HashSet()) { it !in countryChecked } + queued
        reload()
    }

    /** The country round is over: its servers count as checked and nothing is «being detected» any more. */
    private fun finishRound() {
        roundBusy = false
        countryChecked += roundPending
        roundPending.clear()
        // Servers queued for the next round stay «being detected».
        val next = queued.toSet()
        if (detecting != next) {
            detecting = next
            reload()
        }
        startQueued()
    }

    /** Tests what was added while the last round ran (see [startUrlTest]). */
    private fun startQueued() {
        if (!testAgain) {
            // The tests the add flow asked for are through.
            addedTest = false
            return
        }
        testAgain = false
        viewModelScope.launch {
            loadJob?.join()
            queued.clear()
            startUrlTest()
            // Nothing to test after all (or another session runs): nothing is «being detected» any more.
            if (ownTest == NONE && detecting.isNotEmpty()) {
                detecting = emptySet()
                reload()
            }
        }
    }

    /** This section's session of [kind] over [ids] has just started; [before] is the state published before it. */
    private fun owned(kind: Int, ids: List<Long>, before: TestUiState) {
        stateBeforeTest = before
        ownTest = kind
        sessionIds = ids
        // Published by the start itself: the session's own stamp.
        ownStartedAt = TestSessionClient.state.value.startedAt
    }

    /**
     * Hidden for [HIDE_STOP_MS]: this section's own session stops (its servers left are tested at the next show) and
     * nothing more starts. A session another screen started (the user's test, «Починка», the classic panel) is left
     * alone: only the one whose stamp is this section's is stopped.
     */
    private fun stopOwnTest() {
        testAgain = false
        addedTest = false
        testAfterLoad = false
        // Rows asked for but not tested yet are asked for again when they show.
        shownAsked.removeAll(wanted)
        wanted.clear()
        wantJob?.cancel()
        val test = TestSessionClient.state.value
        if (ownTest != NONE && test.running && test.kind == ownTest && test.startedAt == ownStartedAt) {
            Logs.i("simple servers: hidden, stopping the background check (${test.done} of ${test.total} done)")
            TestSessionClient.stop()
        }
    }

    private fun readRecord(): RoundRecord =
        RoundRecord.decode(SimpleUi.store(getApplication()).getString(KEY_ROUND))

    private fun saveRecord(value: RoundRecord) {
        record = value
        SimpleUi.store(getApplication()).putString(KEY_ROUND, value.encode())
    }

    private fun onTestState(test: TestUiState) {
        if (ownTest == NONE || test === stateBeforeTest) return
        if (test.kind != ownTest || test.startedAt != ownStartedAt) {
            // Another session (not this section's) runs or ran since, or the panel was cleared: this section's ended
            // unseen, as no other session begins or is cleared while it runs.
            ownTest = NONE
            roundBusy = false
            roundPending.clear()
            queued.clear()
            if (detecting.isNotEmpty()) {
                detecting = emptySet()
                reload()
            }
            return
        }
        if (ownTest == TestSpec.KIND_URL) {
            var changed = false
            for ((id, row) in test.rows) {
                if (row.phase != RowPhase.DONE) continue
                val latency = when {
                    row.ok -> row.latency.takeIf { it != 0 } ?: ProxyEntity.LATENCY_CONNECT_ONLY
                    row.failed -> -1
                    else -> continue
                }
                if (liveLatency.put(id, latency) != latency) changed = true
            }
            if (changed) publishLatency()
        }
        if (test.running) return
        val finished = ownTest
        ownTest = NONE
        stateBeforeTest = null
        if (finished == TestSpec.KIND_URL) record?.let { last ->
            // What the round still has to test: none of it after a whole session, else those without a result (a
            // stop when the section was hidden). A new process learns the same from `latency_at` (RoundPlan.followUp).
            val whole = !test.cancelled && test.failure == TestFailure.NONE
            val through = if (whole) sessionIds.toSet() else test.rows.filterValues { it.ok || it.failed }.keys
            if (last.left.any { it in through }) saveRecord(last.copy(left = last.left - through))
        }
        // The panel of the classic interface would otherwise show this section's checks.
        TestSessionClient.dismiss()
        viewModelScope.launch {
            // :bg has stored the results; read them, then check the exit IPs the names leave open.
            if (finished == TestSpec.KIND_IP) {
                countryChecked += detecting - queued
                countryChecked += roundPending
                roundPending.clear()
                detecting = queued.toSet()
                roundBusy = false
            }
            loadJob?.join()
            reload()
            loadJob?.join()
            liveLatency.clear()
            if (finished == TestSpec.KIND_URL) startIpTest() else startQueued()
        }
    }

    private fun publishLatency() {
        mutableState.update { state -> state.copy(sections = state.sections?.let(::withLiveLatency)) }
    }

    /** [sections] with the live URL test results over the stored delays; main thread only (it owns [liveLatency]). */
    private fun withLiveLatency(sections: ServerSections): ServerSections {
        if (liveLatency.isEmpty()) return sections
        fun ServerEntry.live() = liveLatency[id]?.let { copy(latency = it) } ?: this
        return sections.copy(
            subscriptions = sections.subscriptions.map { s ->
                s.copy(countries = s.countries.map { row -> row.copy(servers = row.servers.map { it.live() }) })
            },
            mine = sections.mine.map { it.live() },
            whitelist = sections.whitelist.map { it.live() },
        )
    }

    // ------------------------------------------------------------------------------------------------ running pick

    private fun publishPick() {
        val status = autoStatus
        val selected = mutableState.value.selected
        if (!status.active || status.selectorId != selected || status.selectedId <= 0L) {
            mutableState.update { it.copy(pick = null) }
            return
        }
        val known = pickInfo?.takeIf { it.first == status.selectedId }?.second
        if (known != null) {
            // The core names an unnamed member by its address: the list's name for it instead.
            val name = known.second.ifEmpty { getApplication<Application>().getString(R.string.simple_server_unnamed) }
            mutableState.update { it.copy(pick = RunningPick(status.selectorId, name, known.first)) }
            return
        }
        pickJob?.cancel()
        pickJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                try {
                    ProfileManager.getProfile(status.selectedId)?.let {
                        CountryFromName.resolve(it.outbound.name, it.testCountry).code to
                            ServerCatalog.nameOf(it, getApplication<Application>().getString(R.string.simple_server_unnamed))
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            }
            // A lookup for a pick that is no longer running is dropped.
            if (autoStatus.selectedId != status.selectedId) return@launch
            pickInfo = status.selectedId to (info ?: (null to ""))
            publishPick()
        }
    }

    private companion object {
        const val NONE = -1

        /** A new round at most this often (from the start of the last one, kept on disk as [RoundRecord.at]). */
        const val TEST_INTERVAL_MS = 2 * 60_000L
        const val PULL_GRACE_MS = 3_000L

        /** Hidden this long (not a rotation, not a glance elsewhere), the section's own session stops. */
        const val HIDE_STOP_MS = 5_000L

        /** Rows that came into view within this time are tested together. */
        const val SHOWN_DELAY_MS = 1_000L

        /** SharedPreferences `simple_ui`: the last round ([RoundRecord.encode]). */
        const val KEY_ROUND = "servers_round"

        /** Every exit IP check asks a lookup service; a list without country names is checked in part. */
        const val IP_TEST_LIMIT = RoundPlan.NO_COUNTRY
    }
}

enum class ServersEvent {
    /** The user chose «Авто», a country or a server. */
    Chosen,

    /** A subscription update finished in `:bg`. */
    Updated,
}
