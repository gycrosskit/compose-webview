package io.github.gycrosskit.composewebview

import android.Manifest
import android.content.Intent
import android.provider.MediaStore
import android.webkit.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidWebCapabilitiesTest {
    @Test
    fun `normalizes comma separated mime types`() {
        assertEquals(
            listOf("image/*", "video/mp4", "application/pdf"),
            normalizeWebViewMimeTypes(
                arrayOf(" image/*,video/mp4 ", "APPLICATION/PDF", "image/*"),
            ),
        )
        assertEquals(listOf("*/*"), normalizeWebViewMimeTypes(arrayOf(".jpg", "invalid", "*/*")))
    }

    @Test
    fun `maps only supported web media resources`() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            webViewMediaPermissions(
                arrayOf(
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE,
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                ),
            ),
        )
        assertEquals(null, webViewMediaPermissions(emptyArray()))
        assertEquals(
            null,
            webViewMediaPermissions(
                arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, "unsupported-resource"),
            ),
        )
    }

    @Test
    fun `requests runtime permissions only for direct capture`() {
        assertEquals(listOf(Manifest.permission.CAMERA), capturePermissions(MediaStore.ACTION_IMAGE_CAPTURE))
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            capturePermissions(MediaStore.ACTION_VIDEO_CAPTURE),
        )
        assertEquals(emptyList<String>(), capturePermissions(Intent.ACTION_GET_CONTENT))
    }

    @Test
    fun `describes camera file capture before requesting permission`() {
        val explanation = webPermissionExplanationResources(
            purpose = WebPermissionPurpose.FILE_CAPTURE,
            permissions = listOf(Manifest.permission.CAMERA),
        )

        assertEquals(R.string.web_permission_name_camera, explanation.permissionName)
        assertEquals(R.string.web_permission_scenario_upload_photo, explanation.scenario)
        assertEquals(R.string.web_permission_impact_camera, explanation.impact)
    }

    @Test
    fun `describes camera and microphone media capture before requesting permissions`() {
        val explanation = webPermissionExplanationResources(
            purpose = WebPermissionPurpose.MEDIA_CAPTURE,
            permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        )

        assertEquals(R.string.web_permission_name_camera_microphone, explanation.permissionName)
        assertEquals(R.string.web_permission_scenario_av_call, explanation.scenario)
        assertEquals(R.string.web_permission_impact_camera_microphone, explanation.impact)
    }

    @Test
    fun `requests web permission on first use or when system still allows retry`() {
        assertEquals(
            WebPermissionRequestAction.REQUEST_PERMISSION,
            resolveWebPermissionRequestAction(
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        )
        assertEquals(
            WebPermissionRequestAction.REQUEST_PERMISSION,
            resolveWebPermissionRequestAction(
                requestedBefore = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `offers settings only after system blocks a previous web permission request`() {
        assertEquals(
            WebPermissionRequestAction.SHOW_SETTINGS,
            resolveWebPermissionRequestAction(
                requestedBefore = true,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `maps denied android permissions to shared settings prompt capabilities`() {
        assertEquals(
            setOf(WebViewPermission.CAMERA, WebViewPermission.MICROPHONE),
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                "unsupported.permission",
            ).toWebViewPermissions(),
        )
    }

    @Test
    fun `formats fullscreen video duration without app resources`() {
        assertEquals("00:00", formatVideoTime(Float.NaN))
        assertEquals("01:49", formatVideoTime(109f))
        assertEquals("01:01:01", formatVideoTime(3_661f))
    }
}
