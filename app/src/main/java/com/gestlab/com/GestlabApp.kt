package com.gestlab.com

import android.app.Application
import android.content.Context
import android.os.Build
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application : hérite de celle du moteur OpenVPN (canaux de notification,
 * liaison avec le processus « :openvpn » qui fait tourner le tunnel).
 *
 * Enregistre aussi tout plantage (dans les deux processus) pour l'afficher dans
 * le journal de l'écran d'attente : sans câble ni outils, on voit la cause exacte.
 */
class GestlabApp : ICSOpenVPNApplication() {

    override fun onCreate() {
        installCrashRecorder()
        super.onCreate()
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val dir = crashDir(this)
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val process = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    Application.getProcessName() else packageName
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(Date())
                dir.mkdirs()
                File(dir, process.substringAfter(':', "ui") + ".txt").writeText(
                    "=== PLANTAGE $stamp — processus $process, fil ${thread.name} ===\n" +
                        trace.lines().take(40).joinToString("\n")
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private fun crashDir(c: Context) = File(c.filesDir, "crash")

        /** Rapports de plantage enregistrés (le plus récent d'abord), ou "" s'il n'y en a pas. */
        fun crashReports(c: Context): String =
            crashDir(c).listFiles()?.sortedByDescending { it.lastModified() }
                ?.joinToString("\n\n") { runCatching { it.readText() }.getOrDefault("") }
                .orEmpty()

        fun clearCrashReports(c: Context) {
            crashDir(c).listFiles()?.forEach { it.delete() }
        }
    }
}
