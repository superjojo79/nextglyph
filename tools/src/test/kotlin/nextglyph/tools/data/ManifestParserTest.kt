package nextglyph.tools.data

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Paths

class ManifestParserTest {

    private val sha = "a".repeat(64)

    /** Champs JSON bruts d'une source valide ; les tests en modifient un à la fois. */
    private fun fields(): LinkedHashMap<String, String> = linkedMapOf(
        "id" to "\"lexique\"",
        "language" to "\"fr\"",
        "need" to "\"a\"",
        "url" to "\"https://example.org/data/lexique.zip\"",
        "sha256" to "\"$sha\"",
        "size" to "10",
        "license" to "\"CC BY-SA 4.0\"",
    )

    private fun obj(fields: Map<String, String>): String =
        fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }

    private fun manifest(vararg sources: String): String = """{"sources":[${sources.joinToString(",")}]}"""

    private fun manifestWith(key: String, rawValue: String): String =
        manifest(obj(fields().also { it[key] = rawValue }))

    private fun assertRejected(json: String, vararg fragments: String) {
        assertThatThrownBy { ManifestParser.parse(json) }
            .isInstanceOf(ManifestException::class.java)
            .hasMessageContainingAll(*fragments)
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `lit une source valide avec tous ses champs`() {
            val sources = ManifestParser.parse(manifest(obj(fields())))

            assertThat(sources).containsExactly(
                DataSource(
                    id = "lexique",
                    language = "fr",
                    needs = setOf(Need.VOCABULARY),
                    url = "https://example.org/data/lexique.zip",
                    sha256 = sha,
                    sizeBytes = 10,
                    license = "CC BY-SA 4.0",
                    fileName = "lexique.zip",
                ),
            )
        }

        @Test
        fun `lit plusieurs sources dans l'ordre du manifeste`() {
            val second = fields().also {
                it["id"] = "\"autre\""
                it["url"] = "\"https://example.org/autre.bz2\""
            }

            val sources = ManifestParser.parse(manifest(obj(fields()), obj(second)))

            assertThat(sources.map { it.id }).containsExactly("lexique", "autre")
        }

        @ParameterizedTest
        @ValueSource(strings = ["a", "b", "c"])
        fun `reconnaît les trois besoins`(code: String) {
            val sources = ManifestParser.parse(manifestWith("need", "\"$code\""))

            assertThat(sources.single().needs.single().code).isEqualTo(code)
        }

        @Test
        fun `reconnaît une combinaison de besoins`() {
            val sources = ManifestParser.parse(manifestWith("need", "\"a+b\""))

            assertThat(sources.single().needs).containsExactly(Need.VOCABULARY, Need.FREQUENCY)
        }

        @Test
        fun `le nom de fichier ignore la query string`() {
            val sources = ManifestParser.parse(manifestWith("url", "\"https://example.org/d/x.zip?version=3\""))

            assertThat(sources.single().fileName).isEqualTo("x.zip")
        }

        @Test
        fun `le manifeste versionné du dépôt est valide`() {
            val text = Files.readString(Paths.get("data", "sources.json"))

            val sources = ManifestParser.parse(text)

            assertThat(sources).isNotEmpty()
            assertThat(sources.map { it.language }).contains("fr", "en")
            assertThat(sources.flatMap { it.needs }).containsAll(Need.entries)
        }

        @Test
        fun `lit les miroirs dans l'ordre et les essaie avant l'url officielle`() {
            val json = manifestWith("mirrors", "[\"https://miroir.example/un/lexique.zip\",\"https://autre.example/lexique.zip\"]")

            val source = ManifestParser.parse(json).single()

            assertThat(source.mirrors).containsExactly("https://miroir.example/un/lexique.zip", "https://autre.example/lexique.zip")
            assertThat(source.urls).containsExactly(
                "https://miroir.example/un/lexique.zip",
                "https://autre.example/lexique.zip",
                "https://example.org/data/lexique.zip",
            )
            assertThat(source.fileName).isEqualTo("lexique.zip")
        }

        @Test
        fun `sans miroir, seule l'url officielle est essayée`() {
            val source = ManifestParser.parse(manifest(obj(fields()))).single()

            assertThat(source.mirrors).isEmpty()
            assertThat(source.urls).containsExactly("https://example.org/data/lexique.zip")
        }
    }

    @Nested
    inner class ErrorCases {

        @ParameterizedTest
        @ValueSource(strings = ["{", "{\"sources\": [", "pas du json {", "{\"sources\": [}"])
        fun `rejette un JSON invalide`(json: String) {
            assertRejected(json, "JSON invalide")
        }

        @ParameterizedTest
        @ValueSource(strings = ["[]", "\"texte\"", "42", "null", ""])
        fun `rejette un manifeste qui n'est pas un objet`(json: String) {
            assertRejected(json, "objet JSON")
        }

        @Test
        fun `rejette un manifeste sans champ sources`() {
            assertRejected("{}", "« sources »", "absent")
        }

        @Test
        fun `rejette un champ sources qui n'est pas un tableau`() {
            assertRejected("{\"sources\": {}}", "« sources »", "tableau")
        }

        @Test
        fun `rejette un manifeste vide`() {
            assertRejected("{\"sources\": []}", "aucune source")
        }

        @Test
        fun `rejette une source qui n'est pas un objet`() {
            assertRejected("{\"sources\": [\"x\"]}", "source n°1", "objet JSON")
        }

        @ParameterizedTest
        @ValueSource(strings = ["id", "language", "need", "url", "sha256", "size", "license"])
        fun `rejette une source dont un champ est manquant`(key: String) {
            val incomplete = fields().also { it.remove(key) }

            assertRejected(manifest(obj(incomplete)), "« $key »", "absent")
        }

        @ParameterizedTest
        @ValueSource(strings = ["id", "language", "need", "url", "sha256", "license"])
        fun `rejette un champ texte qui n'est pas une chaîne`(key: String) {
            assertRejected(manifestWith(key, "5"), "« $key »", "chaîne")
        }

        @ParameterizedTest
        @ValueSource(strings = ["id", "language", "need", "url", "sha256", "license"])
        fun `rejette un champ texte vide ou blanc`(key: String) {
            assertRejected(manifestWith(key, "\"  \""), "« $key »", "vide")
        }

        @ParameterizedTest
        @ValueSource(strings = ["Lexique", "-lexique", "lex ique", "lex_ique", "é"])
        fun `rejette un id invalide`(id: String) {
            assertRejected(manifestWith("id", "\"$id\""), "id", "invalide")
        }

        @ParameterizedTest
        @ValueSource(strings = ["f", "fra", "FR", "f1"])
        fun `rejette un code de langue invalide`(language: String) {
            assertRejected(manifestWith("language", "\"$language\""), "language", "invalide")
        }

        @ParameterizedTest
        @ValueSource(strings = ["d", "a+", "+a", "a+x", "a b", "A", "a,b"])
        fun `rejette un besoin invalide`(need: String) {
            assertRejected(manifestWith("need", "\"$need\""), "need", "invalide")
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "http://example.org/a.zip",
                "ftp://example.org/a.zip",
                "file:///etc/passwd",
                "//example.org/a.zip",
                "example.org/a.zip",
                "https:///a.zip",
            ],
        )
        fun `rejette une url qui n'est pas https avec un hôte`(url: String) {
            assertRejected(manifestWith("url", "\"$url\""), "url", "https://hôte/chemin")
        }

        @Test
        fun `rejette une url syntaxiquement invalide`() {
            assertRejected(manifestWith("url", "\"https://exa mple.org/a.zip\""), "url invalide")
        }

        @ParameterizedTest
        @ValueSource(strings = ["https://example.org", "https://example.org/", "https://example.org/dir/", "https://example.org/..", "https://example.org/a/."])
        fun `rejette une url sans nom de fichier`(url: String) {
            assertRejected(manifestWith("url", "\"$url\""), "nom de fichier")
        }

        @ParameterizedTest
        @ValueSource(strings = ["abc", "e3b0", "0123456789abcdef-0123456789abcdef-0123456789abcdef-0123456789abcdef"])
        fun `rejette un SHA-256 mal formé`(value: String) {
            assertRejected(manifestWith("sha256", "\"$value\""), "sha256 mal formé")
        }

        @Test
        fun `rejette un SHA-256 de 64 caractères non hexadécimaux ou en majuscules`() {
            assertRejected(manifestWith("sha256", "\"${"g".repeat(64)}\""), "sha256 mal formé")
            assertRejected(manifestWith("sha256", "\"${"A".repeat(64)}\""), "sha256 mal formé")
        }

        @Test
        fun `rejette un SHA-256 entouré de texte`() {
            assertRejected(manifestWith("sha256", "\"x$sha\""), "sha256 mal formé")
            assertRejected(manifestWith("sha256", "\"${sha}x\""), "sha256 mal formé")
        }

        @Test
        fun `rejette un size qui n'est pas un nombre`() {
            assertRejected(manifestWith("size", "\"10\""), "« size »", "nombre")
        }

        @ParameterizedTest
        @ValueSource(strings = ["1.5", "1e3", "9223372036854775808"])
        fun `rejette un size qui n'est pas un entier d'octets`(size: String) {
            assertRejected(manifestWith("size", size), "« size »", "entier")
        }

        @ParameterizedTest
        @ValueSource(strings = ["0", "-1"])
        fun `rejette un size nul ou négatif`(size: String) {
            assertRejected(manifestWith("size", size), "« size »", "strictement positif")
        }

        @Test
        fun `rejette deux sources de même id`() {
            val other = fields().also { it["url"] = "\"https://example.org/autre.zip\"" }

            assertRejected(manifest(obj(fields()), obj(other)), "Doublon de id", "lexique")
        }

        @Test
        fun `rejette deux sources qui donneraient le même fichier local`() {
            val other = fields().also { it["id"] = "\"autre\"" }

            assertRejected(manifest(obj(fields()), obj(other)), "Doublon de nom de fichier", "lexique.zip")
        }

        @Test
        fun `le message d'une source invalide désigne son rang ou son id`() {
            val second = fields().also {
                it["id"] = "\"autre\""
                it["url"] = "\"https://example.org/autre.zip\""
                it["size"] = "0"
            }

            assertRejected(manifest(obj(fields()), obj(second)), "source « autre »")
            assertRejected(manifest(obj(fields()), "5"), "source n°2")
        }

        @Test
        fun `rejette un champ mirrors qui n'est pas un tableau`() {
            assertRejected(manifestWith("mirrors", "\"https://miroir.example/lexique.zip\""), "lexique", "mirrors", "tableau")
        }

        @ParameterizedTest
        @ValueSource(strings = ["3", "true", "null", "{}", "[]"])
        fun `rejette un miroir qui n'est pas une chaîne`(element: String) {
            assertRejected(manifestWith("mirrors", "[\"https://miroir.example/lexique.zip\", $element]"), "lexique", "miroir n°2", "chaîne")
        }

        @ParameterizedTest
        @ValueSource(strings = ["http://miroir.example/lexique.zip", "https://miroir.example/", "ftp://miroir.example/lexique.zip"])
        fun `rejette un miroir qui n'est pas une url https vers un fichier`(mirror: String) {
            assertRejected(manifestWith("mirrors", "[\"$mirror\"]"), "lexique", "miroir n°1", "url")
        }

        @Test
        fun `rejette un miroir identique à l'url officielle`() {
            assertRejected(manifestWith("mirrors", "[\"https://example.org/data/lexique.zip\"]"), "lexique", "url officielle")
        }

        @Test
        fun `rejette deux miroirs identiques`() {
            val mirror = "\"https://miroir.example/lexique.zip\""
            assertRejected(manifestWith("mirrors", "[$mirror,$mirror]"), "Doublon", "miroir", "lexique")
        }
    }

    @Nested
    inner class BoundaryValues {

        @Test
        fun `accepte un SHA-256 de exactement 64 caractères`() {
            assertThat(ManifestParser.parse(manifestWith("sha256", "\"$sha\"")).single().sha256).hasSize(64)
        }

        @ParameterizedTest
        @ValueSource(ints = [63, 65])
        fun `rejette un SHA-256 de 63 ou 65 caractères`(length: Int) {
            assertRejected(manifestWith("sha256", "\"${"a".repeat(length)}\""), "sha256 mal formé")
        }

        @Test
        fun `accepte un SHA-256 avec tous les chiffres hexadécimaux`() {
            val all = "0123456789abcdef".repeat(4)

            assertThat(ManifestParser.parse(manifestWith("sha256", "\"$all\"")).single().sha256).isEqualTo(all)
        }

        @Test
        fun `accepte un size de 1 et de Long MAX_VALUE`() {
            assertThat(ManifestParser.parse(manifestWith("size", "1")).single().sizeBytes).isEqualTo(1L)
            assertThat(ManifestParser.parse(manifestWith("size", Long.MAX_VALUE.toString())).single().sizeBytes)
                .isEqualTo(Long.MAX_VALUE)
        }

        @Test
        fun `accepte un id et un code de langue minimaux`() {
            val sources = ManifestParser.parse(manifest(obj(fields().also { it["id"] = "\"x\"" })))

            assertThat(sources.single().id).isEqualTo("x")
        }

        @Test
        fun `accepte un id commençant par un chiffre et contenant des tirets`() {
            assertThat(ManifestParser.parse(manifestWith("id", "\"3-a-1\"")).single().id).isEqualTo("3-a-1")
        }

        @Test
        fun `les trois besoins combinés et dédoublonnés`() {
            val needs = ManifestParser.parse(manifestWith("need", "\"c+a+b+a\"")).single().needs

            assertThat(needs).containsExactly(Need.SENTENCES, Need.VOCABULARY, Need.FREQUENCY)
        }

        @Test
        fun `accepte une licence avec des caractères Unicode`() {
            val license = "Licence « libre » — Ωmega"

            assertThat(ManifestParser.parse(manifestWith("license", "\"$license\"")).single().license).isEqualTo(license)
        }

        @Test
        fun `accepte une seule source et ignore les champs inconnus`() {
            val withExtra = fields().also { it["note"] = "\"libre\"" }

            assertThat(ManifestParser.parse(manifest(obj(withExtra)))).hasSize(1)
        }

        @Test
        fun `le code d'un besoin inconnu n'est pas résolu`() {
            assertThat(Need.fromCode("z")).isNull()
            assertThat(Need.fromCode("")).isNull()
        }

        @Test
        fun `un tableau de miroirs vide équivaut à son absence`() {
            val source = ManifestParser.parse(manifestWith("mirrors", "[]")).single()

            assertThat(source.mirrors).isEmpty()
            assertThat(source.urls).containsExactly(source.url)
        }

        @Test
        fun `un miroir peut porter un autre nom de fichier que l'url officielle`() {
            val source = ManifestParser.parse(manifestWith("mirrors", "[\"https://miroir.example/copie.zip\"]")).single()

            assertThat(source.fileName).isEqualTo("lexique.zip")
            assertThat(source.mirrors).containsExactly("https://miroir.example/copie.zip")
        }
    }
}
