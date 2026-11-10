package app.silenttrap.drive

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DriveAuthRequiredException(message: String) : IOException(message)

/**
 * Creates files the app owns. drive.file does not grant access to the rest of Drive.
 * The folder is created by this app, so later uploads can see it.
 */
class DriveClient(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("silent_trap_drive", Context.MODE_PRIVATE)

    fun clearCache() {
        prefs.edit().remove(KEY_FOLDER).apply()
    }

    fun uploadWav(file: File, sha256: String?): String {
        val token = token()
        val folderId = folderId(token)
        return try {
            upload(token, folderId, file, sha256)
        } catch (error: DriveHttpException) {
            if (error.code == 401) {
                GoogleAuthUtil.clearToken(context, token)
                val fresh = token()
                upload(fresh, folderId(fresh), file, sha256)
            } else {
                throw error
            }
        }
    }

    fun delete(fileId: String) {
        val token = token()
        val connection = open("https://www.googleapis.com/drive/v3/files/$fileId", token, "DELETE")
        try {
            val code = connection.responseCode
            if (code == 404 || code in 200..299) return
            throw DriveHttpException(code, readError(connection))
        } finally {
            connection.disconnect()
        }
    }

    private fun token(): String {
        val account = DriveAuth.account(context)?.account
            ?: throw DriveAuthRequiredException("Sign in to Google Drive again.")
        return try {
            GoogleAuthUtil.getToken(context, account, "oauth2:${DriveAuth.SCOPE}")
        } catch (error: UserRecoverableAuthException) {
            throw DriveAuthRequiredException("Google needs you to approve Drive access again.")
        }
    }

    private fun folderId(token: String): String {
        val cached = prefs.getString(KEY_FOLDER, null)
        if (!cached.isNullOrBlank() && folderExists(token, cached)) return cached
        val query = "name = '${DriveAuth.FOLDER_NAME}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encoded = URLEncoder.encode(query, "UTF-8")
        val listUrl = "https://www.googleapis.com/drive/v3/files?q=$encoded&fields=files(id,name)&spaces=drive&pageSize=10"
        val listed = getJson(listUrl, token)
        val files = listed.optJSONArray("files")
        if (files != null && files.length() > 0) {
            val id = files.getJSONObject(0).getString("id")
            prefs.edit().putString(KEY_FOLDER, id).apply()
            return id
        }
        val created = postJson(
            "https://www.googleapis.com/drive/v3/files",
            token,
            JSONObject()
                .put("name", DriveAuth.FOLDER_NAME)
                .put("mimeType", "application/vnd.google-apps.folder"),
        )
        val id = created.getString("id")
        prefs.edit().putString(KEY_FOLDER, id).apply()
        return id
    }

    private fun folderExists(token: String, id: String): Boolean {
        return try {
            val json = getJson("https://www.googleapis.com/drive/v3/files/$id?fields=id,trashed", token)
            !json.optBoolean("trashed", false)
        } catch (_: DriveHttpException) {
            false
        }
    }

    private fun upload(token: String, folderId: String, file: File, sha256: String?): String {
        val boundary = "silenttrap_${System.currentTimeMillis()}"
        val metadata = JSONObject()
            .put("name", file.name)
            .put("parents", org.json.JSONArray().put(folderId))
        if (sha256 != null) metadata.put("description", "SHA-256: $sha256")
            .toString()
        val connection = open(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            token,
            "POST",
        )
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        connection.setChunkedStreamingMode(0)
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        try {
            connection.outputStream.use { output ->
                val preamble = buildString {
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                    append(metadata).append("\r\n")
                    append("--").append(boundary).append("\r\n")
                    append("Content-Type: audio/wav\r\n\r\n")
                }
                output.write(preamble.toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(output) }
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                throw DriveHttpException(code, readError(connection))
            }
            return JSONObject(body).getString("id")
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, token: String): JSONObject {
        val connection = open(url, token, "GET")
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpException(code, readError(connection))
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(url: String, token: String, body: JSONObject): JSONObject {
        val connection = open(url, token, "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw DriveHttpException(code, readError(connection))
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, token: String, method: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        return connection
    }

    private fun readError(connection: HttpURLConnection): String {
        val stream = connection.errorStream ?: return connection.responseMessage ?: "Drive error"
        return stream.bufferedReader().use { it.readText() }.take(400)
    }

    private class DriveHttpException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

    private companion object {
        const val KEY_FOLDER = "folder_id"
    }
}
