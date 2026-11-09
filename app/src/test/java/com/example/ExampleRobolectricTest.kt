package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Gemini Wake", appName)
  }

  @Test
  fun `verify strictly one launcher activity resolves to MainActivity`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val launcherIntent =
      Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setPackage(context.packageName)
      }

    val resolvedActivities = context.packageManager.queryIntentActivities(launcherIntent, 0)
    assertEquals(
      "Expected strictly 1 LAUNCHER activity for package ${context.packageName}, found: ${resolvedActivities.map { it.activityInfo.name }}",
      1,
      resolvedActivities.size,
    )
    assertEquals("com.example.MainActivity", resolvedActivities.single().activityInfo.name)
  }

  @Test
  fun `verify source and merged debug manifests have strictly one LAUNCHER category and no activity-alias`() {
    val sourceManifest =
      sequenceOf(
          File("src/main/AndroidManifest.xml"),
          File("app/src/main/AndroidManifest.xml"),
        )
        .firstOrNull { it.exists() }

    assertTrue("Source AndroidManifest.xml must exist", sourceManifest != null)
    val sourceXml = sourceManifest!!.readText()
    val launcherRegex = Regex("""android\.intent\.category\.LAUNCHER""")
    val activeSourceLauncherLines =
      sourceXml.lines().filter { line ->
        line.contains("android.intent.category.LAUNCHER") && !line.contains("""tools:node="remove"""")
      }
    assertEquals(
      "Source AndroidManifest.xml must contain strictly 1 active LAUNCHER category",
      1,
      activeSourceLauncherLines.size,
    )
    assertTrue(
      "Source AndroidManifest.xml must include tools:node=\"remove\" guards on secondary intent-filters",
      sourceXml.contains("""tools:node="remove""""),
    )
    assertFalse(
      "Source AndroidManifest.xml must not contain any <activity-alias>",
      sourceXml.contains("<activity-alias"),
    )
    assertFalse(
      "Source AndroidManifest.xml must not contain any AppWidgetProvider (APPWIDGET_UPDATE)",
      sourceXml.contains("android.appwidget.action.APPWIDGET_UPDATE"),
    )
    assertTrue(
      "Source AndroidManifest.xml must strip androidx.activity.ComponentActivity via tools:node=\"remove\"",
      sourceXml.contains("androidx.activity.ComponentActivity") && sourceXml.contains("""tools:node="remove""""),
    )
    assertTrue(
      "Source AndroidManifest.xml must strip androidx.compose.ui.tooling.PreviewActivity via tools:node=\"remove\"",
      sourceXml.contains("androidx.compose.ui.tooling.PreviewActivity") && sourceXml.contains("""tools:node="remove""""),
    )

    val mergedManifest =
      sequenceOf(
          File("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
          File(
            "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
          ),
        )
        .firstOrNull { it.exists() }

    if (mergedManifest != null) {
      val mergedXml = mergedManifest.readText()
      assertEquals(
        "Merged debug AndroidManifest.xml must contain strictly 1 LAUNCHER category",
        1,
        launcherRegex.findAll(mergedXml).count(),
      )
      assertFalse(
        "Merged debug AndroidManifest.xml must not contain any <activity-alias>",
        mergedXml.contains("<activity-alias"),
      )
      assertFalse(
        "Merged debug AndroidManifest.xml must not contain any AppWidgetProvider (APPWIDGET_UPDATE)",
        mergedXml.contains("android.appwidget.action.APPWIDGET_UPDATE"),
      )
      assertFalse(
        "Merged debug AndroidManifest.xml must not contain exported androidx.activity.ComponentActivity",
        mergedXml.contains("""android:name="androidx.activity.ComponentActivity""""),
      )
      assertFalse(
        "Merged debug AndroidManifest.xml must not contain exported androidx.compose.ui.tooling.PreviewActivity",
        mergedXml.contains("""android:name="androidx.compose.ui.tooling.PreviewActivity""""),
      )
    }
  }
}
