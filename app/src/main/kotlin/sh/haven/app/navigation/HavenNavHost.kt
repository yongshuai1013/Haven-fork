package sh.haven.app.navigation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import androidx.activity.compose.LocalActivity
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.app.desktop.DesktopViewModel
import sh.haven.feature.connections.ConnectionsScreen
import sh.haven.feature.connections.ConnectionsViewModel
import sh.haven.feature.keys.KeysScreen
import sh.haven.feature.settings.SettingsScreen
import androidx.compose.ui.unit.dp
import sh.haven.feature.sftp.SftpScreen
import sh.haven.feature.sftp.SftpViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import sh.haven.feature.terminal.TerminalScreen
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun HavenNavHost(
    preferencesRepository: UserPreferencesRepository,
    connectionRepository: ConnectionRepository,
    sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository,
    stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository,
    agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus,
    userMessageBus: sh.haven.core.data.message.UserMessageBus,
) {
    // Desktop multi-session ViewModel — hoisted to nav scope so it survives tab switches
    val desktopViewModel: DesktopViewModel = hiltViewModel()

    // Agent activity audit overlay — opened by the AgentActiveChip on the
    // Connections top bar, dismissed via the screen's back arrow. Lifted
    // here so the chip can deep-link directly instead of jumping to
    // Settings as a fallback. Settings still has its own entry; both
    // surfaces render the same screen.
    var showAgentActivityOverlay by remember { mutableStateOf(false) }

    // Native Wayland desktop — poll compositor state reactively
    var waylandRunning by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            waylandRunning = sh.haven.core.wayland.WaylandBridge.nativeIsRunning()
            kotlinx.coroutines.delay(500)
        }
    }

    // Auto-hide tabs for protocols with no configured connections.
    // Predicates layer on the always-shown Connections + Settings tabs:
    // each per-screen flag short-circuits to true when alwaysShowAllTabs
    // is on, which is the safety net for power users with single-purpose
    // installs that empty repositories.
    val connections by connectionRepository.observeAll()
        .collectAsState(initial = emptyList())
    val sshKeys by sshKeyRepository.observeAll()
        .collectAsState(initial = emptyList())
    val stepCaConfigs by stepCaConfigRepository.observeAll()
        .collectAsState(initial = emptyList())
    val alwaysShowAllTabs by preferencesRepository.alwaysShowAllTabs
        .collectAsState(initial = false)

    val hasTerminalProfiles = connections.any { it.isTerminal }
    // Show Keys once the user has any key/CA config OR any SSH connection
    // to attach a key to — otherwise there's a chicken-and-egg: a fresh
    // SSH connection can't reach key management to generate/import a key
    // (#170). isSsh covers the key-auth-capable profiles.
    val hasKeysOrCaConfigs = sshKeys.isNotEmpty() || stepCaConfigs.isNotEmpty() ||
        connections.any { it.isSsh }

    val screenOrderPref by preferencesRepository.screenOrder
        .collectAsState(initial = emptyList())
    val screens = remember(
        screenOrderPref,
        hasTerminalProfiles,
        hasKeysOrCaConfigs,
        alwaysShowAllTabs,
    ) {
        val ordered = if (screenOrderPref.isNotEmpty()) {
            val byRoute = screenOrderPref.mapNotNull { route ->
                Screen.entries.find { it.route == route }
            }
            val missing = Screen.entries.filter { it !in byRoute }
            byRoute + missing
        } else {
            Screen.entries.toList()
        }
        ordered.filter { screen ->
            if (alwaysShowAllTabs) return@filter true
            when (screen) {
                // Always shown — Connections is the master list, Settings
                // hosts the Always-show-all-tabs toggle (discoverability).
                Screen.Connections, Screen.Settings -> true
                // Always shown: the Desktop tab is the sole entry point to
                // the local-desktop install hub (DesktopManagerScreen), which
                // is useful with no connection at all — same rationale as Sftp
                // below. Hiding it until a desktop connection existed made the
                // local-desktop feature undiscoverable on a fresh install: the
                // installer was reachable only via "Always show all tabs" or
                // after blindly creating a Local Shell first (#215).
                Screen.Desktop -> true
                // SFTP file browser is useful even with no remote storage
                // (local file paths work), so it stays visible.
                Screen.Sftp -> true
                // Terminal hides until there's any SSH/Mosh/ET/Reticulum
                // profile (ConnectionProfile.isTerminal covers them all).
                Screen.Terminal -> hasTerminalProfiles
                // Keys hides until any SSH key or step-ca config exists;
                // user can still reach key management from Settings.
                Screen.Keys -> hasKeysOrCaConfigs
            }
        }
    }
    // Separate mutable list for nav bar visual order during drag (pager untouched)
    val navScreens = remember { mutableStateListOf<Screen>() }
    LaunchedEffect(screens) {
        if (navScreens.toList() != screens) {
            navScreens.clear()
            navScreens.addAll(screens)
        }
    }
    val pagerState = rememberPagerState { screens.size }
    fun pageOf(screen: Screen): Int = screens.indexOf(screen).coerceAtLeast(0)
    val coroutineScope = rememberCoroutineScope()

    // SINGLE SOURCE OF TRUTH for which screen is shown. Render (the pager
    // page), selection (nav-bar / rail highlight) and navigation
    // (requestScreen) all derive from this one Screen value, so they can
    // never drift apart. The previous design tracked position as a raw Int
    // page index against a `screens` list that is both *filtered*
    // (Desktop/Terminal/Keys appear only when relevant) and *user-reorderable*
    // (drag-to-reorder persists an order) — when the set or order changed
    // under it, the same Int aliased to a different screen, so tapping one
    // tab could land on another and the highlight could disagree with the
    // page. Anchoring to the Screen *identity* keeps the page locked to the
    // selection across any set/order change.
    //
    // Initialised from the pager's restored page so a config-change / process
    // restore starts aligned; the settle effect below keeps it in lockstep
    // with manual swipes.
    var selectedScreen by remember {
        mutableStateOf(
            screens.getOrNull(pagerState.currentPage) ?: screens.firstOrNull() ?: Screen.Connections,
        )
    }
    fun requestScreen(screen: Screen) { selectedScreen = screen }
    // RENDER follows SELECTION: re-scroll whenever the selection changes OR
    // the available screens change (filter/reorder), so the visible page is
    // always the selected Screen. Instant scroll (not animated) so a tap
    // never double-jumps through intermediate pages. No-op when the screen
    // isn't present yet (e.g. a tab still gated off, or a deep-link to
    // Desktop before its first session) — adding it mutates `screens`, which
    // re-fires this effect and lands the user there.
    LaunchedEffect(selectedScreen, screens) {
        val idx = screens.indexOf(selectedScreen)
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        }
    }
    // Manual swipes feed the source of truth: when the pager *settles* on a
    // page, adopt that screen as the selection. Keyed on settledPage only
    // (not `screens`) so a pending requestScreen to a not-yet-present tab is
    // never clobbered by a screens-list change before the scroll lands.
    LaunchedEffect(pagerState.settledPage) {
        screens.getOrNull(pagerState.settledPage)?.let { settled ->
            if (settled != selectedScreen) selectedScreen = settled
        }
    }

    // Desktop (VNC/RDP) navigation is collected HERE, at the always-composed
    // nav-host level, rather than inside ConnectionsScreen — so a desktop tab is
    // created the instant the connect emits, no matter which screen is on-screen.
    // This is what makes the lost-connection Retry button and the MCP
    // connect_profile tool reliably open the desktop even when the Connections
    // screen isn't composed (it isn't, from the Desktop tab). (#121)
    val connectionsViewModel: ConnectionsViewModel = hiltViewModel()
    val navigateToVncEvent by connectionsViewModel.navigateToVnc.collectAsState()
    val navigateToRdpEvent by connectionsViewModel.navigateToRdp.collectAsState()
    LaunchedEffect(navigateToVncEvent) {
        navigateToVncEvent?.let { nav ->
            desktopViewModel.addVncSession(
                nav.host, nav.port, nav.password, nav.username,
                sshForward = nav.sshForward,
                sshSessionId = nav.sshSessionId,
                profileId = nav.profileId,
                colorDepth = nav.colorDepth,
            )
            connectionsViewModel.onDesktopNavigated()
            requestScreen(Screen.Desktop)
        }
    }
    LaunchedEffect(navigateToRdpEvent) {
        navigateToRdpEvent?.let { nav ->
            desktopViewModel.addRdpSession(
                nav.host, nav.port, nav.username, nav.password, nav.domain,
                nav.sshForward, nav.sshSessionId, nav.sshProfileId, nav.profileId,
                useNla = nav.useNla,
                colorDepth = nav.colorDepth,
            )
            connectionsViewModel.onDesktopNavigated()
            requestScreen(Screen.Desktop)
        }
    }

    // Debug navigation: scroll pager when DebugReceiver (debug builds only) emits a route
    LaunchedEffect(Unit) {
        DebugNavEvents.requests.collect { route ->
            val target = screens.indexOfFirst { it.route == route }
            if (target >= 0) pagerState.animateScrollToPage(target)
        }
    }

    // Agent UI commands — when an MCP tool posts a navigation verb, switch
    // the pager to the right tab so the user lands where the agent asked.
    // The matching feature ViewModel collects the same bus and adjusts its
    // internal state in parallel.
    LaunchedEffect(agentUiCommandBus, screens) {
        agentUiCommandBus.commands.collect { command ->
            val screen = when (command) {
                is sh.haven.core.data.agent.AgentUiCommand.NavigateToSftpPath -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.OpenConvertDialog -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.FocusTerminalSession -> Screen.Terminal
                is sh.haven.core.data.agent.AgentUiCommand.OpenTerminalSession -> Screen.Terminal
                is sh.haven.core.data.agent.AgentUiCommand.OpenRemoteDesktop -> Screen.Desktop
                is sh.haven.core.data.agent.AgentUiCommand.OpenWaylandDesktop -> Screen.Desktop
                is sh.haven.core.data.agent.AgentUiCommand.RegenerateStepCaCert -> Screen.Keys
                is sh.haven.core.data.agent.AgentUiCommand.OpenInEditor -> Screen.Sftp
                is sh.haven.core.data.agent.AgentUiCommand.ConnectProfile ->
                    // Connect lands the user on the Connections tab so they
                    // can see the connecting → connected status flip.
                    Screen.Connections
            }
            // Reactive: e.g. OpenRemoteDesktop waits for Desktop to appear
            // rather than silently no-op'ing when it's still hidden.
            requestScreen(screen)
        }
    }

    val terminalFontSize by preferencesRepository.terminalFontSize
        .collectAsState(initial = UserPreferencesRepository.DEFAULT_FONT_SIZE)
    // User-picked Nerd Font / custom TTF for the terminal (#123).
    // Null = fall back to bundled Hack Regular.
    val terminalFontPath by preferencesRepository.terminalFontPath
        .collectAsState(initial = null)
    val toolbarLayout by preferencesRepository.toolbarLayout
        .collectAsState(initial = sh.haven.core.data.preferences.ToolbarLayout.DEFAULT)
    val navBlockMode by preferencesRepository.navBlockMode
        .collectAsState(initial = sh.haven.core.data.preferences.NavBlockMode.ALIGNED)
    val toolbarMinKeyWidth by preferencesRepository.toolbarMinButtonWidth
        .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.DEFAULT_TOOLBAR_MIN_BUTTON_WIDTH)
    val showSearchButton by preferencesRepository.showSearchButton
        .collectAsState(initial = false)
    val showCopyOutputButton by preferencesRepository.showCopyOutputButton
        .collectAsState(initial = false)
    val keepScreenOnInTerminal by preferencesRepository.keepScreenOnInTerminal
        .collectAsState(initial = false)
    val mouseInputEnabled by preferencesRepository.mouseInputEnabled
        .collectAsState(initial = true)
    val terminalRightClick by preferencesRepository.terminalRightClick
        .collectAsState(initial = false)
    val tapToPositionCursorOnPrompt by preferencesRepository.terminalTapToPositionCursor
        .collectAsState(initial = false)
    val allowStandardKeyboard by preferencesRepository.allowStandardKeyboard
        .collectAsState(initial = false)
    val rawKeyboardMode by preferencesRepository.rawKeyboardMode
        .collectAsState(initial = false)
    // Custom keyboard mode + per-flag toggles (#115 follow-up).
    // Build a non-null ImeFlagSet only when Custom mode is on; null
    // when off, so TerminalScreen falls through to the preset logic.
    val keyboardCustomMode by preferencesRepository.keyboardCustomMode
        .collectAsState(initial = false)
    val imeFlagNoSuggestions by preferencesRepository.imeFlagNoSuggestions
        .collectAsState(initial = true)
    val imeFlagVisiblePassword by preferencesRepository.imeFlagVisiblePassword
        .collectAsState(initial = true)
    val imeFlagAutoCorrect by preferencesRepository.imeFlagAutoCorrect
        .collectAsState(initial = false)
    val imeFlagFullEditor by preferencesRepository.imeFlagFullEditor
        .collectAsState(initial = false)
    val imeFlagNoExtractUi by preferencesRepository.imeFlagNoExtractUi
        .collectAsState(initial = true)
    val imeFlagNoPersonalizedLearning by preferencesRepository.imeFlagNoPersonalizedLearning
        .collectAsState(initial = true)
    val customKeyboardFlags = if (keyboardCustomMode) {
        sh.haven.core.terminal.ImeFlagSet(
            noSuggestions = imeFlagNoSuggestions,
            visiblePassword = imeFlagVisiblePassword,
            autoCorrect = imeFlagAutoCorrect,
            fullEditor = imeFlagFullEditor,
            noExtractUi = imeFlagNoExtractUi,
            noPersonalizedLearning = imeFlagNoPersonalizedLearning,
        )
    } else null
    val interceptCtrlShiftV by preferencesRepository.interceptCtrlShiftV
        .collectAsState(initial = true)
    val showTerminalTabBar by preferencesRepository.showTerminalTabBar
        .collectAsState(initial = true)
    val desktopInputMode by preferencesRepository.desktopInputMode
        .collectAsState(initial = "DIRECT")

    // Profile ID to focus when navigating to terminal
    var pendingTerminalProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a new session (new tab) on an existing connection
    var pendingNewSessionProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    // Profile ID for opening a local-shell tab (Desktop → Manage shell button,
    // #168). Routed through here rather than the AgentUiCommandBus because
    // TerminalViewModel only collects the bus while TerminalScreen is composed,
    // and the Desktop tab is outside the pager's composition window — the bus
    // event was dropped and no tab opened. HavenNavHost is always composed.
    var pendingLocalShellProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // SMB auto-connect params
    var pendingSmbProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Rclone auto-connect params
    var pendingRcloneProfileId by rememberSaveable { mutableStateOf<String?>(null) }

    // Local-shell open requests from the Desktop → Manage shell button (#168).
    // Always-composed HavenNavHost collects them, sets the pending profile,
    // and animates to the Terminal page; TerminalScreen creates the tab once
    // composed (see pendingLocalShellProfileId).
    LaunchedEffect(desktopViewModel) {
        desktopViewModel.openLocalShellRequests.collect { profileId ->
            pendingLocalShellProfileId = profileId
            requestScreen(Screen.Terminal)
        }
    }

    // Disable pager swipe while terminal text selection is active
    var terminalSelectionActive by remember { mutableStateOf(false) }
    var terminalReorderMode by remember { mutableStateOf(false) }
    var openToolbarConfig by remember { mutableStateOf(false) }

    // Desktop fullscreen hides bottom nav and system bars
    var desktopFullscreen by remember { mutableStateOf(false) }
    // Terminal fullscreen — same chrome behaviour, owned by TerminalScreen (#138)
    var terminalFullscreen by remember { mutableStateOf(false) }
    // Exit a tab's fullscreen mode when the pager settles on a different
    // tab. Without this, switching from a fullscreen Terminal/Desktop to
    // (e.g.) Keys leaves `terminalFullscreen = true`, which disables
    // pager swipe (line 277) and strands the user — they'd swiped here
    // but can no longer swipe back. Reported by maintainer 2026-05-07.
    LaunchedEffect(pagerState.settledPage) {
        val settled = screens.getOrNull(pagerState.settledPage)
        if (settled != Screen.Terminal && terminalFullscreen) {
            terminalFullscreen = false
        }
        if (settled != Screen.Desktop && desktopFullscreen) {
            desktopFullscreen = false
        }
    }
    // Disable pager swipe when VNC/RDP is connected (pinch-to-zoom conflicts)
    var desktopConnected by remember { mutableStateOf(false) }
    // Disable pager swipe when SFTP text editor or image tools are open
    var sftpEditorOpen by remember { mutableStateOf(false) }
    var sftpImageToolOpen by remember { mutableStateOf(false) }

    // Nav bar drag-to-reorder state
    var navDragIndex by remember { mutableIntStateOf(-1) }
    var navDragOffset by remember { mutableFloatStateOf(0f) }
    val navItemLefts = remember { mutableStateMapOf<Int, Float>() }
    val navItemWidths = remember { mutableStateMapOf<Int, Float>() }
    val haptic = LocalHapticFeedback.current

    // Use WindowSizeClass (width >= Medium, i.e. >= 600dp) as the Material3-recommended
    // signal for switching to a side rail.  This avoids triggering the rail on narrow
    // landscape phones where it would eat more width than it saves, while also opting
    // wide-enough portrait tablets into the rail.
    val activity = LocalActivity.current ?: return
    val windowSizeClass = calculateWindowSizeClass(activity)
    val useSideNavigation =
        windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    val pagerContent: @Composable (Modifier) -> Unit = { modifier ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !desktopFullscreen && !terminalFullscreen && !desktopConnected && !terminalSelectionActive && !sftpEditorOpen && !sftpImageToolOpen,
            modifier = modifier,
        ) { page ->
            when (screens[page]) {
                Screen.Connections -> ConnectionsScreen(
                    onNavigateToTerminal = { profileId ->
                        pendingTerminalProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Terminal)
                        }
                    },
                    onNavigateToNewSession = { profileId ->
                        pendingNewSessionProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Terminal)
                        }
                    },
                    // VNC/RDP navigation is handled by the always-composed
                    // collector above (HavenNavHost), not via these callbacks —
                    // see the navigateToVnc/navigateToRdp LaunchedEffects (#121).
                    onNavigateToSmb = { profileId ->
                        pendingSmbProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Sftp)
                        }
                    },
                    onNavigateToRclone = { profileId ->
                        pendingRcloneProfileId = profileId
                        coroutineScope.launch {
                            requestScreen(Screen.Sftp)
                        }
                    },
                    onNavigateToWayland = {
                        desktopViewModel.addWaylandTab()
                        coroutineScope.launch {
                            requestScreen(Screen.Desktop)
                        }
                    },
                    onNavigateToConnections = {
                        coroutineScope.launch {
                            requestScreen(Screen.Connections)
                        }
                    },
                    onNavigateToAgentActivity = { showAgentActivityOverlay = true },
                    workspaceSection = { sh.haven.app.workspace.ui.WorkspaceSection() },
                )
                Screen.Terminal -> {
                    TerminalScreen(
                        navigateToProfileId = pendingTerminalProfileId,
                        newSessionProfileId = pendingNewSessionProfileId,
                        openLocalShellProfileId = pendingLocalShellProfileId,
                        isActive = pagerState.settledPage == pageOf(Screen.Terminal),
                        fontSize = terminalFontSize,
                        terminalFontPath = terminalFontPath,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        toolbarMinKeyWidth = toolbarMinKeyWidth,
                        showSearchButton = showSearchButton,
                        showCopyOutputButton = showCopyOutputButton,
                        keepScreenOnInTerminal = keepScreenOnInTerminal,
                        mouseInputEnabled = mouseInputEnabled,
                        terminalRightClick = terminalRightClick,
                        tapToPositionCursorOnPrompt = tapToPositionCursorOnPrompt,
                        allowStandardKeyboard = allowStandardKeyboard,
                        onToggleStandardKeyboard = {
                            coroutineScope.launch {
                                preferencesRepository.setAllowStandardKeyboard(!allowStandardKeyboard)
                            }
                        },
                        rawKeyboardMode = rawKeyboardMode,
                        customKeyboardFlags = customKeyboardFlags,
                        onToggleRawKeyboard = {
                            coroutineScope.launch {
                                preferencesRepository.setRawKeyboardMode(!rawKeyboardMode)
                            }
                        },
                        interceptCtrlShiftV = interceptCtrlShiftV,
                        showTabBar = showTerminalTabBar,
                        onFullscreenChanged = { terminalFullscreen = it },
                        onNavigateToConnections = {
                            coroutineScope.launch {
                                requestScreen(Screen.Connections)
                            }
                        },
                        onNavigateToVnc = { host, port, username, password, sshForward, sshSessionId, colorDepth ->
                            desktopViewModel.addVncSession(
                                host, port, password, username,
                                sshForward = sshForward,
                                sshSessionId = sshSessionId,
                                colorDepth = colorDepth,
                            )
                            coroutineScope.launch {
                                requestScreen(Screen.Desktop)
                            }
                        },
                        onSelectionActiveChanged = { terminalSelectionActive = it },
                        onReorderModeChanged = { terminalReorderMode = it },
                        onToolbarLayoutChanged = { newLayout ->
                            coroutineScope.launch {
                                preferencesRepository.setToolbarLayout(newLayout)
                            }
                        },
                        onOpenToolbarSettings = {
                            openToolbarConfig = true
                            coroutineScope.launch {
                                requestScreen(Screen.Settings)
                            }
                        },
                        onNavigateToSftp = {
                            coroutineScope.launch {
                                requestScreen(Screen.Sftp)
                            }
                        },
                        terminalModifier = Modifier.pagerSwipeOverride(
                            pagerState, coroutineScope,
                            isSelectionActive = { terminalSelectionActive || terminalReorderMode },
                        ),
                    )
                    // Clear pending IDs after the terminal has had a chance to consume them.
                    // Use a small delay to avoid cancelling the LaunchedEffect in TerminalScreen
                    // that calls selectTabByProfileId (which may need up to 5s to find the tab).
                    LaunchedEffect(pendingTerminalProfileId) {
                        if (pendingTerminalProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingTerminalProfileId = null
                        }
                    }
                    LaunchedEffect(pendingNewSessionProfileId) {
                        if (pendingNewSessionProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingNewSessionProfileId = null
                        }
                    }
                    LaunchedEffect(pendingLocalShellProfileId) {
                        if (pendingLocalShellProfileId != null) {
                            kotlinx.coroutines.delay(6000)
                            pendingLocalShellProfileId = null
                        }
                    }
                }
                Screen.Desktop -> {
                    // Auto-add Wayland tab when compositor is running
                    LaunchedEffect(waylandRunning) {
                        if (waylandRunning) desktopViewModel.addWaylandTab()
                        else desktopViewModel.removeWaylandTab()
                    }
                    DesktopScreen(
                        desktopViewModel = desktopViewModel,
                        toolbarLayout = toolbarLayout,
                        navBlockMode = navBlockMode,
                        inputMode = desktopInputMode,
                        onSetInputMode = { mode ->
                            coroutineScope.launch { preferencesRepository.setDesktopInputMode(mode) }
                        },
                        isActive = pagerState.settledPage == pageOf(Screen.Desktop),
                        fullscreen = desktopFullscreen,
                        onFullscreenChanged = { desktopFullscreen = it },
                        onConnectedChanged = { desktopConnected = it },
                    )
                }
                Screen.Sftp -> {
                    // Share one SftpViewModel instance between the flick
                    // handler (which needs to read currentPath + call
                    // navigateUp) and the SftpScreen itself. hiltViewModel()
                    // returns the same instance within a composition scope.
                    val sftpViewModel: SftpViewModel = hiltViewModel()
                    val sftpCurrentPath by sftpViewModel.currentPath.collectAsState()
                    val sftpSelectedPaths by sftpViewModel.selectedPaths.collectAsState()
                    val sftpModifier = Modifier
                        .fillMaxSize()
                        .pagerSwipeOverride(
                            pagerState = pagerState,
                            scope = coroutineScope,
                            isSelectionActive = { sftpSelectedPaths.isNotEmpty() || sftpEditorOpen || sftpImageToolOpen },
                            onFlick = { rightward ->
                                // Fast rightward flick inside a subdirectory
                                // navigates up one level instead of switching
                                // tabs (#89).
                                val path = sftpCurrentPath
                                if (rightward && path != "/" && path.isNotEmpty()) {
                                    sftpViewModel.navigateUp()
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                    SftpScreen(
                        pendingSmbProfileId = pendingSmbProfileId,
                        pendingRcloneProfileId = pendingRcloneProfileId,
                        onEditorOpenChanged = { sftpEditorOpen = it },
                        onImageToolOpenChanged = { sftpImageToolOpen = it },
                        sftpModifier = sftpModifier,
                        onAttachFinished = {
                            // Once the picker resolves (confirm or cancel)
                            // bring the user back to the Terminal page so the
                            // shell-quoted path lands at their cursor where
                            // they can see it.
                            coroutineScope.launch {
                                requestScreen(Screen.Terminal)
                            }
                        },
                        viewModel = sftpViewModel,
                    )
                    LaunchedEffect(pendingSmbProfileId) {
                        if (pendingSmbProfileId != null) {
                            pendingSmbProfileId = null
                        }
                    }
                    LaunchedEffect(pendingRcloneProfileId) {
                        if (pendingRcloneProfileId != null) {
                            pendingRcloneProfileId = null
                        }
                    }
                }
                Screen.Keys -> KeysScreen()
                Screen.Settings -> {
                    SettingsScreen(
                        openToolbarConfig = openToolbarConfig,
                        onToolbarConfigConsumed = { openToolbarConfig = false },
                    )
                }
            }
        }
    }

    // App-global user-message host: any ViewModel can post to userMessageBus
    // and it shows here, over EVERY screen (the Scaffold is the root, the pager
    // is its content). Fixes errors being lost to a screen-scoped snackbar once
    // the connect path navigates away (#215 follow-up).
    val globalSnackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(userMessageBus) {
        userMessageBus.messages.collect { msg ->
            // Simple dedupe: skip if identical to the currently-shown message.
            if (globalSnackbarHostState.currentSnackbarData?.visuals?.message != msg.text) {
                globalSnackbarHostState.showSnackbar(
                    message = msg.text,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        snackbarHost = { SnackbarHost(globalSnackbarHostState) },
        bottomBar = {
            if (!desktopFullscreen && !terminalFullscreen && !useSideNavigation) {
                NavigationBar {
                    navScreens.forEachIndexed { index, screen ->
                        val isDragged = index == navDragIndex
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                            label = {
                                // maxLines = 1 + ellipsis guards against any locale whose
                                // nav label is too long for the tab slot on narrow devices
                                // (e.g. Samsung S23 portrait with 5 tabs). Previously the
                                // label could wrap to two lines and distort the whole bar
                                // height — see #78.
                                Text(
                                    text = stringResource(screen.labelRes),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    softWrap = false,
                                )
                            },
                            selected = screen == selectedScreen,
                            onClick = {
                                // Don't navigate while a drag-reorder is in
                                // flight. Otherwise just set the selection —
                                // the render effect scrolls the pager to it.
                                if (navDragIndex < 0 && screens.contains(screen)) {
                                    requestScreen(screen)
                                }
                            },
                            modifier = Modifier
                                .onGloballyPositioned { coords ->
                                    navItemLefts[index] = coords.positionInParent().x
                                    navItemWidths[index] = coords.size.width.toFloat()
                                }
                                .then(
                                    if (isDragged) {
                                        Modifier
                                            .zIndex(1f)
                                            .offset { IntOffset(navDragOffset.roundToInt(), 0) }
                                    } else {
                                        Modifier
                                    },
                                )
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            navDragIndex = index
                                            navDragOffset = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, offset ->
                                            change.consume()
                                            navDragOffset += offset.x
                                            val di = navDragIndex
                                            if (di < 0) return@detectDragGesturesAfterLongPress
                                            val myLeft = navItemLefts[di] ?: return@detectDragGesturesAfterLongPress
                                            val myW = navItemWidths[di] ?: return@detectDragGesturesAfterLongPress
                                            val myCenter = myLeft + myW / 2 + navDragOffset
                                            if (di < navScreens.size - 1) {
                                                val nextLeft = navItemLefts[di + 1] ?: 0f
                                                val nextW = navItemWidths[di + 1] ?: 0f
                                                if (myCenter > nextLeft + nextW / 2) {
                                                    val item = navScreens.removeAt(di)
                                                    navScreens.add(di + 1, item)
                                                    navDragOffset -= nextW
                                                    navDragIndex = di + 1
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                            if (di > 0) {
                                                val prevLeft = navItemLefts[di - 1] ?: 0f
                                                val prevW = navItemWidths[di - 1] ?: 0f
                                                if (myCenter < prevLeft + prevW / 2) {
                                                    val item = navScreens.removeAt(di)
                                                    navScreens.add(di - 1, item)
                                                    navDragOffset += prevW
                                                    navDragIndex = di - 1
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            navDragIndex = -1
                                            navDragOffset = 0f
                                            coroutineScope.launch {
                                                preferencesRepository.setScreenOrder(navScreens.map { it.route })
                                            }
                                        },
                                        onDragCancel = {
                                            navDragIndex = -1
                                            navDragOffset = 0f
                                            coroutineScope.launch {
                                                preferencesRepository.setScreenOrder(navScreens.map { it.route })
                                            }
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (useSideNavigation) {
            Row(
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            ) {
                if (!desktopFullscreen && !terminalFullscreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        // Scrollable so the items never clip off the bottom on
                        // short landscape heights — a plain NavigationRail
                        // centres its items and silently cuts off the last ones
                        // when they don't all fit, which reads as "a tab is
                        // gone" (cf. issue #179's auto-hide confusion).
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        navScreens.forEach { screen ->
                            val pageIndex = screens.indexOf(screen)
                            if (pageIndex >= 0) {
                                NavigationRailItem(
                                    icon = { Icon(screen.icon, contentDescription = stringResource(screen.labelRes)) },
                                    label = {
                                        Text(
                                            text = stringResource(screen.labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            softWrap = false,
                                        )
                                    },
                                    alwaysShowLabel = false,
                                    selected = screen == selectedScreen,
                                    onClick = { requestScreen(screen) },
                                )
                            }
                        }
                        }
                    }
                }
                pagerContent(Modifier.weight(1f))
            }
        } else {
            pagerContent(
                Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
                    .imePadding(),
            )
        }
    }

    // Agent-activity audit overlay — rendered above the pager when the
    // chip on the Connections top bar (or any future deep-link) toggles
    // it on. Dismissed via the screen's back arrow.
    if (showAgentActivityOverlay) {
        sh.haven.feature.settings.AgentActivityScreen(
            onBack = { showAgentActivityOverlay = false },
        )
    }
}

/**
 * Intercepts all gestures on [PointerEventPass.Initial] to prevent the
 * HorizontalPager's built-in scroll from stealing the touch. Horizontal
 * drags are forwarded to the [PagerState] programmatically; all other
 * gestures (vertical scroll, selection, hold) are consumed on Initial
 * so the pager never intercepts them — Terminal.kt handles them on Main.
 *
 * When [isSelectionActive] returns true, horizontal forwarding is suppressed
 * so selection drag isn't misinterpreted as a tab swipe.
 */
private fun Modifier.pagerSwipeOverride(
    pagerState: PagerState,
    scope: CoroutineScope,
    isSelectionActive: () -> Boolean = { false },
    /**
     * Optional per-page flick handler. Called at gesture release if the
     * horizontal drag qualifies as a fast flick (velocity past the
     * internal threshold). Return true if the flick was handled
     * (e.g. SFTP navigated up one directory): the pager will snap back
     * to its current page instead of page-switching.
     *
     * `rightward = true` when totalX > 0 (drag went left→right).
     */
    onFlick: (rightward: Boolean) -> Boolean = { false },
): Modifier = pointerInput(pagerState) {
    val touchSlopPx = viewConfiguration.touchSlop
    val flickVelocityPx = with(density) { 200.dp.toPx() }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var totalX = 0f
        var totalY = 0f
        var decided = false
        var isHorizontal = false

        // Anchor the gesture to the page we started on. During the drag we
        // call dispatchRawDelta, which moves the pager progressively and
        // may tick pagerState.currentPage over to the next page before the
        // finger lifts. Computing the release target from currentPage would
        // then cause a two-page jump. Use startPage as the stable anchor.
        val startPage = pagerState.currentPage
        var selectionInterrupted = false
        val velocityTracker = VelocityTracker().also {
            it.addPosition(down.uptimeMillis, down.position)
        }

        var change: PointerInputChange? = down
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            change = event.changes.firstOrNull() ?: break
            val delta = change.positionChange()
            totalX += delta.x
            totalY += delta.y
            velocityTracker.addPosition(change.uptimeMillis, change.position)

            if (!decided && totalX * totalX + totalY * totalY > touchSlopPx * touchSlopPx) {
                decided = true
                // Require 2:1 horizontal:vertical ratio to trigger page swipe,
                // preventing accidental tab switches during vertical scroll (#40.8)
                isHorizontal = abs(totalX) > abs(totalY) * 2
            }

            // Consume horizontal drags on Initial to prevent the pager's built-in
            // scrollable from intercepting — but let vertical events through so
            // the terminal can handle scrollback and mouse input.
            if (decided && isHorizontal && !isSelectionActive()) {
                change.consume()
            }

            // Forward horizontal drags to pager (unless selection active)
            if (isHorizontal && !selectionInterrupted) {
                if (isSelectionActive()) {
                    // Selection activated mid-swipe — snap pager back
                    selectionInterrupted = true
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage) }
                } else {
                    pagerState.dispatchRawDelta(-delta.x)
                }
            }
        } while (change?.pressed == true)

        if (isHorizontal && !selectionInterrupted && !isSelectionActive()) {
            // Release decision — fast flick first (per-page hook gets a
            // chance to claim it), then fall back to standard page-threshold
            // behaviour.
            val releaseVelocity = velocityTracker.calculateVelocity().x
            val isFlick = abs(releaseVelocity) >= flickVelocityPx
            val flickHandled = if (isFlick) onFlick(totalX > 0) else false

            if (flickHandled) {
                // Caller took over — snap pager back to the page we
                // started on (we forwarded some delta to it during the
                // drag, so currentPage may already have ticked over).
                scope.launch { pagerState.animateScrollToPage(startPage) }
            } else {
                val threshold = size.width / 4
                val target = when {
                    totalX < -threshold -> startPage + 1
                    totalX > threshold -> startPage - 1
                    else -> startPage
                }.coerceIn(0, pagerState.pageCount - 1)
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        } else if (selectionInterrupted) {
            // Ensure pager settles on the page we started on
            scope.launch { pagerState.animateScrollToPage(startPage) }
        }
    }
}
