package com.example.globalcompat.baseline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UserFunctionalValidationTest {
    @Test
    fun `not tested is preserved and does not count as success or failure`() {
        val validation = UserFunctionalValidation()

        assertEquals(UserValidationAnswer.NOT_TESTED, validation.googleAccountLogin)
        assertEquals(UserValidationAnswer.NOT_TESTED, validation.chatGptLoginAndUse)
        assertEquals(UserValidationAnswer.NOT_TESTED, validation.chromeGoogleLogin)
        assertFalse(validation.allSuccessful())
        assertFalse(validation.googleAccountLogin == UserValidationAnswer.NO)
    }
}
