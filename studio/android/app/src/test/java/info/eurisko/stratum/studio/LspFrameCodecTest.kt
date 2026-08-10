package info.eurisko.stratum.studio

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class LspFrameCodecTest {
    @Test
    fun roundTripsUtf8ByByteLength() {
        val bytes = ByteArrayOutputStream()
        LspFrameCodec.write(BufferedOutputStream(bytes), "{\"text\":\"lambda\"}")

        assertEquals("{\"text\":\"lambda\"}", LspFrameCodec.read(ByteArrayInputStream(bytes.toByteArray())))
    }

    @Test
    fun normalizesEvaluationSelection() {
        assertEquals(2 to 3, evaluationRange(TextFieldValue("abcdef", TextRange(2, 5))))
        assertEquals(2 to 3, evaluationRange(TextFieldValue("abcdef", TextRange(5, 2))))
        assertEquals(4 to 0, evaluationRange(TextFieldValue("abcdef", TextRange(4))))
    }
}