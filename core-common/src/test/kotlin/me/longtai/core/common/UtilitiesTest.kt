package me.longtai.core.common

import me.longtai.core.common.barcode.BarcodeCheck
import me.longtai.core.common.codec.Base64Url
import me.longtai.core.common.codec.Hex
import me.longtai.core.common.csv.Csv
import me.longtai.core.common.csv.CsvTable
import me.longtai.core.common.nfc.NdefRecords
import me.longtai.core.common.print.BarcodeSymbology
import me.longtai.core.common.scan.KeyboardWedgeDecoder
import me.longtai.core.common.scan.ScanDebouncer
import me.longtai.core.common.security.PinHasher
import me.longtai.core.common.time.TimeFormats
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UtilitiesTest {

    @Test
    fun `csv parses quotes newlines and bom`() {
        val text = "﻿sku,name,price\r\nA1,\"Apple, red\",10\r\nB2,\"He said \"\"hi\"\"\",20\n\nC3,\"multi\nline\",30"
        val rows = Csv.parse(text)
        assertEquals(4, rows.size)
        assertEquals(listOf("sku", "name", "price"), rows[0])
        assertEquals("Apple, red", rows[1][1])
        assertEquals("He said \"hi\"", rows[2][1])
        assertEquals("multi\nline", rows[3][1])
    }

    @Test
    fun `csv rejects malformed quotes`() {
        assertFailsWith<Csv.ParseException> { Csv.parse("a,\"b") }
        assertFailsWith<Csv.ParseException> { Csv.parse("a,\"b\"c") }
    }

    @Test
    fun `csv write round trips and guards formulas`() {
        val rows = listOf(listOf("name", "note"), listOf("A, B", "=SUM(A1)"), listOf("-5", "-abc"))
        val written = Csv.write(rows)
        val parsed = Csv.parse(written)
        assertEquals("A, B", parsed[1][0])
        assertEquals("'=SUM(A1)", parsed[1][1])
        assertEquals("-5", parsed[2][0])
        assertEquals("'-abc", parsed[2][1])
        val table = CsvTable(parsed)
        assertEquals("=SUM(A1)", table.records[0]["NOTE"])
        assertEquals(listOf("price"), table.missingColumns(listOf("name", "price")))
    }

    @Test
    fun `hex and base64url round trip`() {
        val bytes = byteArrayOf(0x04, 0x1A, 0x7F, -1, 0)
        assertEquals("041A7FFF00", Hex.encode(bytes))
        assertEquals("04:1A:7F:FF:00", Hex.encode(bytes, ":"))
        assertContentEquals(bytes, Hex.decode("04:1a:7f:ff:00"))
        assertNull(Hex.decode("abc"))
        assertEquals("041A7FFF00", Hex.normalize("04 1a 7f ff 00"))
        for (size in 0..10) {
            val data = ByteArray(size) { (it * 37 + 5).toByte() }
            assertContentEquals(data, Base64Url.decode(Base64Url.encode(data)))
        }
        assertEquals("aGVsbG8", Base64Url.encode("hello".toByteArray()))
        assertNull(Base64Url.decode("a+b/"))
    }

    @Test
    fun `pin hash verifies and uses random salt`() {
        val h1 = PinHasher.hash("1234", iterations = 1000)
        val h2 = PinHasher.hash("1234", iterations = 1000)
        assertNotEquals(h1, h2)
        assertTrue(PinHasher.verify("1234", h1))
        assertFalse(PinHasher.verify("1235", h1))
        assertFalse(PinHasher.verify("1234", "garbage"))
        assertTrue(PinHasher.isValidPin("0000"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("123"))
    }

    @Test
    fun `pbkdf2 matches RFC 7914 test vector`() {
        val out = PinHasher.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55AC046E56E3089FEC1691C22544B605F94185216DDE0465E68B9D57C20DACBC" +
                "49CA9CCCF179B645991664B39D77EF317C71B845B1E30BD509112041D3A19783",
            Hex.encode(out),
        )
    }

    @Test
    fun `keyboard wedge emits fast input and ignores human typing`() {
        val d = KeyboardWedgeDecoder(maxInterKeyDelayMs = 50, minLength = 3)
        "4710088".forEachIndexed { i, c -> d.onChar(c, 1000L + i * 5) }
        assertEquals("4710088", d.onEnter(1040))

        "123".forEachIndexed { i, c -> d.onChar(c, 2000L + i * 300) }
        assertNull(d.onEnter(2700))

        d.onChar('9', 3000)
        "ABC".forEachIndexed { i, c -> d.onChar(c, 3500L + i * 5) }
        assertEquals("ABC", d.onEnter(3520))
    }

    @Test
    fun `debouncer drops quick duplicates`() {
        val d = ScanDebouncer(windowMs = 800)
        assertTrue(d.accept("A", 0))
        assertFalse(d.accept("A", 300))
        assertTrue(d.accept("B", 400))
        assertTrue(d.accept("A", 500))
        assertTrue(d.accept("A", 1400))
    }

    @Test
    fun `ndef text and uri decode`() {
        assertEquals("會員A001", NdefRecords.decodeText(NdefRecords.encodeText("會員A001", "zh")))
        assertEquals("https://example.com", NdefRecords.decodeUri(byteArrayOf(0x04) + "example.com".toByteArray()))
    }

    @Test
    fun `gs1 check digits and symbology`() {
        assertTrue(BarcodeCheck.isValidEan13("4710088412345".dropLast(1) + BarcodeCheck.gs1CheckDigit("471008841234")))
        assertTrue(BarcodeCheck.isValidEan13("4006381333931"))
        assertFalse(BarcodeCheck.isValidEan13("4006381333932"))
        assertTrue(BarcodeCheck.isValidEan8("96385074"))
        assertTrue(BarcodeCheck.isValidUpcA("036000291452"))
        assertEquals(BarcodeSymbology.EAN13, BarcodeCheck.symbologyFor("4006381333931"))
        assertEquals(BarcodeSymbology.CODE128, BarcodeCheck.symbologyFor("SKU-001"))
    }

    @Test
    fun `date parsing handles formats and end of day`() {
        val zone = ZoneId.of("Asia/Taipei")
        val start = TimeFormats.parseDateTime("2026-09-24", zone)!!
        val end = TimeFormats.parseDateTime("2026/09/24", zone, endOfDay = true)!!
        assertEquals(TimeFormats.dayRange(LocalDate.of(2026, 9, 24), zone).first, start)
        assertEquals(TimeFormats.dayRange(LocalDate.of(2026, 9, 24), zone).last, end)
        assertEquals("2026-09-24 13:05", TimeFormats.dateTimeShort(TimeFormats.parseDateTime("2026-09-24T13:05", zone)!!, zone))
        assertNull(TimeFormats.parseDateTime("24/09/2026", zone))
    }
}
