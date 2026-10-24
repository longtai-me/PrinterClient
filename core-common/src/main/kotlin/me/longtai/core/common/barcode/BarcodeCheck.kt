package me.longtai.core.common.barcode

import me.longtai.core.common.print.BarcodeSymbology

/** GS1 check-digit validation and symbology selection for printing product codes. */
object BarcodeCheck {

    fun isValidEan13(code: String) = code.length == 13 && code.all { it.isDigit() } && gs1CheckDigit(code.dropLast(1)) == code.last() - '0'

    fun isValidEan8(code: String) = code.length == 8 && code.all { it.isDigit() } && gs1CheckDigit(code.dropLast(1)) == code.last() - '0'

    fun isValidUpcA(code: String) = code.length == 12 && code.all { it.isDigit() } && gs1CheckDigit(code.dropLast(1)) == code.last() - '0'

    /** GS1 mod-10 check digit for the given digits (without the check digit). */
    fun gs1CheckDigit(digits: String): Int {
        require(digits.all { it.isDigit() }) { "digits only" }
        var sum = 0
        digits.reversed().forEachIndexed { index, c ->
            val d = c - '0'
            sum += if (index % 2 == 0) d * 3 else d
        }
        return (10 - sum % 10) % 10
    }

    /** Picks the densest valid symbology; CODE128 encodes anything printable. */
    fun symbologyFor(code: String): BarcodeSymbology = when {
        isValidEan13(code) -> BarcodeSymbology.EAN13
        isValidEan8(code) -> BarcodeSymbology.EAN8
        isValidUpcA(code) -> BarcodeSymbology.UPC_A
        else -> BarcodeSymbology.CODE128
    }

    /** CODE128 can encode ASCII 32..126. */
    fun isPrintableCode128(code: String) = code.isNotEmpty() && code.all { it.code in 32..126 }
}
