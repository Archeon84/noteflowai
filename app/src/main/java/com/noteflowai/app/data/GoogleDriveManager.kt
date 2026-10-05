@file:Suppress("DEPRECATION")

package com.noteflowai.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Collections

class GoogleDriveManager(private val context: Context) {

    companion object {
        private const val TAG = "GoogleDriveManager"
        private const val BACKUP_FOLDER_NAME = "NoteFlowAI_Backups"
        private const val MIME_TYPE_ZIP = "application/zip"
        private const val PREFS_NAME = "google_drive_prefs"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
    }

    private val gsonFactory = GsonFactory.getDefaultInstance()
    private val httpTransport = NetHttpTransport()

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestScopes(Scope(DriveScopes.DRIVE_FILE))
        .build()

    private var googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    fun isSignedIn(): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account != null
    }

    fun getSignedInEmail(): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCOUNT_EMAIL, null)
    }

    fun handleSignInResult(data: Intent?): Boolean {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && account.email != null) {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_ACCOUNT_EMAIL, account.email).apply()
                Log.i(TAG, "Signed in as ${account.email}")
                true
            } else {
                Log.w(TAG, "Sign-in succeeded but no account")
                false
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed: ${e.statusCode}", e)
            false
        }
    }

    fun signOut() {
        googleSignInClient.signOut()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_ACCOUNT_EMAIL).apply()
    }

    private fun getDriveService(): Drive? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null

        val credential = GoogleAccountCredential.usingOAuth2(
            context, Collections.singleton(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account.account ?: return null

        return Drive.Builder(httpTransport, gsonFactory, credential)
            .setApplicationName("NoteFlowAI")
            .build()
    }

    data class DriveResult(val success: Boolean, val message: String)
    data class DriveDownloadResult(val success: Boolean, val message: String, val file: File? = null)

    suspend fun uploadBackup(backupFile: File): DriveResult = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService() ?: return@withContext DriveResult(false, "Not signed in to Google Drive")

            val folderId = findOrCreateFolder(drive)

            val existingFile = findFileByName(drive, backupFile.name, folderId)
            if (existingFile != null) {
                val content = FileContent(MIME_TYPE_ZIP, backupFile)
                drive.files().update(existingFile.id, null, content).execute()
                Log.i(TAG, "Updated existing backup: ${backupFile.name}")
                return@withContext DriveResult(true, "Updated backup on Google Drive: ${backupFile.name}")
            }

            val fileMetadata = DriveFile().apply {
                name = backupFile.name
                parents = listOf(folderId)
            }
            val content = FileContent(MIME_TYPE_ZIP, backupFile)
            val uploaded = drive.files().create(fileMetadata, content)
                .setFields("id, name, size")
                .execute()

            Log.i(TAG, "Uploaded backup: ${uploaded.name} (${uploaded.size} bytes)")
            DriveResult(true, "Uploaded to Google Drive: ${uploaded.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            DriveResult(false, "Upload failed: ${e.message}")
        }
    }

    suspend fun downloadLatestBackup(): DriveDownloadResult = withContext(Dispatchers.IO) {
        try {
            val drive = getDriveService() ?: return@withContext DriveDownloadResult(false, "Not signed in to Google Drive")

            val folderId = findOrCreateFolder(drive)
            val files = drive.files().list()
                .setQ("'$folderId' in parents and mimeType='$MIME_TYPE_ZIP' and trashed=false")
                .setSpaces("drive")
                .setOrderBy("modifiedTime desc")
                .setFields("files(id, name, size, modifiedTime)")
                .execute()
                .files

            if (files.isNullOrEmpty()) {
                return@withContext DriveDownloadResult(false, "No backups found on Google Drive")
            }

            val latest = files[0]
            val backupDir = File(context.getExternalFilesDir(null), "NoteFlowAI_Backup")
            backupDir.mkdirs()
            val destFile = File(backupDir, latest.name)

            drive.files().get(latest.id).executeMediaAsInputStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Downloaded: ${latest.name} (${latest.size} bytes)")
            DriveDownloadResult(true, "Downloaded from Google Drive: ${latest.name}", destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            DriveDownloadResult(false, "Download failed: ${e.message}")
        }
    }

    private fun findOrCreateFolder(drive: Drive): String {
        val folderName = BACKUP_FOLDER_NAME
        val result = drive.files().list()
            .setQ("mimeType='application/vnd.google-apps.folder' and name='$folderName' and trashed=false")
            .setSpaces("drive")
            .setFields("files(id)")
            .execute()

        if (!result.files.isNullOrEmpty()) {
            return result.files[0].id
        }

        val folderMetadata = DriveFile().apply {
            name = folderName
            mimeType = "application/vnd.google-apps.folder"
        }
        val folder = drive.files().create(folderMetadata)
            .setFields("id")
            .execute()
        return folder.id
    }

    private fun findFileByName(drive: Drive, name: String, folderId: String): DriveFile? {
        return drive.files().list()
            .setQ("'$folderId' in parents and name='$name' and trashed=false")
            .setSpaces("drive")
            .setFields("files(id, name)")
            .execute()
            .files
            ?.firstOrNull()
    }
}
