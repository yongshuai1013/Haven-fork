package sh.haven.feature.connections

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.SshKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.data.repository.PortForwardRepository
import sh.haven.core.data.repository.SshKeyRepository
import sh.haven.core.et.EtSessionManager
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.local.DesktopManager
import sh.haven.core.local.LocalSessionManager
import sh.haven.core.local.ProotManager
import sh.haven.core.mail.MailSessionManager
import sh.haven.core.mosh.MoshSessionManager
import sh.haven.core.reticulum.ReticulumTransport
import sh.haven.core.reticulum.ReticulumSessionManager
import sh.haven.core.smb.SmbSessionManager
import sh.haven.core.rdp.RdpSessionManager
import sh.haven.core.ssh.HostKeyVerifier
import sh.haven.core.ssh.SessionManagerRegistry
import sh.haven.core.ssh.SshSessionManager

/**
 * Tests that disconnect() and deleteConnection() clean up ALL session manager types.
 * Regression tests for the bug where local and SMB sessions survived disconnect/delete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelSessionTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var appContext: Context
    private lateinit var repository: ConnectionRepository
    private lateinit var portForwardRepository: PortForwardRepository
    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var reticulumSessionManager: ReticulumSessionManager
    private lateinit var moshSessionManager: MoshSessionManager
    private lateinit var etSessionManager: EtSessionManager
    private lateinit var smbSessionManager: SmbSessionManager
    private lateinit var localSessionManager: LocalSessionManager
    private lateinit var rdpSessionManager: RdpSessionManager
    private lateinit var mailSessionManager: MailSessionManager
    private lateinit var rcloneSessionManager: sh.haven.core.rclone.RcloneSessionManager
    private lateinit var prootManager: ProotManager
    private lateinit var desktopManager: DesktopManager
    private lateinit var sessionManagerRegistry: SessionManagerRegistry
    private lateinit var sshKeyRepository: SshKeyRepository
    private lateinit var viewModel: ConnectionsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        appContext = mockk(relaxed = true)
        repository = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }
        portForwardRepository = mockk(relaxed = true)
        sshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { hasActiveSessions } returns false
        }
        reticulumSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        moshSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        etSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        smbSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        prootManager = mockk(relaxed = true)
        desktopManager = mockk(relaxed = true) {
            every { desktops } returns MutableStateFlow(emptyMap())
        }
        localSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
            every { prootManager } returns this@ConnectionsViewModelSessionTest.prootManager
            every { desktopManager } returns this@ConnectionsViewModelSessionTest.desktopManager
        }
        rdpSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
            every { activeSessions } returns emptyList()
        }
        mailSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        rcloneSessionManager = mockk(relaxed = true) {
            every { sessions } returns MutableStateFlow(emptyMap())
        }
        sessionManagerRegistry = SessionManagerRegistry(
            ssh = sshSessionManager,
            reticulum = reticulumSessionManager,
            mosh = moshSessionManager,
            et = etSessionManager,
            smb = smbSessionManager,
            local = localSessionManager,
            rdp = rdpSessionManager,
            mail = mailSessionManager,
            rclone = rcloneSessionManager,
            keepAlives = emptySet(),
        )

        sshKeyRepository = mockk(relaxed = true) {
            every { observeAll() } returns flowOf(emptyList())
        }

        viewModel = ConnectionsViewModel(
            appContext = appContext,
            repository = repository,
            portForwardRepository = portForwardRepository,
            sshSessionManager = sshSessionManager,
            sshSessionAttacher = mockk(relaxed = true),
            reticulumSessionManager = reticulumSessionManager,
            moshSessionManager = moshSessionManager,
            etSessionManager = etSessionManager,
            reticulumTransport = mockk(relaxed = true) {
                every { discoveredDestinations } returns kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            },
            smbSessionManager = smbSessionManager,
            rcloneSessionManager = rcloneSessionManager,
            rcloneClient = mockk(relaxed = true),
            fidoAuthenticator = mockk(relaxed = true),
            localSessionManager = localSessionManager,
            mailSessionManager = mailSessionManager,
            sessionManagerRegistry = sessionManagerRegistry,
            sshKeyRepository = sshKeyRepository,
            totpSecretRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            preferencesRepository = mockk(relaxed = true) {
                every { sessionManager } returns flowOf(mockk(relaxed = true) {
                    every { label } returns "None"
                })
            },
            connectionGroupDao = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            sshIdentityRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
                // No identity assigned in these tests: applyTo is a pass-through
                // so the connect path sees the profile's inline credentials.
                coEvery { applyTo(any()) } answers { firstArg() }
            },
            hostKeyVerifier = mockk(relaxed = true),
            connectionLogRepository = mockk(relaxed = true),
            tunnelResolver = mockk(relaxed = true),
            portKnocker = mockk(relaxed = true),
            spaSender = mockk(relaxed = true),
            tunnelConfigRepository = mockk(relaxed = true) {
                every { observeAll() } returns flowOf(emptyList())
            },
            certRenewalGate = mockk(relaxed = true) {
                every { renewing } returns kotlinx.coroutines.flow.MutableStateFlow(null)
            },
            desktopSessionRegistry = mockk(relaxed = true) {
                every { statuses } returns kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
            },
            agentUiCommandBus = mockk(relaxed = true) {
                every { commands } returns kotlinx.coroutines.flow.MutableSharedFlow()
            },
            agentActivityHolder = mockk(relaxed = true) {
                every { activeProfiles } returns MutableStateFlow(emptyMap())
            },
            userMessageBus = sh.haven.core.data.message.UserMessageBus(),
            hostRediscovery = mockk(relaxed = true) {
                coEvery { rediscover(any()) } returns null
            },
            usbipForwarder = mockk(relaxed = true),
            biometricGate = mockk(relaxed = true),
            pendingAuthPromptHolder = mockk(relaxed = true),
            sessionSelectionHolder = mockk(relaxed = true),
            connectionPreflight = mockk(relaxed = true) {
                coEvery { beforeConnect(any()) } answers {
                    sh.haven.core.data.repository.ConnectionPreflight.Result.Proceed(firstArg())
                }
            },
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `disconnect cleans up all session manager types`() {
        viewModel.disconnect("profile1")

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
        // #363: rclone was missing from the registry, so disconnect left
        // the storage card CONNECTED forever.
        verify { rcloneSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `disconnect stops VNC server`() {
        viewModel.disconnect("profile1")

        verify { desktopManager.stopAll() }
    }

    @Test
    fun `deleteConnection cleans up all session manager types`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { sshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { reticulumSessionManager.removeAllSessionsForProfile("profile1") }
        verify { moshSessionManager.removeAllSessionsForProfile("profile1") }
        verify { etSessionManager.removeAllSessionsForProfile("profile1") }
        verify { smbSessionManager.removeAllSessionsForProfile("profile1") }
        verify { localSessionManager.removeAllSessionsForProfile("profile1") }
        verify { rcloneSessionManager.removeAllSessionsForProfile("profile1") }
    }

    @Test
    fun `deleteConnection stops VNC server and deletes from repository`() = runTest {
        viewModel.deleteConnection("profile1")
        testDispatcher.scheduler.advanceUntilIdle()

        verify { desktopManager.stopAll() }
        coVerify { repository.delete("profile1") }
    }

    private fun jumpProfile(authMethods: String) = ConnectionProfile(
        id = "jump", label = "jump", host = "h", username = "u",
        connectionType = "SSH", authMethods = authMethods,
    )

    private fun skKey() = SshKey(
        label = "yk", keyType = "sk-ssh-ed25519@openssh.com",
        privateKeyBytes = ByteArray(0), publicKeyOpenSsh = "", fingerprintSha256 = "",
    )

    // #286: a FIDO ("Any hardware key") jump host has no saved password and no
    // legacy keyId — connectJumpHost authenticates it via the FIDO authenticator,
    // so the pre-check must NOT shadow that with a password prompt.
    @Test
    fun `FIDO jump host with an sk-key enrolled needs no password prompt`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        coEvery { repository.getById("jump") } returns jumpProfile("ANY_HARDWARE_KEY")
        coEvery { sshKeyRepository.getAllDecrypted() } returns listOf(skKey())

        assertNull(viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // Don't over-suppress: "Any hardware key" with no sk-key enrolled has no
    // usable credential, so the prompt IS the fallback path.
    @Test
    fun `Any-hardware-key jump host with no sk-key prompts for password`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        val jp = jumpProfile("ANY_HARDWARE_KEY")
        coEvery { repository.getById("jump") } returns jp
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()

        assertSame(jp, viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }

    // Password-only jump host with nothing saved → prompt (existing behaviour).
    @Test
    fun `password-only jump host with no saved password prompts`() = runTest {
        every { sshSessionManager.getSessionsForProfile("jump") } returns emptyList()
        val jp = jumpProfile("PASSWORD")
        coEvery { repository.getById("jump") } returns jp
        coEvery { sshKeyRepository.getAllDecrypted() } returns emptyList()

        assertSame(jp, viewModel.jumpHostNeedsPasswordPrompt("jump"))
    }
}
