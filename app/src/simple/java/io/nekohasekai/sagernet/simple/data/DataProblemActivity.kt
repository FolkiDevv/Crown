package io.nekohasekai.sagernet.simple.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.backup.ThrBackup
import io.nekohasekai.sagernet.simple.SimpleTheme
import io.nekohasekai.sagernet.simple.SimpleUi
import io.nekohasekai.sagernet.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The screen of both interfaces when their data is in trouble (`stop-wiping-database-on-open-error`):
 * - [MODE_ERROR], «Не удалось открыть данные»: the database did not open and was left as it is. Retry, or save the
 *   file. Nothing here reads the database: the theme follows the system, the language the system's.
 * - [MODE_REBUILT]: the database was damaged, a copy was kept and a new one created. Restore a backup, or save the
 *   copy.
 */
class DataProblemActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DataProblem"
        private const val EXTRA_MODE = "io.nekohasekai.sagernet.simple.data.MODE"
        private const val MODE_ERROR = "error"
        private const val MODE_REBUILT = "rebuilt"
        private const val STATE_STATUS = "status"

        fun errorIntent(context: Context): Intent =
            Intent(context, DataProblemActivity::class.java).putExtra(EXTRA_MODE, MODE_ERROR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        fun rebuiltIntent(context: Context): Intent =
            Intent(context, DataProblemActivity::class.java).putExtra(EXTRA_MODE, MODE_REBUILT)
    }

    private val rebuilt get() = intent?.getStringExtra(EXTRA_MODE) == MODE_REBUILT

    private var status by mutableStateOf<String?>(null)
    private var failure by mutableStateOf<String?>(null)
    private var kind by mutableStateOf(DataFiles.Kind.OTHER)
    private var busy by mutableStateOf(false)

    private val save = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) saveTo(uri)
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (!rebuilt) {
            // A crash while this screen comes up must not restart into it again (a loop); see onStart/onStop.
            DataGate.screenShown = true
            // Restarted here by a crash, and this process opened the database after all: carry on as usual. Not
            // isAvailable(): that waits for a retry still opening (a rotation during «Повторить»).
            if (SagerDatabase.isOpen) {
                DataGate.screenShown = false
                DataGate.restart(this)
                return
            }
            showFailure()
        } else if (DataGate.rebuiltCopy() == null) {
            finish()
            return
        } else {
            DataGate.rebuiltNoticeUp()
        }
        status = savedInstanceState?.getString(STATE_STATUS)
        setContent {
            SimpleTheme(fromSettings = rebuilt) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (rebuilt) RebuiltScreen() else ErrorScreen()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STATUS, status)
    }

    override fun onStart() {
        super.onStart()
        if (!rebuilt) DataGate.screenShown = true
    }

    override fun onStop() {
        // In the background (Home, Back) another entry may start MainActivity, whose crash must lead back here.
        if (!rebuilt) DataGate.screenShown = false
        super.onStop()
    }

    private fun showFailure() {
        val error = SagerDatabase.openFailure ?: return
        failure = DataFiles.describe(error)
        kind = DataGate.kindOf(error)
    }

    // ------------------------------------------------------------------------------------------------ actions

    private fun retry() {
        if (busy) return
        busy = true
        status = null
        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) { SagerDatabase.isAvailable(retry = true) }
            if (opened) {
                // The app's start skipped every setting: a new process starts it again, now with the data.
                DataGate.screenShown = false
                DataGate.restart(this@DataProblemActivity)
                return@launch
            }
            showFailure()
            status = getString(R.string.simple_data_error_retry_failed)
            busy = false
        }
    }

    private fun fileName(): String =
        "crown-data-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip"

    private fun startSave() {
        if (savedFiles().isEmpty()) {
            status = getString(R.string.simple_data_save_nothing)
            return
        }
        try {
            save.launch(fileName())
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, e)
            status = getString(R.string.simple_data_save_no_picker)
        }
    }

    /** The files saved: the database as it is, or the copy of the damaged one. */
    private fun savedFiles(): List<File> {
        val db = DataGate.databaseFile()
        if (!rebuilt) return DataFiles.parts(db)
        val name = DataGate.rebuiltCopy().orEmpty()
        if (name.isEmpty()) return emptyList()
        return DataFiles.backupParts(File(db.parentFile, name))
    }

    private fun saveTo(uri: Uri) {
        busy = true
        lifecycleScope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    val output = contentResolver.openOutputStream(uri)
                        ?: throw java.io.IOException("no output stream")
                    output.use { DataFiles.writeZip(savedFiles(), it) }
                    getString(R.string.simple_data_saved)
                } catch (e: Exception) {
                    Log.w(TAG, "save", e)
                    getString(R.string.simple_data_save_failed, DataFiles.describe(e))
                }
            }
            busy = false
        }
    }

    private fun startRestore() {
        try {
            pickBackup.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, e)
            status = getString(R.string.simple_data_save_no_picker)
        }
    }

    /**
     * As the simple UI hands a backup over (it checks the magic first too): MainActivity opens the stock restore with
     * it. Another file keeps this screen, so the copy can still be saved or another file picked.
     */
    private fun openBackup(uri: Uri) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val backup = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { ThrBackup.hasMagic(it) } == true
                } catch (e: Exception) {
                    Log.w(TAG, "open backup", e)
                    false
                }
            }
            busy = false
            if (backup) handBackup(uri) else status = getString(R.string.simple_data_not_backup)
        }
    }

    private fun handBackup(uri: Uri) {
        try {
            startActivity(
                SimpleUi.markOwn(
                    Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(SimpleUi.EXTRA_OPEN_CLASSIC, true)
                )
            )
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, e)
            return
        }
        // The mark stays until «Понятно»: a restore that fails or is cancelled must not lose the way to the copy.
        finish()
    }

    private fun dismiss() {
        DataGate.clearRebuilt()
        finish()
    }

    // ------------------------------------------------------------------------------------------------ screens

    @Composable
    private fun Page(title: String, content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                content()
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    @Composable
    private fun ErrorScreen() = Page(stringResource(R.string.simple_data_error_title)) {
        val reason = when (kind) {
            DataFiles.Kind.NEWER_VERSION -> R.string.simple_data_error_newer
            DataFiles.Kind.NO_SPACE -> R.string.simple_data_error_space
            DataFiles.Kind.CORRUPT -> R.string.simple_data_error_corrupt
            DataFiles.Kind.OTHER -> R.string.simple_data_error_other
        }
        Text(stringResource(R.string.simple_data_error_kept), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(reason), style = MaterialTheme.typography.bodyLarge)
        failure?.let {
            Text(
                stringResource(R.string.simple_data_error_detail, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = ::retry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.simple_data_error_retry))
        }
        OutlinedButton(onClick = ::startSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.simple_data_error_save))
        }
        SaveWarning()
    }

    @Composable
    private fun SaveWarning() = Text(
        stringResource(R.string.simple_data_save_private),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    private fun RebuiltScreen() = Page(stringResource(R.string.simple_data_rebuilt_title)) {
        Text(stringResource(R.string.simple_data_rebuilt_text), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = ::startRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.simple_data_rebuilt_restore))
        }
        OutlinedButton(onClick = ::startSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.simple_data_rebuilt_save))
        }
        SaveWarning()
        TextButton(onClick = ::dismiss, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.simple_data_rebuilt_dismiss))
        }
    }
}
