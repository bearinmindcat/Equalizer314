package com.bearinmind.equalizer314

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.color.MaterialColors
import java.util.Locale

/** Language picker; Reset drops back to the phone's language. */
class LanguageActivity : AppCompatActivity() {

    /** Tag, own name, English name. One line per values-XX folder. */
    private val catalogue = listOf(
        Triple("en", "English", "English"),
        Triple("ru", "Русский", "Russian"),
        Triple("uk", "Українська", "Ukrainian"),
    )

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var countText: TextView
    private var available: List<Triple<String, String, String>> = emptyList()
    private var systemFallback = "English"
    private val density get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_language)

        listContainer = findViewById(R.id.languageListContainer)
        emptyText = findViewById(R.id.languageEmptyText)
        countText = findViewById(R.id.languageCountText)

        // The accent outline marks the pick here and nowhere else.
        findViewById<View>(R.id.languageActiveCard).background = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke((2 * density).toInt(),
                MaterialColors.getColor(listContainer, com.google.android.material.R.attr.colorPrimary))
            cornerRadius = 12 * density
        }

        findViewById<ImageButton>(R.id.languageBackButton).setOnClickListener { finish() }

        systemFallback = systemFallbackName()
        val systemRow = Triple("", getString(R.string.system_default), systemFallback)
        val translations = catalogue.filter { hasTranslation(it.first) }
        available = listOf(systemRow) + translations
        // System default is a choice, not a language, so it is not counted.
        countText.text = resources.getQuantityString(R.plurals.n_languages, translations.size, translations.size)

        findViewById<TextView>(R.id.languageResetButton).setOnClickListener {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        }
        findViewById<EditText>(R.id.languageSearchInput).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        render("")
    }

    private fun resourcesFor(locale: Locale) = createConfigurationContext(
        Configuration(resources.configuration).apply { setLocale(locale) }).resources

    /** res/values-[tag] present? Baseline is explicit English, never the live config, or the active language hides itself. */
    private fun hasTranslation(tag: String): Boolean {
        if (tag == "en") return true
        val probes = intArrayOf(R.string.change_language_and_translations_here, R.string.backup_and_restore, R.string.reset)
        val english = resourcesFor(Locale.ENGLISH)
        val candidate = resourcesFor(Locale.forLanguageTag(tag))
        return probes.any { candidate.getString(it) != english.getString(it) }
    }

    /** Where System default lands: the phone's language if we ship it, else English, since untranslated locales fall through to res/values. */
    private fun systemFallbackName(): String {
        // LocaleManager is reliable for the phone's own language; Resources.getSystem() is not.
        val system = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getSystemService(android.app.LocaleManager::class.java)?.systemLocales
        } else null
        val deviceTag = (system?.takeIf { !it.isEmpty }?.get(0)
            ?: android.content.res.Resources.getSystem().configuration.locales[0]).language
        return catalogue.firstOrNull { it.first == deviceTag && hasTranslation(it.first) }?.third
            ?: catalogue.first().third
    }

    private fun currentTag(): String =
        AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-')

    private fun render(query: String) {
        val q = query.trim().lowercase()
        val shown = available.filter {
            q.isEmpty() || it.second.lowercase().contains(q) ||
                it.third.lowercase().contains(q) || it.first.contains(q)
        }
        listContainer.removeAllViews()
        emptyText.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        for (lang in shown) listContainer.addView(buildRow(lang))
        showActive(currentTag())
    }

    /** Header card naming the pick, like the AutoEQ active-profile card. */
    private fun showActive(tag: String) {
        val picked = available.firstOrNull { it.first == tag }
        val name = picked?.second.orEmpty()
        val sub = picked?.third.orEmpty()
        findViewById<TextView>(R.id.languageActiveName).text = name
        findViewById<TextView>(R.id.languageActiveSub).apply {
            text = sub
            visibility = if (sub.isBlank()) View.GONE else View.VISIBLE
        }
    }

    /** Confirm first, so a mis-tap does not reskin the whole app. */
    private fun confirmSwitch(lang: Triple<String, String, String>) {
        val name = if (lang.first.isEmpty()) getString(R.string.system_default) else lang.second
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (16 * density).toInt())
        }
        view.addView(TextView(this).apply {
            text = getString(R.string.language)
            setTextColor(0xFFE2E2E2.toInt()); textSize = 20f
            setPadding(0, 0, 0, (12 * density).toInt())
        })
        view.addView(TextView(this).apply {
            text = getString(R.string.switch_language_confirm, name)
            setTextColor(0xFFAAAAAA.toInt()); textSize = 14f
            setPadding(0, 0, 0, (16 * density).toInt())
        })
        view.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * density).toInt()
            ).apply { bottomMargin = (12 * density).toInt() }
            setBackgroundColor(0xFF444444.toInt())
        })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun dlgBtn(label: String, color: Int, endMargin: Boolean) =
            com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (endMargin) marginEnd = (3 * density).toInt() else marginStart = (3 * density).toInt()
                }
                cornerRadius = (12 * density).toInt(); setTextColor(color)
                strokeColor = android.content.res.ColorStateList.valueOf(0xFF444444.toInt())
                strokeWidth = (1 * density).toInt()
                setBackgroundColor(0x00000000); insetTop = 0; insetBottom = 0
            }
        val okBtn = dlgBtn(getString(R.string.switch_label), 0xFFDDDDDD.toInt(), endMargin = true)
        val cancelBtn = dlgBtn(getString(R.string.cancel), 0xFFEF9A9A.toInt(), endMargin = false)
        btnRow.addView(okBtn); btnRow.addView(cancelBtn)
        view.addView(btnRow)
        val dialog = android.app.AlertDialog.Builder(this, R.style.Theme_Equalizer314_Dialog)
            .setView(view).create()
        okBtn.setOnClickListener {
            dialog.dismiss()
            // Empty tag is System default: clear the override, do not set a locale.
            AppCompatDelegate.setApplicationLocales(
                if (lang.first.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(lang.first))
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** Same card as the user preset rows; rows never highlight, only the header does. */
    private fun buildRow(lang: Triple<String, String, String>): View {
        val onSurface = MaterialColors.getColor(listContainer, com.google.android.material.R.attr.colorOnSurface)
        val onVariant = MaterialColors.getColor(listContainer, com.google.android.material.R.attr.colorOnSurfaceVariant)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, (4 * density).toInt()) }
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0x00000000)
                setStroke((1 * density).toInt(), 0xFF444444.toInt())
                cornerRadius = 12 * density
            }
            val hPad = (12 * density).toInt(); val vPad = (10 * density).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = true
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = lang.second
            textSize = 16f
            setTextColor(onSurface)
        })
        // Every row carries its English name underneath, English and System default included.
        if (lang.third.isNotBlank()) {
            labels.addView(TextView(this).apply {
                text = lang.third
                textSize = 12f
                setTextColor(onVariant)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        row.addView(labels)
        row.setOnClickListener { confirmSwitch(lang) }
        return row
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }
}
