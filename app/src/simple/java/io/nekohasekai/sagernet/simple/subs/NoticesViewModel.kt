package io.nekohasekai.sagernet.simple.subs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class NoticesUiState(
    /** The one message of the home screen, null for none. */
    val home: Notice? = null,
    /** Every message of every subscription (the server list shows them on the subscription cards). */
    val all: List<Notice> = emptyList(),
    /** The announcement shown whole in the sheet, null while it is closed. */
    val sheet: Notice? = null,
)

/**
 * The subscription messages of the simple UI (simple-ui "Сообщения подписки"): read from the database and the
 * subscription state of [SubscriptionState] again whenever a subscription update ends, the app comes back, or the
 * user puts one off. A subscription the user left the app for (its provider's page) is checked again on return.
 */
class NoticesViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {

    private val mutableState = MutableStateFlow(NoticesUiState())
    val state: StateFlow<NoticesUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var recheckJob: Job? = null
    private var updating: Set<Long> = emptySet()

    /** The subscription whose provider page the user went to, checked again when the app comes back. */
    // Kept in the saved state: Android may end the process while the provider's page is open.
    private var leftFor: Long
        get() = saved[KEY_LEFT_FOR] ?: 0L
        set(value) {
            saved[KEY_LEFT_FOR] = value
        }

    /** The subscription whose provider page is being opened: [leftFor] once the app is out of sight ([stopped]). */
    private var leaving: Long
        get() = saved[KEY_LEAVING] ?: 0L
        set(value) {
            saved[KEY_LEAVING] = value
        }

    /** The announcement asked for before the messages were read (a notification's «Подробнее»). */
    private var sheetWanted = 0L

    init {
        viewModelScope.launch {
            SubscriptionClient.states.collect { states ->
                val finished = updating - states.keys
                updating = states.keys
                if (finished.isNotEmpty()) reload()
            }
        }
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                try {
                    val now = System.currentTimeMillis() / 1000
                    val facts = SubscriptionState.facts()
                    val notices = facts.flatMap { SubscriptionNotices.of(it, now) }
                    val gids = notices.map { it.groupId }.distinct()
                    // «Позже» lasts while an amber message does: after a renewal or a top-up, the next period's first
                    // threshold shows again.
                    for (gid in facts.map { it.groupId }) {
                        if (notices.none { it.groupId == gid && it.level == NoticeLevel.WARNING }) SubscriptionState.clearLater(gid)
                    }
                    val later = gids.mapNotNull { gid -> SubscriptionState.later(gid)?.let { gid to it } }.toMap()
                    val hidden = gids.mapNotNull { gid -> SubscriptionState.hidden(gid)?.let { gid to it } }.toMap()
                    notices to SubscriptionNotices.pick(notices, later, hidden)
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
            } ?: return@launch
            val (all, home) = read
            mutableState.update { state ->
                // A requested announcement (a notification's «Подробнее») first; otherwise an open sheet follows its
                // subscription's announcement, and closes when it is gone.
                val sheetGid = sheetWanted.takeIf { it > 0L } ?: state.sheet?.groupId
                val sheet = sheetGid?.let { gid -> announcementOf(all, gid) }
                if (sheetWanted > 0L) sheetWanted = 0L
                state.copy(home = home, all = all, sheet = sheet)
            }
        }
    }

    /** The app is in front again: a subscription the user went to renew is checked, the messages are read again. */
    fun resumed() {
        val gid = leftFor
        leftFor = 0L
        if (gid > 0L) recheck(gid) else reload()
    }

    /** The user opens a page of the provider of [gid] (to renew it, the account, support). */
    fun leavingFor(gid: Long) {
        leaving = gid
    }

    /**
     * The app went out of sight: a provider page opened just before is what the user left for. (A page opened from a
     * notification as the app starts comes before that start's [resumed], which must not take it yet.)
     */
    fun stopped() {
        if (leaving > 0L) leftFor = leaving
        leaving = 0L
    }

    fun recheck(gid: Long) {
        // [SubscriptionClient.states] shows the end of the update only if its callback is registered by then. In a
        // process started just now (Android ended it while the provider's page was open) a quick update can end
        // first, so the subscription's update time is watched too.
        recheckJob?.cancel()
        recheckJob = viewModelScope.launch {
            val before = withContext(Dispatchers.IO) { updatedAt(gid) }
            SubscriptionClient.refreshGroup(gid, false)
            withTimeoutOrNull(RECHECK_WATCH_MS) {
                while (true) {
                    val now = withContext(Dispatchers.IO) { updatedAt(gid) }
                    // A failed read (null) tells nothing: only a change between two good reads ends the wait early.
                    if (before != null && now != null && now != before) break
                    delay(RECHECK_POLL_MS)
                }
            }
            reload()
        }
    }

    private fun updatedAt(gid: Long): Long? = try {
        GroupRepo.get(gid)?.subLastUpdate
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    /** «Позже»: the amber message stays away until its next threshold. */
    fun later(notice: Notice) = store { SubscriptionState.setLater(notice.groupId, notice.token) }

    /** ✕ on the announcement: away until the provider sends another text. */
    fun hide(notice: Notice) = store { SubscriptionState.setHidden(notice.groupId, notice.token) }

    fun openAnnouncement(gid: Long) {
        val notice = announcementOf(mutableState.value.all, gid)
        if (notice == null) {
            // Not read yet (the app opened from a notification): shown once it is.
            sheetWanted = gid
            reload()
            return
        }
        // A request still waiting for its read is older than this one.
        sheetWanted = 0L
        mutableState.update { it.copy(sheet = notice) }
    }

    fun closeAnnouncement() = mutableState.update { it.copy(sheet = null) }

    /** The announcement of [gid], also when a red or amber message quotes it instead of a card of its own. */
    private fun announcementOf(all: List<Notice>, gid: Long): Notice? =
        all.firstOrNull { it.groupId == gid && it.reason == NoticeReason.ANNOUNCE }
            ?: all.firstOrNull { it.groupId == gid && it.facts.announce.isNotEmpty() }
                ?.let { Notice(it.facts, NoticeReason.ANNOUNCE, NoticeLevel.NEWS, it.facts.announce) }

    private fun store(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            reload()
        }
    }

    private companion object {
        const val KEY_LEFT_FOR = "left_for"
        const val KEY_LEAVING = "leaving"

        // A refresh ends within the fetch timeout; a failed one leaves the time as it was, and the read follows anyway.
        const val RECHECK_WATCH_MS = 30_000L
        const val RECHECK_POLL_MS = 1_000L
    }
}
