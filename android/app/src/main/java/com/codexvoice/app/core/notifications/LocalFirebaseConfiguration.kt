package com.codexvoice.app.core.notifications

import android.content.Context
import android.net.Uri
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.gson.JsonParser

/** Client identifiers only. Never import a server service account onto the phone. */
object LocalFirebaseConfiguration {
    private fun preferences(context: Context) = context.getSharedPreferences("firebase-client", Context.MODE_PRIVATE)
    private fun options(context: Context): FirebaseOptions? {
        val saved = preferences(context)
        val appId = saved.getString("appId", null) ?: return null
        return FirebaseOptions.Builder().setApplicationId(appId)
            .setApiKey(saved.getString("apiKey", "")!!)
            .setProjectId(saved.getString("projectId", "")!!)
            .setGcmSenderId(saved.getString("senderId", "")!!).build()
    }
    fun initialize(context: Context): FirebaseApp? = try {
        FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            ?: options(context)?.let { FirebaseApp.initializeApp(context, it) }
            ?: FirebaseApp.initializeApp(context)
    } catch (_: Exception) { null }

    fun project(context: Context): String? = preferences(context).getString("projectId", null)
        ?: try { FirebaseApp.getInstance().options.projectId } catch (_: Exception) { null }

    fun importClient(context: Context, uri: Uri) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(65537)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) error("Client file could not be read")
                count += read
            }
            buffer.copyOf(count)
        }
            ?: error("Client file could not be opened")
        require(bytes.size <= 65536) { "Choose a Firebase Android client file under 64 KB" }
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(!root.has("private_key") && !root.has("type")) { "Never import service-account credentials onto the phone" }
        val info = root.getAsJsonObject("project_info") ?: error("Missing project information")
        val project = info.get("project_id")?.asString ?: error("Missing project ID")
        val sender = info.get("project_number")?.asString ?: error("Missing sender ID")
        require(project.matches(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]")) && sender.matches(Regex("[0-9]{1,20}"))) { "Invalid Firebase project" }
        val client = root.getAsJsonArray("client")?.firstOrNull {
            it.asJsonObject.getAsJsonObject("client_info")?.getAsJsonObject("android_client_info")?.get("package_name")?.asString == context.packageName
        }?.asJsonObject ?: error("Register com.codexvoice.app in your Firebase project")
        val appId = client.getAsJsonObject("client_info").get("mobilesdk_app_id").asString
        val key = client.getAsJsonArray("api_key")?.firstOrNull()?.asJsonObject?.get("current_key")?.asString ?: error("Missing Android client API key")
        require(appId.startsWith("1:$sender:android:") && key.matches(Regex("AIza[0-9A-Za-z_-]{35}"))) { "Invalid Android client identifiers" }
        val active = initialize(context)
        require(active == null || (active.options.projectId == project && active.options.applicationId == appId && active.options.apiKey == key)) {
            "A different Firebase client is active. Use a generic build with cleared app storage to configure a different project; pair again afterward."
        }
        check(preferences(context).edit().putString("projectId", project).putString("senderId", sender)
            .putString("appId", appId).putString("apiKey", key).commit()) { "Client configuration could not be saved" }
        initialize(context)
    }
}
