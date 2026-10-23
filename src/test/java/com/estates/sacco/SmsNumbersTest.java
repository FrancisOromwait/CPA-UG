package com.estates.sacco;

import com.estates.sacco.service.MessageService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Reading members' phone numbers as they were typed, and counting how many SMS a message takes. */
class SmsNumbersTest {
    @Test
    void theUsualWaysANumberIsTyped() {
        assertEquals("+256772123456", MessageService.ugandanMobile("0772123456"));
        assertEquals("+256772123456", MessageService.ugandanMobile("0772 123 456"));
        assertEquals("+256772123456", MessageService.ugandanMobile("+256 772-123-456"));
        assertEquals("+256772123456", MessageService.ugandanMobile("256772123456"));
        assertEquals("+256772123456", MessageService.ugandanMobile("772123456"));
    }

    @Test
    void twoNumbersInOneBoxTakeTheFirstUsableOne() {
        assertEquals("+256782069817", MessageService.ugandanMobile("0782069817/0702274465"));
        assertEquals("+256704906475", MessageService.ugandanMobile("0704906475/ 0777906474"));
        assertEquals("+256703800796", MessageService.ugandanMobile("077491150/0703800796"));        // the first is a digit short
        assertEquals("+256782604832", MessageService.ugandanMobile("0782604832/041466071"));
    }

    @Test
    void noMobileNumber() {
        assertNull(MessageService.ugandanMobile(null));
        assertNull(MessageService.ugandanMobile(""));
        assertNull(MessageService.ugandanMobile("041466071"));                                     // a landline
        assertNull(MessageService.ugandanMobile("waiswamusa@yahoo.com ; waiswamusa2@gmail.com"));
        assertNull(MessageService.ugandanMobile("071824098"));                                     // a digit short
    }

    @Test
    void smsParts() {
        assertEquals(1, MessageService.smsParts("a".repeat(160)));
        assertEquals(2, MessageService.smsParts("a".repeat(161)));
        assertEquals(3, MessageService.smsParts("a".repeat(307)));
        assertEquals(1, MessageService.smsParts("Ssebo " + "a".repeat(60) + " ✓"));            // a tick is outside the plain SMS alphabet: 70 a part
        assertEquals(2, MessageService.smsParts("✓" + "a".repeat(70)));
    }
}
