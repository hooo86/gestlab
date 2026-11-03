package com.gestlab.com

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Enregistre les fichiers téléchargés depuis Odoo (factures PDF, exports Excel…).
 *
 * Le téléchargement est fait par l'application elle-même, pas par le gestionnaire
 * de téléchargements d'Android : celui-ci ne passe pas par le VPN de l'application.
 */
object Downloads {

    /** Enregistre dans « Téléchargements » et renvoie l'adresse du fichier pour l'ouvrir. */
    fun save(context: Context, bytes: ByteArray, rawName: String, mime: String?): Uri {
        val type = mime?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(rawName.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val name = withExtension(sanitize(rawName), type)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Impossible de créer le fichier")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, name)
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun open(context: Context, uri: Uri, mime: String?) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime ?: context.contentResolver.getType(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").trim()
            .ifEmpty { "document" }

    private fun withExtension(name: String, mime: String): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
        return "$name.$ext"
    }
}
