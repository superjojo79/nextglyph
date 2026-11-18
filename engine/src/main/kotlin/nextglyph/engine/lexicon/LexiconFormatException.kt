package nextglyph.engine.lexicon

/**
 * Levée quand un lexique binaire est invalide : magic ou version inattendus, fichier
 * tronqué ou suivi d'octets en trop, offset ou valeur hors limites.
 *
 * Le chargement ne vérifie que l'en-tête ; les nœuds sont vérifiés à chaque lecture.
 * Une corruption dans les nœuds se manifeste donc par cette exception au moment de la
 * requête qui la rencontre, jamais par un `IndexOutOfBoundsException` ni une boucle infinie.
 */
class LexiconFormatException(message: String) : RuntimeException(message)
