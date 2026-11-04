package com.gestlab.com

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Un menu Odoo. [actionId] est nul pour une simple rubrique (sans écran). */
data class OdooMenu(
    val id: Int,
    val name: String,
    val actionId: Int?,
    val children: List<OdooMenu>,
)

/** Ligne du menu latéral d'une application : rubrique (titre) ou écran cliquable. */
data class MenuEntry(val menu: OdooMenu, val depth: Int, val isSection: Boolean)

class OdooSessionExpired : IOException("Session Odoo expirée")

/** Utilisateur connecté à Odoo : identifiant, nom, version du serveur et contexte (langue…). */
data class OdooSession(val uid: Int, val name: String, val version: String, val context: JSONObject?)

/** Compteurs de la barre : messages à traiter (boîte de réception) et activités à faire. */
data class OdooCounters(val messages: Int?, val activities: Int?)

/**
 * Lit les menus Odoo autorisés pour l'utilisateur connecté (ir.ui.menu.load_menus) :
 * Odoo ne renvoie que les menus permis par ses groupes et droits d'accès, le menu
 * de l'application suit donc exactement les droits de chaque utilisateur.
 */
object OdooMenus {

    suspend fun session(baseUrl: String, cookie: String?): OdooSession = withContext(Dispatchers.IO) {
        val info = rpc(baseUrl, cookie, "/web/session/get_session_info", JSONObject()) as? JSONObject
            ?: throw IOException("Réponse Odoo inattendue")
        val uid = info.opt("uid")
        if (uid !is Number) throw OdooSessionExpired()
        OdooSession(
            uid = uid.toInt(),
            name = info.optString("name").ifEmpty { info.optString("username") },
            version = info.optString("server_version"),
            // Contexte de l'utilisateur (langue du compte, fuseau…) : sans lui, certaines
            // versions d'Odoo renvoient les menus en anglais.
            context = info.optJSONObject("user_context"),
        )
    }

    suspend fun load(baseUrl: String, cookie: String?, session: OdooSession?): List<OdooMenu> =
        withContext(Dispatchers.IO) {
            val kwargs = JSONObject()
            session?.context?.let { kwargs.put("context", it) }
            val result = rpc(
                baseUrl, cookie, "/web/dataset/call_kw/ir.ui.menu/load_menus",
                JSONObject()
                    .put("model", "ir.ui.menu")
                    .put("method", "load_menus")
                    .put("args", JSONArray().put(false))
                    .put("kwargs", kwargs)
            )
            parse(result as JSONObject)
        }

    /** Compteurs ; une valeur nulle si le module correspondant est absent ou en erreur. */
    suspend fun counters(baseUrl: String, cookie: String?, session: OdooSession): OdooCounters =
        withContext(Dispatchers.IO) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
            fun count(model: String, domain: JSONArray): Int? = try {
                val kwargs = JSONObject()
                session.context?.let { kwargs.put("context", it) }
                (rpc(
                    baseUrl, cookie, "/web/dataset/call_kw/$model/search_count",
                    JSONObject()
                        .put("model", model)
                        .put("method", "search_count")
                        .put("args", JSONArray().put(domain))
                        .put("kwargs", kwargs)
                ) as? Number)?.toInt()
            } catch (e: OdooSessionExpired) {
                throw e
            } catch (e: Exception) {
                null
            }
            OdooCounters(
                // Messages qui demandent une action de l'utilisateur (boîte de réception Odoo).
                messages = count("mail.message", JSONArray().put(JSONArray().put("needaction").put("=").put(true))),
                // Activités en retard ou du jour, assignées à l'utilisateur.
                activities = count(
                    "mail.activity", JSONArray()
                        .put(JSONArray().put("user_id").put("=").put(session.uid))
                        .put(JSONArray().put("date_deadline").put("<=").put(today))
                ),
            )
        }

    /** Appel JSON-RPC à Odoo avec le cookie de session de la WebView ; renvoie « result ». */
    private fun rpc(baseUrl: String, cookie: String?, path: String, params: JSONObject): Any? {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "call")
            .put("id", 1)
            .put("params", params)

        val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (!cookie.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Odoo : erreur HTTP $code")

            val json = JSONObject(text)
            json.optJSONObject("error")?.let { err ->
                val name = err.optJSONObject("data")?.optString("name").orEmpty()
                if (name.contains("SessionExpired") || err.optInt("code") == 100) throw OdooSessionExpired()
                throw IOException(err.optJSONObject("data")?.optString("message") ?: err.optString("message"))
            }
            return json.opt("result")
        } finally {
            conn.disconnect()
        }
    }

    /** Odoo 16+ : dictionnaire plat {id: menu, "root": {...}} ; Odoo ≤ 15 : arbre imbriqué. */
    private fun parse(result: JSONObject): List<OdooMenu> {
        val root = result.optJSONObject("root") ?: result
        val children = root.optJSONArray("children") ?: return emptyList()
        return (0 until children.length()).mapNotNull { i ->
            when (val c = children.get(i)) {
                is JSONObject -> nested(c)
                else -> flat(result, c.toString())
            }
        }
    }

    private fun flat(all: JSONObject, id: String): OdooMenu? {
        val m = all.optJSONObject(id) ?: return null
        val ids = m.optJSONArray("children") ?: JSONArray()
        return OdooMenu(
            id = m.optInt("id"),
            name = m.optString("name"),
            actionId = actionOf(m),
            children = (0 until ids.length()).mapNotNull { flat(all, ids.get(it).toString()) },
        )
    }

    private fun nested(m: JSONObject): OdooMenu {
        val kids = m.optJSONArray("children") ?: JSONArray()
        return OdooMenu(
            id = m.optInt("id"),
            name = m.optString("name"),
            actionId = actionOf(m),
            children = (0 until kids.length()).mapNotNull { kids.optJSONObject(it)?.let(::nested) },
        )
    }

    /** "actionID": 123 (Odoo 16+) ou "action": "ir.actions.act_window,123" (anciennes versions). */
    private fun actionOf(m: JSONObject): Int? {
        val direct = m.opt("actionID")
        if (direct is Number && direct.toInt() > 0) return direct.toInt()
        val legacy = m.opt("action")
        if (legacy is String) return legacy.substringAfter(',', "").toIntOrNull()
        return null
    }

    /** Aplatit les sous-menus d'une application pour l'affichage (rubriques + écrans). */
    fun entriesOf(app: OdooMenu): List<MenuEntry> {
        val out = mutableListOf<MenuEntry>()
        fun walk(menu: OdooMenu, depth: Int) {
            if (menu.children.isEmpty()) {
                if (menu.actionId != null) out += MenuEntry(menu, depth, isSection = false)
            } else {
                val before = out.size
                out += MenuEntry(menu, depth, isSection = true)
                menu.children.forEach { walk(it, depth + 1) }
                if (out.size == before + 1) out.removeAt(before) // rubrique vide
            }
        }
        app.children.forEach { walk(it, 0) }
        return out
    }

    fun urlOf(baseUrl: String, menu: OdooMenu): String =
        if (menu.actionId != null) "$baseUrl/web#menu_id=${menu.id}&action=${menu.actionId}"
        else "$baseUrl/web#menu_id=${menu.id}"
}
