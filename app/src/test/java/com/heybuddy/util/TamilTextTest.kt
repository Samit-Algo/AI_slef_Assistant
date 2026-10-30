package com.heybuddy.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** The shared Tamil cleanup, with the real problems seen from Tesseract in Task 2.3. */
class TamilTextTest {

    @Test
    fun removesTheInvisibleJoinerAfterPulli() {
        // As Tesseract wrote it: an invisible zero-width non-joiner after each "்".
        assertEquals("டெமோ அருண் டெஸ்ட்", TamilText.clean("டெமோ அருண்‌ டெஸ்ட்‌"))
    }

    @Test
    fun putsAVowelSignWrittenBeforeItsLetterBackAfterIt() {
        // As Tesseract wrote "தந்தை": the ை came before த (visual order) and after a joiner.
        assertEquals("தந்தை பெயர்", TamilText.clean("தந்‌ைத பெயர்‌"))
    }

    @Test
    fun fixesALeftVowelSignAtTheStartOfAWord() {
        assertEquals("டெமோ", TamilText.clean("ெடமோ"))
    }

    @Test
    fun rebuildsATwoPartVowelSignFromVisualOrder() {
        // ொ is drawn ெ…ா around its letter; in visual order that is ெ, ட, ா.
        assertEquals("டொ", TamilText.clean("ெடா"))
    }

    @Test
    fun makesTwoWaysOfTypingTheSameLetterEqual() {
        val composed = "டோ" // one code point for ோ
        val typedInParts = "டோ" // ே + ா
        assertEquals(TamilText.clean(composed), TamilText.clean(typedInParts))
    }

    @Test
    fun leavesCorrectTamilAndOtherScriptsUnchanged() {
        val correct = listOf("தந்தை பெயர்: டெமோ கணேசன்", "சென்னை - 600004", "TESTA KUMARI", "टेस्टा कुमारी", "கை", "கெட்ட")
        for (text in correct) {
            assertEquals(text, TamilText.clean(text))
        }
    }

    @Test
    fun doesNotMoveASignBeforeALetterThatHasAPulli() {
        // A consonant with a pulli cannot carry a vowel sign, so this is left as it is.
        val text = "ஓெக்"
        assertEquals(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC), TamilText.clean(text))
    }
}
