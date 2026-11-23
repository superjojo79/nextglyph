package nextglyph.engine.ngram

/**
 * Levée quand un modèle de mot suivant est invalide : magic, version ou étiquette inattendus, fichier
 * tronqué ou suivi d'octets en trop, offset ou identifiant hors limites.
 *
 * Le chargement ne vérifie que l'en-tête et les tailles ; le contenu est vérifié à chaque lecture.
 * Une corruption se manifeste donc par cette exception au moment de la requête qui la rencontre,
 * jamais par un `IndexOutOfBoundsException`.
 */
class NgramFormatException(message: String) : RuntimeException(message)
