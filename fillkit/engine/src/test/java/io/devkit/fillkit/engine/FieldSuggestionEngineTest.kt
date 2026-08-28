package io.devkit.fillkit.engine

import io.devkit.fillkit.FieldMetadata
import io.devkit.fillkit.FillContentHint
import io.devkit.fillkit.FillType
import io.devkit.fillkit.SuggestionConfidence
import io.devkit.fillkit.suggestionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldSuggestionEngineTest {
    private val engine = FieldSuggestionEngine()

    @Test
    fun explicitAndContentHintsAreExact() {
        assertEquals(FillType.Username, engine.suggest(FieldMetadata(explicitFillType = FillType.Username)).single().type)
        val content = engine.suggest(FieldMetadata(contentHint = FillContentHint.Email)).first()
        assertEquals(FillType.Email, content.type)
        assertEquals(SuggestionConfidence.Exact, content.confidence)
        assertTrue(content.reasons.single().description.contains("content type"))
    }

    @Test
    fun commonLabelsAreHighConfidenceAndExplainTheirReason() {
        val result = engine.suggest(FieldMetadata(label = "Business email address")).first()
        assertEquals(FillType.Email, result.type)
        assertEquals(SuggestionConfidence.High, result.confidence)
        assertTrue(result.reasons.first().description.contains("email"))
    }

    @Test
    fun ambiguousCodeReturnsMultipleLowConfidenceChoices() {
        val results = engine.suggest(FieldMetadata(label = "Code"))
        assertEquals(3, results.size)
        assertTrue(results.all { it.confidence == SuggestionConfidence.Low })
        assertTrue(results.any { it.type is FillType.OtpCode })
        assertTrue(results.any { it.type == FillType.PostalCode })
    }

    @Test
    fun customRulePacksExtendPureInference() {
        val rules = suggestionRules("commerce", "Commerce") {
            labelContains("referral", FillType.Custom("referral", String::class), SuggestionConfidence.High)
        }
        val result = FieldSuggestionEngine(listOf(rules)).suggest(FieldMetadata(label = "Referral token")).first()
        assertEquals(FillType.Custom("referral", String::class), result.type)
        assertTrue(result.reasons.first().description.contains("referral"))
    }

    @Test
    fun currentValueShapeIsOnlyMediumConfidence() {
        val result = engine.suggest(FieldMetadata(currentText = "hello@example.com")).first()
        assertEquals(FillType.Email, result.type)
        assertEquals(SuggestionConfidence.Medium, result.confidence)
    }
}
