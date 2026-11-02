package io.github.gycrosskit.composewebview

import android.Manifest
import android.webkit.PermissionRequest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidWebCapabilitiesTest {
    @Test
    fun `normalizes comma separated mime types`() {
        assertEquals(
            listOf("image/*", "video/mp4", "application/pdf"),
            normalizeWebViewMimeTypes(
                arrayOf(" image/*,video/mp4 ", "APPLICATION/PDF", "image/*"),
            ),
        )
        assertEquals(listOf("image/jpeg", "*/*"), normalizeWebViewMimeTypes(arrayOf(".jpg", "invalid", "*/*")))
        assertEquals(listOf("image/jpeg", "video/quicktime", "*/*"), normalizeWebViewMimeTypes(arrayOf("image/jpg,video/mov,*")))
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
        assertEquals(listOf(Manifest.permission.CAMERA), capturePermissions(capture = true, video = false))
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            capturePermissions(capture = true, video = true),
        )
        assertEquals(emptyList<String>(), capturePermissions(capture = false, video = false))
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

}
