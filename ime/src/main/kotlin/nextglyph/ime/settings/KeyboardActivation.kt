package nextglyph.ime.settings

/** Détermine si le clavier est activé dans le système. */
object KeyboardActivation {

    /**
     * Vrai si l'une des méthodes de saisie activées ([enabledInputMethodPackages], noms de paquet
     * issus de `InputMethodManager.getEnabledInputMethodList`) appartient à [packageName].
     */
    fun isEnabled(enabledInputMethodPackages: Collection<String>, packageName: String): Boolean =
        packageName in enabledInputMethodPackages
}
