package com.example.netremote

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Un pas du parcours appris : ou toucher, et QUOI y faire.
 *
 * L'emplacement est decrit de trois facons parce qu'elles ne vieillissent pas
 * pareil. L'identifiant de ressource survit a un changement de langue et de
 * position. Le libelle survit a une reorganisation des tuiles. Les coordonnees
 * ne survivent a rien, mais fonctionnent quand les deux autres manquent.
 *
 * Le [role] est la correction du defaut de fond des versions precedentes :
 * elles n'enregistraient que des APPUIS. Or la derniere etape n'est pas une
 * action, c'est un ETAT — un interrupteur. Rejouer un appui dessus le bascule,
 * quel que soit l'etat de depart : demander « active » alors que c'est deja
 * actif le coupait. Un pas [ROLE_TOGGLE] est lu avant d'etre touche, et n'est
 * touche que si son etat differe de celui demande.
 */
data class Step(
    val viewId: String,
    val label: String,
    val x: Int,
    val y: Int,
    /** Application ou se trouvait l'appui : sert a s'y replacer au rejeu. */
    val pkg: String = "",
    val role: String = ROLE_TAP
) {
    fun toJson(): JSONObject = JSONObject()
        .put("viewId", viewId)
        .put("label", label)
        .put("x", x)
        .put("y", y)
        .put("pkg", pkg)
        .put("role", role)

    val isToggle: Boolean get() = role == ROLE_TOGGLE

    fun describe(): String {
        val what = when {
            viewId.isNotBlank() -> label.ifBlank { viewId.substringAfterLast('/') }
            label.isNotBlank() -> label
            else -> "appui en ($x, $y)"
        }
        return if (isToggle) "$what  [interrupteur]" else what
    }

    companion object {
        /** On appuie, sans se soucier de l'etat : ca ouvre un ecran. */
        const val ROLE_TAP = "tap"

        /** On lit l'etat, et on n'appuie que s'il differe de celui voulu. */
        const val ROLE_TOGGLE = "toggle"

        fun fromJson(o: JSONObject) = Step(
            o.optString("viewId"), o.optString("label"),
            o.optInt("x", -1), o.optInt("y", -1), o.optString("pkg"),
            o.optString("role", ROLE_TAP)
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
    private const val KEY_SHORTCUT = "shortcut"
    private const val TAG = "Recipe"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Si aucun pas n'est marque comme interrupteur, c'est le dernier.
     *
     * Vrai par construction : le parcours s'arrete des que les donnees ont
     * bascule, donc le pas final est forcement celui qui les bascule. Ca evite
     * de demander a l'utilisateur ce que le trajet dit deja.
     */
    /**
     * @param shortcut « paquet/classe » de l'ecran ou se trouve l'interrupteur,
     *   quand on a pu l'identifier. C'est le vrai gain de robustesse : rejouer
     *   trois appuis dans les Reglages depend de la mise en page, de la vitesse
     *   des transitions et de la position dans une liste ; ouvrir l'ecran
     *   directement ne depend de rien. Le parcours reste la, en repli.
     */
    fun save(context: Context, steps: List<Step>, shortcut: String = "") {
        val fixed = when {
            steps.isEmpty() || steps.any { it.isToggle } -> steps
            else -> steps.dropLast(1) + steps.last().copy(role = Step.ROLE_TOGGLE)
        }

        val array = JSONArray()
        fixed.forEach { array.put(it.toJson()) }
        sp(context).edit()
            .putString(KEY_STEPS, array.toString())
            .putString(KEY_SHORTCUT, shortcut)
            .apply()
        Log.i(TAG, "Sequence enregistree : " + fixed.size + " pas")
    }

    fun load(context: Context): List<Step> = try {
        val array = JSONArray(sp(context).getString(KEY_STEPS, "[]"))
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Step.fromJson(it) }
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** « paquet/classe » de l'ecran de l'interrupteur, vide si inconnu. */
    fun shortcut(context: Context): String =
        sp(context).getString(KEY_SHORTCUT, "").orEmpty()

    fun clear(context: Context) =
        sp(context).edit().remove(KEY_STEPS).remove(KEY_SHORTCUT).apply()

    fun exists(context: Context) = load(context).isNotEmpty()

    fun describe(context: Context): String {
        val steps = load(context)
        if (steps.isEmpty()) return "aucune séquence apprise"
        val lines = steps.mapIndexed { i, s -> "${i + 1}. ${s.describe()}" }.toMutableList()
        val shortcut = shortcut(context)
        if (shortcut.isNotBlank()) lines += "raccourci : " + shortcut.substringAfterLast('.')
        return lines.joinToString("\n")
    }
}
