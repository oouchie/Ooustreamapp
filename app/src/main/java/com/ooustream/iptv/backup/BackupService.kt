package com.ooustream.iptv.backup

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.ooustream.iptv.data.local.dao.FavoriteDao
import com.ooustream.iptv.data.local.dao.SearchHistoryDao
import com.ooustream.iptv.data.local.dao.WatchProgressDao
import com.ooustream.iptv.data.repository.CredentialStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val watchProgressDao: WatchProgressDao,
    private val searchHistoryDao: SearchHistoryDao,
    private val credentialStore: CredentialStore,
    @ApplicationContext private val context: Context
) {

    private val gson = Gson()

    suspend fun exportBackup(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val favorites = favoriteDao.getAllFavorites().first()
            val watchProgress = watchProgressDao.getAllProgress().first()
            val credentials = credentialStore.load()

            val backupData = BackupData(
                version = BackupData.CURRENT_VERSION,
                timestamp = System.currentTimeMillis(),
                favorites = favorites,
                watchProgress = watchProgress,
                credentials = credentials
            )

            val json = gson.toJson(backupData)

            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "ooustream_backup_${dateFormat.format(Date())}.json"

            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                ?: throw IllegalStateException("External files directory is not available")

            if (!dir.exists()) {
                dir.mkdirs()
            }

            val file = File(dir, fileName)
            file.writeText(json)
            file
        }
    }

    suspend fun importBackup(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val backupData = try {
                gson.fromJson(json, BackupData::class.java)
            } catch (e: JsonSyntaxException) {
                throw IllegalArgumentException("Invalid backup file format", e)
            }

            requireNotNull(backupData) { "Backup data is empty" }

            if (backupData.version > BackupData.CURRENT_VERSION) {
                throw IllegalArgumentException(
                    "Backup version ${backupData.version} is newer than supported version ${BackupData.CURRENT_VERSION}. Please update the app."
                )
            }

            clearAllData()

            var restoredCount = 0

            backupData.favorites.forEach { favorite ->
                favoriteDao.insert(favorite)
                restoredCount++
            }

            backupData.watchProgress.forEach { progress ->
                watchProgressDao.upsert(progress)
                restoredCount++
            }

            backupData.credentials?.let { creds ->
                credentialStore.save(creds)
                restoredCount++
            }

            restoredCount
        }
    }

    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        favoriteDao.clearAll()
        watchProgressDao.clearAll()
        searchHistoryDao.clear()
        credentialStore.clear()
    }
}
