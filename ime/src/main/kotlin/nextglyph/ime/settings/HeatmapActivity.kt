package nextglyph.ime.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import nextglyph.engine.layout.LayoutKind
import nextglyph.engine.stats.KeyHeatmap
import nextglyph.ime.AppServices
import nextglyph.ime.R
import nextglyph.ime.settings.KeyboardSettings.Companion.storedName
import nextglyph.ime.view.HeatmapDescriptions
import nextglyph.ime.view.HeatmapMode
import nextglyph.ime.view.HeatmapStyle
import nextglyph.ime.view.HeatmapView
import java.text.NumberFormat

/**
 * Carte de frappe (issue #46), ouverte depuis les réglages : trois vues du clavier au repos (densité des
 * appuis, décalages appris, erreurs), dessinées par [HeatmapView] d'après les compteurs de [KeyHeatmap] et le
 * modèle spatial appris. Rien de ce qui a été tapé n'est lu ni affiché : seulement des compteurs par touche.
 *
 * Le dernier choix de vue et de disposition est mémorisé dans le fichier de préférences local des mesures
 * (pas dans les réglages du clavier, que le service écoute). La disposition n'est proposée que si les deux
 * langues sont actives ; sinon, c'est celle des réglages. Le bouton de remise à zéro efface l'apprentissage des
 * zones de frappe et la carte, sans toucher au dictionnaire personnel ni aux mesures de saisie.
 */
class HeatmapActivity : Activity() {

    private lateinit var services: AppServices
    private lateinit var map: HeatmapView
    private lateinit var legend: TextView
    private lateinit var total: TextView
    private lateinit var empty: TextView
    private lateinit var descriptions: HeatmapDescriptions
    private val percent: NumberFormat = NumberFormat.getPercentInstance().apply { maximumFractionDigits = 0 }

    private var mode = HeatmapMode.DENSITY
    private var layout = LayoutKind.AZERTY

    /** Préférences locales non critiques (dernière vue, dernière disposition) : même fichier que les mesures. */
    private val local: SharedPreferences get() = getSharedPreferences(AppServices.STATS_PREFERENCES, MODE_PRIVATE)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        services = AppServices.get(this)
        setTitle(R.string.heatmap_title)
        descriptions = HeatmapDescriptions(
            text = { id, args -> getString(id, *args.toTypedArray()) },
            plural = { id, quantity, args -> resources.getQuantityString(id, quantity, *args.toTypedArray()) },
            percent = { percent.format(it) },
        )
        val settings = services.settings
        mode = HeatmapMode.fromStored(local.getString(KEY_MODE, null))
        val defaultLayout = settings.layoutFor(resources.configuration.locales[0].language)
        val bothLanguages = settings.languages.size > 1
        layout = if (bothLanguages) LayoutKind.entries.firstOrNull { storedName(it) == local.getString(KEY_LAYOUT, null) } ?: defaultLayout else defaultLayout

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        setContentView(scroll)

        // Bord à bord imposé dès Android 15 : on laisse la place aux barres système (API 30+ pour les insets typés).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }

        buildContent(content, bothLanguages)
        render()
        services.learned.load { render() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildContent(root: LinearLayout, bothLanguages: Boolean) {
        val heading = TextView(this).apply {
            setText(R.string.heatmap_title)
            setTextAppearance(android.R.style.TextAppearance_Material_Title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isAccessibilityHeading = true
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(infoButton(), LinearLayout.LayoutParams(dp(MIN_TOUCH_DP), dp(MIN_TOUCH_DP)))
        root.addView(row)
        text(root, getString(R.string.pref_heatmap_summary))

        subtitle(root, R.string.heatmap_mode_title)
        radios(
            root,
            HeatmapMode.entries.map { it.stored to modeTitle(it) },
            mode.stored,
        ) { value ->
            mode = HeatmapMode.fromStored(value)
            local.edit().putString(KEY_MODE, mode.stored).apply()
            render()
        }

        if (bothLanguages) {
            subtitle(root, R.string.heatmap_layout_title)
            radios(
                root,
                listOf(
                    storedName(LayoutKind.AZERTY) to R.string.pref_layout_azerty,
                    storedName(LayoutKind.QWERTY) to R.string.pref_layout_qwerty,
                ),
                storedName(layout),
            ) { value ->
                layout = LayoutKind.entries.firstOrNull { storedName(it) == value } ?: layout
                local.edit().putString(KEY_LAYOUT, storedName(layout)).apply()
                render()
            }
        }

        empty = text(root, getString(R.string.heatmap_empty))
        map = HeatmapView(this)
        root.addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        total = text(root, "")
        legend = text(root, "")
        text(root, getString(R.string.heatmap_privacy))
        root.addView(
            Button(this).apply {
                setText(R.string.heatmap_reset_learning)
                minHeight = dp(MIN_TOUCH_DP)
                setOnClickListener { confirmReset() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) },
        )
    }

    private fun modeTitle(mode: HeatmapMode): Int = when (mode) {
        HeatmapMode.DENSITY -> R.string.heatmap_mode_density
        HeatmapMode.OFFSETS -> R.string.heatmap_mode_offsets
        HeatmapMode.ERRORS -> R.string.heatmap_mode_errors
    }

    /** Redessine la carte, la légende et l'état vide d'après les compteurs et le modèle appris du moment. */
    private fun render() {
        if (!::map.isInitialized) return
        val heatmap = services.heatmap
        val taps = heatmap.keys().sumOf { it.taps }
        val hasData = !heatmap.isEmpty
        empty.visibility = if (hasData) View.GONE else View.VISIBLE
        map.visibility = if (hasData) View.VISIBLE else View.GONE
        total.visibility = if (hasData) View.VISIBLE else View.GONE
        legend.visibility = if (hasData) View.VISIBLE else View.GONE
        total.text = resources.getQuantityString(R.plurals.heatmap_total, taps, NumberFormat.getIntegerInstance().format(taps))
        legend.text = when (mode) {
            HeatmapMode.DENSITY -> getString(R.string.heatmap_legend_density)
            HeatmapMode.OFFSETS -> getString(R.string.heatmap_legend_offsets, HeatmapStyle.ARROW_SCALE.toInt())
            HeatmapMode.ERRORS -> getString(R.string.heatmap_legend_errors, KeyHeatmap.MIN_TAPS_FOR_RATE)
        }
        map.show(layout, mode, heatmap, services.learned.data?.spatial, descriptions)
    }

    /**
     * Remise à zéro de l'apprentissage des zones de frappe (modèle spatial) et de la carte, après confirmation.
     * Le dictionnaire personnel, les corrections refusées et les mesures de saisie ne sont pas touchés.
     */
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.heatmap_reset_learning)
            .setMessage(R.string.heatmap_reset_confirm)
            .setPositiveButton(R.string.heatmap_reset_action) { _, _ ->
                services.learned.load { data ->
                    data.spatial.reset()
                    services.learned.saveIfChanged()
                    render()
                }
                services.heatmap.reset()
                services.saveHeatmap()
                render()
                Toast.makeText(this, R.string.heatmap_reset_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // Construction des vues.

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(root: LinearLayout, value: String): TextView = TextView(this).apply {
        text = value
        setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        setPadding(0, dp(4), 0, dp(4))
    }.also(root::addView)

    private fun subtitle(root: LinearLayout, title: Int) {
        root.addView(
            TextView(this).apply {
                setText(title)
                setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
                setPadding(0, dp(12), 0, 0)
            },
        )
    }

    /** Choix exclusif de boutons radio : [options] (valeur, libellé), [selected] coché, [onChange] reçoit la valeur choisie. */
    private fun radios(root: LinearLayout, options: List<Pair<String, Int>>, selected: String, onChange: (String) -> Unit) {
        val group = RadioGroup(this)
        for ((value, title) in options) {
            group.addView(
                RadioButton(this).apply {
                    id = View.generateViewId()
                    setText(title)
                    tag = value
                    minHeight = dp(MIN_TOUCH_DP)
                    isChecked = value == selected
                },
            )
        }
        group.setOnCheckedChangeListener { radio, id ->
            val value = radio.findViewById<View>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            onChange(value)
        }
        root.addView(group)
    }

    /** Bouton (i) de 48 dp : explication détaillée de la carte dans une boîte de dialogue. */
    private fun infoButton(): ImageButton = ImageButton(this).apply {
        setImageResource(R.drawable.ic_info)
        scaleType = ImageView.ScaleType.CENTER
        val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = attributes.getDrawable(0)
        attributes.recycle()
        contentDescription = getString(R.string.pref_info_description, getString(R.string.heatmap_title))
        setOnClickListener {
            AlertDialog.Builder(this@HeatmapActivity)
                .setTitle(R.string.heatmap_title)
                .setMessage(R.string.pref_heatmap_info)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private companion object {
        const val KEY_MODE = "heatmap_mode"
        const val KEY_LAYOUT = "heatmap_layout"
        const val MIN_TOUCH_DP = 48
    }
}
