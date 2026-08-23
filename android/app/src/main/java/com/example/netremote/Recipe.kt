package com.example.netremote

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un appui appris : ou l'utilisateur a touche, decrit de trois facons.
 *
 * On garde les trois parce qu'elles ne vieillissent pas pareil. L'identifiant
 * de ressource survit a un changement de langue et de position. Le libelle
 * survit a une reorganisation des tuiles. Les coordonnees ne survivent a rien,
 * mais fonctionnent quand les deux autres manquent.
 */
data class Step(
    val viewId: String,
    val label: String,
    val x: Int,
    val y: Int,
    /** Application ou se trouvait l'appui : sert a s'y replacer au rejeu. */
    val pkg: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("viewId", viewId)
        .put("label", label)
        .put("x", x)
        .put("y", y)
        .put("pkg", pkg)

    fun describe(): String = when {
        viewId.isNotBlank() -> label.ifBlank { viewId.substringAfterLast('/') }
        label.isNotBlank() -> label
        else -> "appui en ($x, $y)"
    }

    companion object {
        fun fromJson(o: JSONObject) = Step(
            o.optString("viewId"), o.optString("label"),
            o.optInt("x", -1), o.optInt("y", -1), o.optString("pkg")
        )
    }
}

/**
 * La sequence apprise pour basculer les donnees mobiles.
 *
 * Apprendre plutot que deviner : les libelles et la disposition des parametres
 * rapides changent selon le constructeur, la version d'Android et la langue.
 * Coder des mots-cles en dur revient a parier sur un appareil qu'on n'a pas.
 */
object Recipe {

    private const val FILE = "netremote_recipe"
    private const val KEY_STEPS = "steps"
    private const val TAG = "Recipe"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, steps: List<Step>) {
        val array = JSONArray()
        steps.forEach { array.put(it.toJson()) }
        sp(context).edit().putString(KEY_STEPS, array.toString()).apply()
        Log.i(TAG, "Sequence enregistree : " + steps.size + " appui(s)")
    }

    fun load(context: Context): List<Step> = try {
        val array = JSONArray(sp(context).getString(KEY_STEPS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Step.fromJson(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun clear(context: Context) = sp(context).edit().remove(KEY_STEPS).apply()

    fun exists(context: Context) = load(context).isNotEmpty()

    fun describe(context: Context): String {
        val steps = load(context)
        if (steps.isEmpty()) return "aucune séquence apprise"
        return steps.mapIndexed { i, s -> "${i + 1}. ${s.describe()}" }.joinToString("\n")
    }
}
