package com.example.gbpusdpredictor

import com.example.gbpusdpredictor.work.ApiKeyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyFormatTest {
    @Test fun cleanStripsSpacesNewlinesAndQuotes() {
        assertEquals("ABCD1234EFGH", ApiKeyFormat.clean("  ABCD1234EFGH \n"))
        assertEquals("ABCD1234EFGH", ApiKeyFormat.clean("\"ABCD1234EFGH\""))
        assertEquals("ABCD1234EFGH", ApiKeyFormat.clean(" 'ABCD1234EFGH' "))
        assertEquals("ABCD1234EFGH", ApiKeyFormat.clean("`ABCD1234EFGH`"))
    }

    @Test fun cleanLeavesAnEmptyStringEmpty() {
        assertEquals("", ApiKeyFormat.clean("   "))
    }

    @Test fun plausibleKeysAreLettersAndDigitsOfSensibleLength() {
        assertTrue(ApiKeyFormat.looksValid("ABCD1234EFGH5678"))
        assertTrue(ApiKeyFormat.looksValid("abcdefgh"))
    }

    @Test fun implausibleTextIsRejected() {
        assertFalse(ApiKeyFormat.looksValid("short"))
        assertFalse(ApiKeyFormat.looksValid("has spaces in it 123"))
        assertFalse(ApiKeyFormat.looksValid("https://www.alphavantage.co/support/#api-key"))
        assertFalse(ApiKeyFormat.looksValid("A".repeat(41)))
    }
}
