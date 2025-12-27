import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class VersionInfo(val versionName: String, val versionCode: Int)

object Versioning {
    // 从给定起始目录向上查找 .git
    private fun findGitDir(start: File): File? {
        var cur: File? = start
        while (cur != null) {
            val git = File(cur, ".git")
            if (git.exists()) return git
            cur = cur.parentFile
        }
        return null
    }

    private fun openGit(repoRoot: File?): Git? {
        if (repoRoot == null) return null
        val builder = FileRepositoryBuilder()
        val repo = try {
            builder.setGitDir(File(repoRoot, ".git")).readEnvironment().findGitDir().build()
        } catch (e: Exception) {
            null
        }
        return if (repo != null) Git(repo) else null
    }

    // 统计 includeRef 可达但 excludeRef 不可达的提交数（等同于 git rev-list --count includeRef ^excludeRef）
    private fun countExclusiveCommits(git: Git?, includeRef: String, excludeRef: String?): Int {
        if (git == null) return 0
        val repository = try { git.repository } catch (e: Exception) { return 0 }
        return try {
            val includeId: ObjectId? = repository.resolve(includeRef)
            if (includeId == null) return 0
            val revWalk = RevWalk(repository)
            val includeCommit = revWalk.parseCommit(includeId)
            revWalk.markStart(includeCommit)
            if (!excludeRef.isNullOrBlank()) {
                val excludeId = repository.resolve(excludeRef)
                if (excludeId != null) {
                    val excludeCommit = revWalk.parseCommit(excludeId)
                    revWalk.markUninteresting(excludeCommit)
                }
            }

            var cnt = 0
            for (c in revWalk) cnt++
            revWalk.close()
            cnt
        } catch (e: Exception) {
            0
        }
    }

    // 计算 version 信息
    // majorSubtract: 手动设置的减量（用于在大版本号更新后减去一定量，防止次版本号持续增长）
    fun compute(rootProjectDir: File, majorOverride: Int = 0, majorSubtract: Int = 0): VersionInfo {
        val gitDir = findGitDir(rootProjectDir) ?: return VersionInfo("$majorOverride.0.0.0", 0)
    // parentFile 可为空（理论上不会，因为 gitDir 是 .git 文件夹），直接在调用时提供回退值以避免可空性问题
    val git = openGit(gitDir.parentFile ?: rootProjectDir)

        val branch = try {
            git?.repository?.branch ?: ""
        } catch (e: Exception) {
            ""
        }

        // 统计 main 分支提交数（如果存在）
        val mainCount = countExclusiveCommits(git, "refs/heads/main", null)
        // 应用手动减量，确保不小于 0
        val mainCountAdjusted = kotlin.math.max(0, mainCount - majorSubtract)

        // 获取当前时间
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("MMddHHmm"))

        // 生成 versionName 和 versionCode
        // 简化版本号格式：所有分支统一使用 major.mainCount.MMddHHmm，main分支添加发布后缀
        val versionName = if (branch == "main") {
            // main分支：major.mainCount.MMddHHmm-release
            "$majorOverride.$mainCountAdjusted.$dateTime-release"
        } else {
            // 非main分支：major.mainCount.MMddHHmm
            "$majorOverride.$mainCountAdjusted.$dateTime"
        }
        
        // versionCode 计算：确保远小于 Int.MAX_VALUE(2147483647)，避免溢出风险
        // 调整系数：进一步降低各部分权重，确保长期递增
        // 格式：major * 1_000_000 + mainCount * 1_000 + MMddHHmm
        // 1_000_000 系数确保 major 为高位，1_000 系数确保 mainCount 为中位，MMddHHmm 为低位
        // 最大值估算：200*1,000,000 + 100,000*1,000 + 12312359 = 312,312,359 < 2,147,483,647
        val versionCode = (majorOverride * 1_000_000L + mainCountAdjusted * 1_000L + dateTime.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())
        
        Pair(versionName, versionCode)

        // Close repository resources
        try { git?.repository?.close() } catch (_: Exception) {}

        return VersionInfo(versionName, versionCode.toInt())
    }
}
