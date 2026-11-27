package io.github.mortenfyhn.feltbok

import kotlin.test.Test

/** The upload's answers from Artsobservasjoner, damaged at random (#201). The upload calls these
 *  parsers without a guard, so a damaged answer must not make them throw: a throw ends the export
 *  with a crash. A wifi login page that answers 200 with HTML is the everyday case. */
class DamagedResponsesTest {
    private val user = """[{"type":"sub","value":"x"},{"type":"user_id","value":"58758"}]"""
    private val taxa = """[
        {"taxonId":3681,"speciesGroupId":8,"scientificName":{"name":"Larus canus"},
         "scientificNameSynonyms":[{"name":"Larus brachyrhynchus"}],"vernacularName":{"name":"fiskemåke"}},
        {"taxonId":46560,"speciesGroupId":7,"scientificName":{"name":"Gonepteryx rhamni"},
         "vernacularName":{"name":"sitronsommerfugl"},"vernacularNameSynonyms":[{"name":"sitronsommarfugl"}]}
    ]"""
    private val sighting = """{"id":123456,"siteId":352401}"""
    private val loginPage = "<!DOCTYPE html><html><body>Logg inn på wifi</body></html>"

    @Test
    fun damagedAnswersDoNotThrow() = forSeeds(1000) {
        val _ = listOf(
            parseUserId(oneOf(loginPage, damage(user))),
            parseTaxonId(oneOf(loginPage, damage(taxa)), "Larus canus"),
            parseTaxonByName(oneOf(loginPage, damage(taxa)), "sitronsommerfugl"),
            parseId(oneOf(loginPage, damage(sighting))),
            parseErrorDetail(oneOf(loginPage, damage(sighting))),
        )
    }
}
