package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.ui.theme.AmberDeepTint
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CounterHeroTextStyle
import com.example.ui.theme.CounterMetricTextStyle
import com.example.ui.theme.EmeraldDeepTint
import com.example.ui.theme.MintGlow
import com.example.ui.theme.SlateGlowTint
import com.example.vpn.ProtectionStatus
import java.text.NumberFormat
import kotlinx.coroutines.launch

@Composable
fun ShieldScreen(
  modifier: Modifier = Modifier,
  viewModel: ShieldViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val coroutineScope = rememberCoroutineScope()
  val numberFormatter = remember { NumberFormat.getIntegerInstance() }
  val vpnDeniedMessage = stringResource(R.string.vpn_permission_denied)

  val vpnConsentLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        viewModel.startVpnService()
      } else {
        coroutineScope.launch { snackbarHostState.showSnackbar(vpnDeniedMessage) }
      }
    }

  val launchVpnFlow: () -> Unit = {
    val prepareIntent = VpnService.prepare(context)
    if (prepareIntent != null) {
      vpnConsentLauncher.launch(prepareIntent)
    } else {
      viewModel.startVpnService()
    }
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
      // Continue with VPN authorization whether notification permission was granted or declined.
      launchVpnFlow()
    }

  val onShieldToggle: () -> Unit = {
    when (uiState.protectionStatus) {
      is ProtectionStatus.Active,
      is ProtectionStatus.Connecting -> viewModel.stopVpnService()
      is ProtectionStatus.Idle,
      is ProtectionStatus.Error -> {
        if (
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
              PackageManager.PERMISSION_GRANTED
        ) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          launchVpnFlow()
        }
      }
    }
  }

  val status = uiState.protectionStatus
  LaunchedEffect(status) {
    if (status is ProtectionStatus.Error) {
      snackbarHostState.showSnackbar(status.message)
    }
  }

  val atmosphericGlowColor by
    animateColorAsState(
      targetValue =
        when (status) {
          is ProtectionStatus.Active -> EmeraldDeepTint
          is ProtectionStatus.Connecting -> AmberDeepTint
          is ProtectionStatus.Idle,
          is ProtectionStatus.Error -> SlateGlowTint
        },
      animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
      label = "atmosphereGlow",
    )

  val bgColor = MaterialTheme.colorScheme.background

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = bgColor,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .drawBehind {
            drawRect(color = bgColor)
            drawCircle(
              brush =
                Brush.radialGradient(
                  colors = listOf(atmosphericGlowColor, bgColor.copy(alpha = 0f)),
                  center = Offset(size.width * 0.5f, size.height * 0.38f),
                  radius = size.maxDimension * 0.58f,
                )
            )
          }
          .padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier =
          Modifier.fillMaxSize()
            .widthIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        ShieldHeader(status = status)

        Spacer(modifier = Modifier.height(24.dp))

        ShieldHeroControl(
          status = status,
          onToggle = onShieldToggle,
        )

        Spacer(modifier = Modifier.height(28.dp))

        BlockedCountersSection(
          totalBlocked = uiState.totalBlocked,
          sessionBlocked = uiState.sessionBlocked,
          activeRuleCount = uiState.activeRuleCount,
          formatter = numberFormatter,
        )

        Spacer(modifier = Modifier.height(20.dp))

        ScopeTransparencyBanner()
      }
    }
  }
}

@Composable
private fun ShieldHeader(status: ProtectionStatus) {
  val accentColor by
    animateColorAsState(
      targetValue =
        when (status) {
          is ProtectionStatus.Active -> MaterialTheme.colorScheme.primary
          is ProtectionStatus.Connecting -> AmberSignal
          is ProtectionStatus.Error -> MaterialTheme.colorScheme.error
          is ProtectionStatus.Idle -> MaterialTheme.colorScheme.secondary
        },
      animationSpec = tween(300),
      label = "headerAccent",
    )

  val statusText =
    when (status) {
      is ProtectionStatus.Active -> stringResource(R.string.status_active)
      is ProtectionStatus.Connecting -> stringResource(R.string.status_connecting)
      is ProtectionStatus.Error -> stringResource(R.string.status_error)
      is ProtectionStatus.Idle -> stringResource(R.string.status_idle)
    }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Icon(
          imageVector = Icons.Rounded.Shield,
          contentDescription = null,
          tint = accentColor,
          modifier = Modifier.padding(8.dp).size(22.dp),
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column {
        Text(
          text = stringResource(R.string.app_name),
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
          text = stringResource(R.string.brand_subtitle),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Surface(
      modifier = Modifier.testTag("status_badge"),
      shape = RoundedCornerShape(50),
      color = accentColor.copy(alpha = 0.14f),
      border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f)),
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(
          modifier = Modifier.size(8.dp).clip(CircleShape).background(accentColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = statusText,
          style = MaterialTheme.typography.labelMedium,
          color = accentColor,
        )
      }
    }
  }
}

@Composable
private fun ShieldHeroControl(
  status: ProtectionStatus,
  onToggle: () -> Unit,
) {
  val isActive = status is ProtectionStatus.Active
  val isConnecting = status is ProtectionStatus.Connecting

  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()

  val scale by
    animateFloatAsState(
      targetValue =
        when {
          isPressed -> 0.94f
          isActive -> 1.03f
          else -> 1f
        },
      animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
      label = "shieldScale",
    )

  val primaryRingColor by
    animateColorAsState(
      targetValue =
        when (status) {
          is ProtectionStatus.Active -> MaterialTheme.colorScheme.primary
          is ProtectionStatus.Connecting -> AmberSignal
          is ProtectionStatus.Error -> MaterialTheme.colorScheme.error
          is ProtectionStatus.Idle -> MaterialTheme.colorScheme.secondary
        },
      animationSpec = tween(350),
      label = "ringColor",
    )

  val coreFillColor by
    animateColorAsState(
      targetValue =
        when (status) {
          is ProtectionStatus.Active -> MaterialTheme.colorScheme.primaryContainer
          is ProtectionStatus.Connecting -> AmberDeepTint
          is ProtectionStatus.Idle,
          is ProtectionStatus.Error -> MaterialTheme.colorScheme.surfaceVariant
        },
      animationSpec = tween(350),
      label = "coreFill",
    )

  val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
  val pulseProgress by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 1f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 2400, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "pulseRing",
    )

  val buttonContentDescription =
    when (status) {
      is ProtectionStatus.Active -> stringResource(R.string.cd_shield_toggle_active)
      is ProtectionStatus.Connecting -> stringResource(R.string.cd_shield_toggle_connecting)
      is ProtectionStatus.Idle,
      is ProtectionStatus.Error -> stringResource(R.string.cd_shield_toggle_idle)
    }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.size(236.dp),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.36f

        if (isActive || isConnecting) {
          val wave1Radius = baseRadius + (size.minDimension * 0.14f * pulseProgress)
          val wave1Alpha = (1f - pulseProgress) * 0.42f
          drawCircle(
            color = primaryRingColor.copy(alpha = wave1Alpha),
            radius = wave1Radius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
          )

          val offsetProgress = (pulseProgress + 0.5f) % 1f
          val wave2Radius = baseRadius + (size.minDimension * 0.14f * offsetProgress)
          val wave2Alpha = (1f - offsetProgress) * 0.32f
          drawCircle(
            color = primaryRingColor.copy(alpha = wave2Alpha),
            radius = wave2Radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
          )
        } else {
          drawCircle(
            color = primaryRingColor.copy(alpha = 0.22f),
            radius = baseRadius + 14.dp.toPx(),
            center = center,
            style = Stroke(width = 1.dp.toPx()),
          )
        }
      }

      Surface(
        modifier =
          Modifier.size(164.dp)
            .graphicsLayer {
              scaleX = scale
              scaleY = scale
            }
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(
              interactionSource = interactionSource,
              indication = ripple(bounded = true, color = primaryRingColor),
              role = Role.Switch,
              onClick = onToggle,
            )
            .semantics { contentDescription = buttonContentDescription }
            .testTag("shield_toggle_button"),
        shape = CircleShape,
        color = coreFillColor,
        border = BorderStroke(2.dp, primaryRingColor.copy(alpha = if (isActive) 0.9f else 0.45f)),
        shadowElevation = if (isActive) 12.dp else 4.dp,
      ) {
        Column(
          modifier = Modifier.fillMaxSize().padding(16.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector =
              when (status) {
                is ProtectionStatus.Active -> Icons.Rounded.VerifiedUser
                is ProtectionStatus.Error -> Icons.Rounded.GppMaybe
                is ProtectionStatus.Connecting,
                is ProtectionStatus.Idle -> Icons.Rounded.PowerSettingsNew
              },
            contentDescription = null,
            tint = if (isActive) MintGlow else MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.size(54.dp),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text =
              if (isActive || isConnecting) {
                stringResource(R.string.action_disable_shield)
              } else {
                stringResource(R.string.action_enable_shield)
              },
            style = MaterialTheme.typography.labelLarge,
            color = if (isActive) MintGlow else MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
      text =
        when (status) {
          is ProtectionStatus.Active -> stringResource(R.string.status_detail_active)
          is ProtectionStatus.Connecting -> stringResource(R.string.status_detail_connecting)
          is ProtectionStatus.Error -> status.message
          is ProtectionStatus.Idle -> stringResource(R.string.status_detail_idle)
        },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(horizontal = 12.dp),
    )
  }
}

@Composable
private fun BlockedCountersSection(
  totalBlocked: Long,
  sessionBlocked: Long,
  activeRuleCount: Int,
  formatter: NumberFormat,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().testTag("total_blocked_counter"),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      tonalElevation = 2.dp,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Rounded.Block,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = stringResource(R.string.label_total_blocked),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        AnimatedContent(
          targetState = totalBlocked,
          transitionSpec = {
            (slideInVertically { it / 3 } + fadeIn(tween(180))) togetherWith
              (slideOutVertically { -it / 3 } + fadeOut(tween(150)))
          },
          label = "totalCounterAnim",
        ) { count ->
          Text(
            text = formatter.format(count),
            style = CounterHeroTextStyle,
            color = MaterialTheme.colorScheme.onBackground,
          )
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      MetricTile(
        modifier = Modifier.weight(1f).testTag("session_blocked_counter"),
        label = stringResource(R.string.label_session_blocked),
        value = formatter.format(sessionBlocked),
        accent = MaterialTheme.colorScheme.primary,
      )
      MetricTile(
        modifier = Modifier.weight(1f).testTag("active_rules_count"),
        label = stringResource(R.string.label_rules_loaded),
        value = formatter.format(activeRuleCount),
        accent = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun MetricTile(
  modifier: Modifier = Modifier,
  label: String,
  value: String,
  accent: Color,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalAlignment = Alignment.Start,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        style = CounterMetricTextStyle,
        color = accent,
      )
    }
  }
}

@Composable
private fun ScopeTransparencyBanner() {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = Icons.Rounded.Dns,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 2.dp).size(18.dp),
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column {
        Text(
          text = stringResource(R.string.scope_notice_title),
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.scope_notice_body),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
