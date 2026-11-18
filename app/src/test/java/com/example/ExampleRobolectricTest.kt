package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("VoidShield", appName)
  }

  @Test
  fun `bundled blocklist loads from raw resources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = com.example.blocker.AdBlocklistEngine.fromContext(context)
    org.junit.Assert.assertTrue(engine.ruleCount > 100)
    org.junit.Assert.assertTrue(engine.isBlocked("ad.doubleclick.net"))
  }
}
