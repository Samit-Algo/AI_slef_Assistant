package com.heybuddy.ocr.fakedocs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.heybuddy.R
import com.heybuddy.ocr.measure.TextAccuracy

/** The three fake card layouts (Task 2.2). They only resemble real cards in layout. */
enum class SampleStyle(val fileName: String) {
    AADHAAR_STYLE("aadhaar_style"),
    PAN_STYLE("pan_style"),
    LICENCE_STYLE("licence_style"),
}

/** How the fake card was "photographed". */
enum class PhotoLook(val fileName: String) {
    CLEAN("clean"),
    TILTED("tilted"),
    SHADOW("shadow"),
    BLURRED("blurred"),
    LOW_LIGHT("low_light"),
}

/**
 * One key field and its true value.
 *
 * @property label What it is ("Name", "DOB", …).
 * @property value The exact printed value.
 * @property ignoreSpaces True for numbers and codes (OCR often changes their gaps).
 */
data class SampleField(val label: String, val value: String, val ignoreSpaces: Boolean)

/**
 * The answer key: exactly what is printed on the fake card.
 *
 * @property readableLines English and Hindi lines (what ML Kit should read), in reading order.
 * @property tamilLines Tamil lines (for the Tamil engine, Task 2.3).
 * @property fields The key fields.
 */
class AnswerKey(val readableLines: List<String>, val tamilLines: List<String>, val fields: List<SampleField>)

/**
 * One fake sample.
 *
 * @property style Which layout.
 * @property look How it was "photographed".
 * @property picture The image (the caller recycles it).
 * @property answer What is printed on it.
 */
class FakeDocument(val style: SampleStyle, val look: PhotoLook, val picture: Bitmap, val answer: AnswerKey) {
    /** For tables and file names, for example "pan_style_shadow". */
    val name: String
        get() = "${style.fileName}_${look.fileName}"
}

/**
 * Draws the FAKE sample documents for the OCR measurement (owner rules, Task 2.2): clearly fake
 * names and numbers, a large "SAMPLE" watermark, no emblem, logo or government symbol, and no
 * official department names. Test code only; never in the app.
 */
object FakeDocuments {

    /** The watermark text; it is on every sample and is never counted as a reading error. */
    const val WATERMARK = "SAMPLE"

    /**
     * Every style in every look: 15 samples.
     *
     * @return The samples (the caller recycles their pictures).
     */
    fun all(): List<FakeDocument> {
        val samples = mutableListOf<FakeDocument>()
        for (style in SampleStyle.entries) {
            for (look in PhotoLook.entries) {
                samples.add(make(style, look))
            }
        }
        return samples
    }

    /**
     * One sample.
     *
     * @param style Which layout.
     * @param look Which photo look.
     * @return The sample.
     */
    fun make(style: SampleStyle, look: PhotoLook): FakeDocument {
        val card = CardPainter()
        when (style) {
            SampleStyle.AADHAAR_STYLE -> card.drawAadhaarStyle()
            SampleStyle.PAN_STYLE -> card.drawPanStyle()
            SampleStyle.LICENCE_STYLE -> card.drawLicenceStyle()
        }
        card.drawWatermark()
        val picture = PhotoEffects.apply(card.picture, look)
        if (picture !== card.picture) card.picture.recycle()
        return FakeDocument(style, look, picture, card.answerKey(style))
    }
}

/**
 * The app's own plain font (Inter) for English, like the plain fonts on real cards. NOT the phone's
 * default: on the owner's Realme phone that is a decorative font whose "D" looks like "O", which
 * would measure the font, not the reader. Hindi and Tamil letters fall back to the phone's Noto fonts.
 */
internal object SampleFonts {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    val regular: Typeface = checkNotNull(ResourcesCompat.getFont(context, R.font.inter_regular))
    val bold: Typeface = checkNotNull(ResourcesCompat.getFont(context, R.font.inter_semibold))
}

/** Draws one card and writes down every line it prints. */
private class CardPainter {

    val picture: Bitmap = createBitmap(CARD_WIDTH, CARD_HEIGHT)
    private val canvas = Canvas(picture)
    private val readable = mutableListOf<String>()
    private val tamil = mutableListOf<String>()

    init {
        picture.eraseColor(Color.WHITE)
    }

    fun drawAadhaarStyle() {
        drawBand(Color.rgb(240, 150, 60), "SAMPLE IDENTITY CARD", "NOT A REAL DOCUMENT - FOR TESTING ONLY")
        drawPhotoBox()
        var y = FIRST_LINE_Y
        y = text("टेस्टा कुमारी", y)
        y = text("TESTA KUMARI", y, bold = true)
        y = text("டெஸ்டா குமாரி", y)
        y = text("जन्म तिथि / DOB: 14/08/1990", y)
        y = text("महिला / FEMALE", y)
        y = text("Address: 12, SAMPLE STREET, MYLAPORE,", y + GAP)
        y = text("CHENNAI, TAMIL NADU - 600004", y)
        y = text("முகவரி: 12, மாதிரி தெரு, மயிலாப்பூர்,", y)
        text("சென்னை, தமிழ்நாடு - 600004", y)
        centered("9999 4567 1231", NUMBER_Y, NUMBER_SIZE)
    }

    fun drawPanStyle() {
        drawBand(Color.rgb(70, 110, 190), "SAMPLE TAX ID CARD", "NOT A REAL DOCUMENT - FOR TESTING ONLY")
        drawPhotoBox()
        var y = FIRST_LINE_Y
        y = text("नाम / Name", y, size = LABEL_SIZE)
        y = text("SAMPLE RAVI DEMO", y, bold = true)
        y = text("पिता का नाम / Father's Name", y + GAP, size = LABEL_SIZE)
        y = text("SAMPLE MOHAN DEMO", y, bold = true)
        y = text("जन्म की तारीख / Date of Birth", y + GAP, size = LABEL_SIZE)
        y = text("03/11/1985", y, bold = true)
        y = text("खाता संख्या / Account Number", y + GAP, size = LABEL_SIZE)
        text("ABCPD1234Z", y, bold = true, size = NUMBER_SIZE)
    }

    fun drawLicenceStyle() {
        drawBand(Color.rgb(60, 150, 110), "SAMPLE DRIVING LICENCE", "NOT A REAL DOCUMENT - FOR TESTING ONLY")
        drawPhotoBox()
        var y = FIRST_LINE_Y
        y = text("DL No: TN00 20260001234", y, bold = true)
        y = text("Name: DEMO ARUN TEST", y)
        y = text("பெயர்: டெமோ அருண் டெஸ்ட்", y)
        y = text("S/D/W of: DEMO KUMAR TEST", y)
        y = text("DOB: 21-01-1992", y)
        y = text("Address: 45, TEST NAGAR,", y + GAP)
        y = text("COIMBATORE - 641001", y)
        y = text("முகவரி: 45, சோதனை நகர்,", y)
        y = text("கோயம்புத்தூர் - 641001", y)
        text("Valid Till: 20-01-2042", y + GAP)
    }

    /**
     * A large, faint, diagonal "SAMPLE" across the card's right side, fully on the card. Not across the key fields on
     * the left: the first measurement showed a watermark over the PAN number makes the reader miss
     * it, which measured the watermark, not the reader.
     */
    fun drawWatermark() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(WATERMARK_ALPHA, 200, 30, 30)
            textSize = WATERMARK_SIZE
            typeface = SampleFonts.bold
            textAlign = Paint.Align.CENTER
        }
        canvas.save()
        canvas.rotate(WATERMARK_ANGLE, WATERMARK_CENTRE_X, WATERMARK_CENTRE_Y)
        canvas.drawText(FakeDocuments.WATERMARK, WATERMARK_CENTRE_X, WATERMARK_CENTRE_Y + WATERMARK_SIZE / 3, paint)
        canvas.restore()
    }

    fun answerKey(style: SampleStyle): AnswerKey = AnswerKey(readable.toList(), tamil.toList(), fieldsOf(style))

    private fun fieldsOf(style: SampleStyle): List<SampleField> {
        return when (style) {
            SampleStyle.AADHAAR_STYLE -> listOf(
                SampleField("Name", "TESTA KUMARI", ignoreSpaces = false),
                SampleField("DOB", "14/08/1990", ignoreSpaces = false),
                // Made up; its last digit is a correct Verhoeff check digit, like a real number's.
                SampleField("Aadhaar no.", "9999 4567 1231", ignoreSpaces = true),
            )
            SampleStyle.PAN_STYLE -> listOf(
                SampleField("Name", "SAMPLE RAVI DEMO", ignoreSpaces = false),
                SampleField("DOB", "03/11/1985", ignoreSpaces = false),
                SampleField("PAN", "ABCPD1234Z", ignoreSpaces = true),
            )
            SampleStyle.LICENCE_STYLE -> listOf(
                SampleField("Name", "DEMO ARUN TEST", ignoreSpaces = false),
                SampleField("DOB", "21-01-1992", ignoreSpaces = false),
                SampleField("DL no.", "TN00 20260001234", ignoreSpaces = true),
            )
        }
    }

    private fun drawBand(color: Int, title: String, subtitle: String) {
        val band = Paint().apply { this.color = color }
        canvas.drawRect(0f, 0f, CARD_WIDTH.toFloat(), BAND_HEIGHT, band)
        val titlePaint = textPaint(TITLE_SIZE, bold = true).apply { this.color = Color.WHITE }
        canvas.drawText(title, MARGIN, TITLE_Y, titlePaint)
        readable.add(title)
        val subtitlePaint = textPaint(SUBTITLE_SIZE, bold = false).apply { this.color = Color.WHITE }
        canvas.drawText(subtitle, MARGIN, SUBTITLE_Y, subtitlePaint)
        readable.add(subtitle)
    }

    /** A plain grey box where a real card has the holder's photo. No face, no symbol. */
    private fun drawPhotoBox() {
        val paint = Paint().apply { color = Color.rgb(205, 210, 215) }
        canvas.drawRoundRect(RectF(MARGIN, PHOTO_TOP, MARGIN + PHOTO_WIDTH, PHOTO_TOP + PHOTO_HEIGHT), PHOTO_CORNER, PHOTO_CORNER, paint)
    }

    /**
     * Draws one line whose TOP is at [top] (so a bigger line never runs into the one above) and
     * records it; returns the next line's top.
     */
    private fun text(line: String, top: Float, bold: Boolean = false, size: Float = TEXT_SIZE): Float {
        canvas.drawText(line, TEXT_X, top + size, textPaint(size, bold))
        record(line)
        return top + size * LINE_SPACING
    }

    private fun centered(line: String, y: Float, size: Float) {
        val paint = textPaint(size, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText(line, CARD_WIDTH / 2f, y, paint)
        record(line)
    }

    private fun record(line: String) {
        if (TextAccuracy.containsTamil(line)) tamil.add(line) else readable.add(line)
    }

    private fun textPaint(size: Float, bold: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 25, 30)
            textSize = size
            typeface = if (bold) SampleFonts.bold else SampleFonts.regular
        }
    }

    private companion object {
        // A card's real proportions (85.6 x 54 mm) at a resolution like a good phone photo of it.
        const val CARD_WIDTH = 1600
        const val CARD_HEIGHT = 1010
        const val MARGIN = 50f
        const val BAND_HEIGHT = 150f
        const val TITLE_Y = 75f
        const val SUBTITLE_Y = 125f
        const val TITLE_SIZE = 44f
        const val SUBTITLE_SIZE = 28f
        const val PHOTO_TOP = 200f
        const val PHOTO_WIDTH = 300f
        const val PHOTO_HEIGHT = 380f
        const val PHOTO_CORNER = 12f
        const val TEXT_X = 400f
        const val FIRST_LINE_Y = 190f
        const val TEXT_SIZE = 40f
        const val LABEL_SIZE = 30f
        const val NUMBER_SIZE = 64f
        const val NUMBER_Y = 960f
        const val LINE_SPACING = 1.45f
        const val GAP = 18f
        const val WATERMARK_SIZE = 170f
        const val WATERMARK_ALPHA = 46
        const val WATERMARK_CENTRE_X = 1225f
        const val WATERMARK_CENTRE_Y = 560f
        const val WATERMARK_ANGLE = -20f
    }
}
