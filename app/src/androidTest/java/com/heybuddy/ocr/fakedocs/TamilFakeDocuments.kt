package com.heybuddy.ocr.fakedocs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap

/**
 * One fake sample with Tamil text, for the Tamil engine measurement (Task 2.3).
 *
 * @property name For tables, for example "tn_certificate_style_tilted".
 * @property picture The image (the caller recycles it).
 * @property tamilLines Every Tamil line printed on it, in reading order.
 * @property fields Key values that must be read exactly (Tamil name, PIN code, date).
 */
class TamilSample(val name: String, val picture: Bitmap, val tamilLines: List<String>, val fields: List<SampleField>)

/**
 * The Tamil samples (owner rules as in Task 2.2: clearly fake, "SAMPLE" watermark, no emblem, logo or
 * department name; test code only). Two new layouts from spec 7.4a, a Tamil Nadu style certificate
 * form and a Tamil name and address block, plus the Tamil lines already on the Aadhaar-style and
 * licence-style cards (owner addition B of Task 2.2).
 */
object TamilFakeDocuments {

    /** The looks of the new layouts: clean, and the two the spec names for a phone photo. */
    private val NEW_LAYOUT_LOOKS = listOf(PhotoLook.CLEAN, PhotoLook.TILTED, PhotoLook.SHADOW)

    /** The cards of Task 2.2 that carry Tamil lines. */
    private val CARDS_WITH_TAMIL = listOf(SampleStyle.AADHAAR_STYLE, SampleStyle.LICENCE_STYLE)

    /**
     * Every Tamil sample: 2 new layouts x 3 looks, and 2 cards x 5 looks. Each is drawn only when
     * asked for, so a memory measurement holds one page at a time, as the app does.
     *
     * @return One maker per sample (the caller recycles each picture).
     */
    fun makers(): List<() -> TamilSample> {
        val makers = mutableListOf<() -> TamilSample>()
        for (look in NEW_LAYOUT_LOOKS) {
            makers.add { certificateStyle(look) }
            makers.add { nameAndAddressBlock(look) }
        }
        for (style in CARDS_WITH_TAMIL) {
            for (look in PhotoLook.entries) {
                makers.add { fromCard(FakeDocuments.make(style, look)) }
            }
        }
        return makers
    }

    /**
     * A Tamil Nadu style certificate form on an A4 page. Its date uses a Tamil month name, which the
     * fact rules of Task 2.6 must understand.
     */
    fun certificateStyle(look: PhotoLook): TamilSample {
        val page = TamilPagePainter(A4_WIDTH, A4_HEIGHT)
        page.drawBand("SAMPLE CERTIFICATE - NOT A REAL DOCUMENT")
        var y = page.firstLineTop
        y = page.text("மாதிரி சான்றிதழ்", y, size = TITLE_SIZE)
        y = page.text("சான்றிதழ் எண்: மாதிரி 2026 0042", y + GAP)
        y = page.text("பெயர்: செல்வி டெஸ்டா குமாரி", y + GAP)
        y = page.text("தந்தை பெயர்: திரு டெமோ ராமன்", y)
        y = page.text("பிறந்த தேதி: 14/08/1990", y)
        y = page.text("முகவரி: 12, மாதிரி தெரு, மயிலாப்பூர்,", y + GAP)
        y = page.text("சென்னை - 600004", y)
        y = page.text("வட்டம்: மயிலாப்பூர்", y + GAP)
        y = page.text("மாவட்டம்: சென்னை", y)
        y = page.text("வழங்கிய நாள்: 05 ஜனவரி 2026", y + GAP)
        page.text("இது ஒரு மாதிரி ஆவணம் மட்டுமே", y + GAP)
        page.drawWatermark()
        val fields = listOf(
            SampleField("Tamil name", "டெஸ்டா குமாரி", ignoreSpaces = false),
            SampleField("PIN", "600004", ignoreSpaces = true),
            SampleField("Date", "14/08/1990", ignoreSpaces = false),
        )
        return finish("tn_certificate_style", look, page, fields)
    }

    /** A card-sized Tamil name and address block, as on a letter or a form. */
    fun nameAndAddressBlock(look: PhotoLook): TamilSample {
        val page = TamilPagePainter(CARD_WIDTH, CARD_HEIGHT)
        page.drawBand("SAMPLE ADDRESS - NOT A REAL DOCUMENT")
        var y = page.firstLineTop
        y = page.text("பெயர்: டெமோ செல்வம்", y)
        y = page.text("தந்தை பெயர்: டெமோ கணேசன்", y)
        y = page.text("கதவு எண் 7, சோதனை வீதி,", y + GAP)
        y = page.text("அண்ணா நகர்,", y)
        page.text("மதுரை - 625020", y)
        page.drawWatermark()
        val fields = listOf(
            SampleField("Tamil name", "டெமோ செல்வம்", ignoreSpaces = false),
            SampleField("PIN", "625020", ignoreSpaces = true),
        )
        return finish("tamil_address_block", look, page, fields)
    }

    private fun fromCard(card: FakeDocument): TamilSample {
        val fields = when (card.style) {
            SampleStyle.LICENCE_STYLE -> listOf(
                SampleField("Tamil name", "டெமோ அருண் டெஸ்ட்", ignoreSpaces = false),
                SampleField("PIN", "641001", ignoreSpaces = true),
            )
            else -> listOf(
                SampleField("Tamil name", "டெஸ்டா குமாரி", ignoreSpaces = false),
                SampleField("PIN", "600004", ignoreSpaces = true),
            )
        }
        return TamilSample(card.name, card.picture, card.answer.tamilLines, fields)
    }

    private fun finish(layout: String, look: PhotoLook, page: TamilPagePainter, fields: List<SampleField>): TamilSample {
        val picture = PhotoEffects.apply(page.picture, look)
        if (picture !== page.picture) page.picture.recycle()
        return TamilSample("${layout}_${look.fileName}", picture, page.lines(), fields)
    }

    // An A4 page at 150 dots per inch, like a phone photo of a printed certificate.
    private const val A4_WIDTH = 1240
    private const val A4_HEIGHT = 1754

    // The same card size as the Task 2.2 samples.
    private const val CARD_WIDTH = 1600
    private const val CARD_HEIGHT = 1010
    private const val TITLE_SIZE = 52f
    private const val GAP = 24f
}

/** Draws one plain white page with Tamil lines and writes down every Tamil line it prints. */
private class TamilPagePainter(width: Int, height: Int) {

    val picture: Bitmap = createBitmap(width, height)
    private val canvas = Canvas(picture)
    private val tamil = mutableListOf<String>()

    /** Where the first line under the band starts. */
    val firstLineTop: Float = BAND_HEIGHT + FIRST_LINE_GAP

    init {
        picture.eraseColor(Color.WHITE)
    }

    /** A plain coloured band with an English "not real" note. No emblem, no department name. */
    fun drawBand(note: String) {
        val band = Paint().apply { color = Color.rgb(120, 90, 160) }
        canvas.drawRect(0f, 0f, picture.width.toFloat(), BAND_HEIGHT, band)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = NOTE_SIZE
            typeface = SampleFonts.bold
        }
        canvas.drawText(note, MARGIN, BAND_TEXT_Y, paint)
    }

    /** Draws one line whose top is at [top]; returns the next line's top. */
    fun text(line: String, top: Float, size: Float = TEXT_SIZE): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 25, 30)
            textSize = size
            // Tamil letters come from the phone's Tamil font; digits and "/" from Inter.
            typeface = SampleFonts.regular
        }
        canvas.drawText(line, MARGIN, top + size, paint)
        tamil.add(line)
        return top + size * LINE_SPACING
    }

    /** The same faint diagonal "SAMPLE" as on the cards, on the right side. */
    fun drawWatermark() {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(WATERMARK_ALPHA, 200, 30, 30)
            textSize = WATERMARK_SIZE
            typeface = SampleFonts.bold
            textAlign = Paint.Align.CENTER
        }
        val centreX = picture.width * WATERMARK_X_SHARE
        val centreY = picture.height * WATERMARK_Y_SHARE
        canvas.save()
        canvas.rotate(WATERMARK_ANGLE, centreX, centreY)
        canvas.drawText(FakeDocuments.WATERMARK, centreX, centreY, paint)
        canvas.restore()
    }

    fun lines(): List<String> = tamil.toList()

    private companion object {
        const val BAND_HEIGHT = 110f
        const val BAND_TEXT_Y = 70f
        const val NOTE_SIZE = 34f
        const val FIRST_LINE_GAP = 50f
        const val MARGIN = 70f
        const val TEXT_SIZE = 40f
        const val LINE_SPACING = 1.6f
        const val WATERMARK_SIZE = 170f
        const val WATERMARK_ALPHA = 46
        const val WATERMARK_ANGLE = -20f

        // Right of centre and low, away from where the lines start on the left.
        const val WATERMARK_X_SHARE = 0.72f
        const val WATERMARK_Y_SHARE = 0.80f
    }
}
