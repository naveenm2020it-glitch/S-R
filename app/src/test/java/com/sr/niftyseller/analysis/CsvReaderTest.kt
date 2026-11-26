package com.sr.niftyseller.analysis

import com.sr.niftyseller.data.CsvReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvReaderTest {
    @Test fun `parses quoted commas escaped quotes and CRLF`() {
        val rows = CsvReader.rows(StringReader("name,note\r\nNIFTY,\"a,b\"\r\nINDEX,\"said \"\"hello\"\"\"\r\n")).toList()
        assertEquals(listOf(listOf("name", "note"), listOf("NIFTY", "a,b"), listOf("INDEX", "said \"hello\"")), rows)
    }

    @Test fun `rejects an unterminated quoted field`() {
        val error = runCatching { CsvReader.rows(StringReader("a,b\n1,\"unfinished")).toList() }.exceptionOrNull()
        assertEquals(true, error is IllegalArgumentException)
    }
}
