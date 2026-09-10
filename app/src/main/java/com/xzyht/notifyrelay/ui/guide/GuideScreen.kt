package com.xzyht.notifyrelay.ui.guide

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.xzyht.notifyrelay.ui.activity.GuideActivity
import notifyrelay.base.util.GuidePermissionRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class GuideStep {
    WELCOME,
    AGREEMENT,
    REQUIRED_PERMISSIONS,
    OPTIONAL_PERMISSIONS,
    SETTINGS,
    APPEARANCE,
    REMOTE_FILTER,
    LOCAL_FILTER,
    SUPER_ISLAND,
    SCRCPY,
    COMPLETE,
}

@Composable
internal fun GuideScreen(
    permissionRequester: GuidePermissionRequester,
    themeBaseIndex: Int,
    onThemeChanged: (Int) -> Unit,
    onContinue: () -> Unit,
    reauth: Boolean = false,
    needConsent: Boolean = false,
) {
    val context = LocalContext.current
    var permissionState by remember {
        mutableStateOf(GuidePermissionUiState())
    }

    suspend fun refreshPermissions() {
        val state = withContext(Dispatchers.IO) {
            readGuidePermissionState(context)
        }
        permissionState = state
    }

    // 页面首次进入时刷新一次
    LaunchedEffect(Unit) {
        refreshPermissions()
    }

    // GuideActivity.onResume 也会主动触发一次刷新
    val resumeTrigger = GuideActivity.GuideScreen.refreshTrigger
    LaunchedEffect(resumeTrigger) {
        refreshPermissions()
    }

    if (reauth || needConsent) {
        // 简化 3 级页流程：
        // - reauth：仅因包名变更导致授权掉落、无新增权限，不重复同意协议；
        // - needConsent：版本更新新增了声明式权限，必须重新阅读并同意使用须知与授权说明。
        // 简化流程：needConsent 须依次展示「协议页 → 必要权限页 → 完成页」（共 4 页），
        // 仅授权掉落（reauth）则跳过协议页（共 3 页）。
        val reauthPagerState = rememberPagerState(initialPage = 0, pageCount = { if (needConsent) 4 else 3 })
        val reauthScope = rememberCoroutineScope()

        fun reauthAnimateTo(index: Int) {
            reauthScope.launch { reauthPagerState.animateScrollToPage(index) }
        }

        BackHandler(enabled = reauthPagerState.currentPage > 0) {
            reauthAnimateTo(reauthPagerState.currentPage - 1)
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .navigationBarsPadding(),
        ) {
            HorizontalPager(
                state = reauthPagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = false,
            ) { page ->
                when (page) {
                    0 ->
                        GuideWelcomePage(
                            onStart = { reauthAnimateTo(1) },
                            reauth = reauth,
                            needConsent = needConsent,
                        )
                    1 ->
                        if (needConsent) {
                            // 新增声明式权限：必须重新阅读并同意使用须知与授权说明页。
                            GuideAgreementPage(
                                onBack = { reauthAnimateTo(0) },
                                onNext = { reauthAnimateTo(2) },
                                needConsent = needConsent,
                            )
                        } else {
                            // 仅授权掉落：引导重新开启必要权限，不重复同意协议。
                            GuideRequiredPermissionPage(
                                permissionRequester = permissionRequester,
                                permissionState = permissionState,
                                onBack = { reauthAnimateTo(0) },
                                onNext = { reauthAnimateTo(2) },
                                reauth = true,
                            )
                        }
                    2 ->
                        if (needConsent) {
                            // needConsent：协议页之后必须展示必要权限页，再进入完成页。
                            GuideRequiredPermissionPage(
                                permissionRequester = permissionRequester,
                                permissionState = permissionState,
                                onBack = { reauthAnimateTo(1) },
                                onNext = { reauthAnimateTo(3) },
                                reauth = reauth,
                                // needConsent 紧凑流程为「欢迎 → 协议 → 必要权限 → 完成」共 4 页，
                                // 必要权限页为第 3 步，独立于 reauth 标签
                                stepLabel = "3 / 4",
                            )
                        } else {
                            GuideCompletePage(
                                requiredGranted = permissionState.requiredGranted,
                                onBackToPermissions = { reauthAnimateTo(1) },
                                onEnter = onContinue,
                            )
                        }
                    3 ->
                        GuideCompletePage(
                            requiredGranted = permissionState.requiredGranted,
                            onBackToPermissions = { reauthAnimateTo(2) },
                            onEnter = onContinue,
                        )
                }
            }
        }
    } else {
        val pagerState = rememberPagerState(initialPage = 0, pageCount = { GuideStep.entries.size })
        val scope = rememberCoroutineScope()

        fun animateTo(step: GuideStep) {
            scope.launch {
                pagerState.animateScrollToPage(step.ordinal)
            }
        }

        fun backStepFor(current: GuideStep): GuideStep? =
            when (current) {
                GuideStep.WELCOME -> null
                GuideStep.AGREEMENT -> GuideStep.WELCOME
                GuideStep.REQUIRED_PERMISSIONS -> GuideStep.AGREEMENT
                GuideStep.OPTIONAL_PERMISSIONS -> GuideStep.REQUIRED_PERMISSIONS
                GuideStep.SETTINGS -> GuideStep.OPTIONAL_PERMISSIONS
                GuideStep.APPEARANCE,
                GuideStep.REMOTE_FILTER,
                GuideStep.LOCAL_FILTER,
                GuideStep.SUPER_ISLAND,
                GuideStep.SCRCPY,
                -> GuideStep.SETTINGS
                GuideStep.COMPLETE -> GuideStep.SETTINGS
            }

        BackHandler(enabled = pagerState.currentPage > 0) {
            val previous = backStepFor(GuideStep.entries[pagerState.currentPage])
            if (previous != null) {
                animateTo(previous)
            }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.background)
                    .navigationBarsPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 引导页不开放手势滑动，避免跳过必读步骤或权限页
                userScrollEnabled = false,
            ) { page ->
                when (GuideStep.entries[page]) {
                    GuideStep.WELCOME ->
                        GuideWelcomePage(
                            onStart = { animateTo(GuideStep.AGREEMENT) },
                        )

                    GuideStep.AGREEMENT ->
                        GuideAgreementPage(
                            onBack = { animateTo(GuideStep.WELCOME) },
                            onNext = { animateTo(GuideStep.REQUIRED_PERMISSIONS) },
                        )

                    GuideStep.REQUIRED_PERMISSIONS ->
                        GuideRequiredPermissionPage(
                            permissionRequester = permissionRequester,
                            permissionState = permissionState,
                            onBack = { animateTo(GuideStep.AGREEMENT) },
                            onNext = { animateTo(GuideStep.OPTIONAL_PERMISSIONS) },
                        )

                    GuideStep.OPTIONAL_PERMISSIONS ->
                        GuideOptionalPermissionPage(
                            permissionRequester = permissionRequester,
                            permissionState = permissionState,
                            onBack = { animateTo(GuideStep.REQUIRED_PERMISSIONS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.SETTINGS ->
                        GuideSettingsOverviewPage(
                            onOpenAppearance = { animateTo(GuideStep.APPEARANCE) },
                            onOpenRemoteFilter = { animateTo(GuideStep.REMOTE_FILTER) },
                            onOpenLocalFilter = { animateTo(GuideStep.LOCAL_FILTER) },
                            onOpenSuperIsland = { animateTo(GuideStep.SUPER_ISLAND) },
                            onOpenScrcpy = { animateTo(GuideStep.SCRCPY) },
                            onBack = { animateTo(GuideStep.OPTIONAL_PERMISSIONS) },
                            onNext = { animateTo(GuideStep.COMPLETE) },
                        )

                    GuideStep.APPEARANCE ->
                        GuideAppearancePage(
                            selectedThemeIndex = themeBaseIndex,
                            onThemeSelected = onThemeChanged,
                            onBack = { animateTo(GuideStep.SETTINGS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.REMOTE_FILTER ->
                        GuideRemoteFilterPage(
                            onBack = { animateTo(GuideStep.SETTINGS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.LOCAL_FILTER ->
                        GuideLocalFilterPage(
                            onBack = { animateTo(GuideStep.SETTINGS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.SUPER_ISLAND ->
                        GuideSuperIslandPage(
                            onBack = { animateTo(GuideStep.SETTINGS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.SCRCPY ->
                        GuideScrcpyPage(
                            onBack = { animateTo(GuideStep.SETTINGS) },
                            onNext = { animateTo(GuideStep.SETTINGS) },
                        )

                    GuideStep.COMPLETE ->
                        GuideCompletePage(
                            requiredGranted = permissionState.requiredGranted,
                            onBackToPermissions = { animateTo(GuideStep.REQUIRED_PERMISSIONS) },
                            onEnter = onContinue,
                        )
                }
            }
        }
    }
}
