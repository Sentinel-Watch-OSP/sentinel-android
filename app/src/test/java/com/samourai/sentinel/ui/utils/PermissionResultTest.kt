package com.samourai.sentinel.ui.utils

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the crash caused by indexing `grantResults[0]` when the
 * permission dialog is cancelled and Android delivers an empty array.
 */
class PermissionResultTest {

    @Test
    fun `empty grantResults is treated as cancelled, not denied`() {
        // This is the case that previously threw ArrayIndexOutOfBoundsException.
        assertEquals(PermissionResult.CANCELLED, permissionResultOf(intArrayOf()))
    }

    @Test
    fun `granted permission is reported as granted`() {
        assertEquals(
            PermissionResult.GRANTED,
            permissionResultOf(intArrayOf(PackageManager.PERMISSION_GRANTED))
        )
    }

    @Test
    fun `denied permission is reported as denied`() {
        assertEquals(
            PermissionResult.DENIED,
            permissionResultOf(intArrayOf(PackageManager.PERMISSION_DENIED))
        )
    }

    @Test
    fun `only the first result is considered for a single permission request`() {
        assertEquals(
            PermissionResult.GRANTED,
            permissionResultOf(
                intArrayOf(
                    PackageManager.PERMISSION_GRANTED,
                    PackageManager.PERMISSION_DENIED
                )
            )
        )
    }
}
