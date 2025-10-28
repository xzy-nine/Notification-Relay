import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.time.LocalDate
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

    private fun openGit(repoRoot: File): Git? {
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
        val gitDir = findGitDir(rootProjectDir) ?: return VersionInfo("$majorOverride.0.0", 0)
        val repoRoot = gitDir.parentFile
        val git = openGit(repoRoot)

        val branch = try {
            git?.repository?.branch ?: ""
        } catch (e: Exception) {
            ""
        }

    // 统计 main 分支提交数（如果存在）
    val mainCount = countExclusiveCommits(git, "refs/heads/main", null)
    // 应用手动减量，确保不小于 0
    val mainCountAdjusted = kotlin.math.max(0, mainCount - majorSubtract)

        // 非 main 分支的修订号应为该分支相对于 main 的独有提交数（等同于 git rev-list --count HEAD ^main）
        val exclusiveHeadCount = try {
            // 如果 main 分支不存在，回退到 HEAD 的全部可达提交数
            val mainRefExists = try { git?.repository?.resolve("refs/heads/main") != null } catch (_: Exception) { false }
            if (mainRefExists) {
                countExclusiveCommits(git, "HEAD", "refs/heads/main")
            } else {
                countExclusiveCommits(git, "HEAD", null)
            }
        } catch (e: Exception) { 0 }

        val patch = if (branch == "main") {
            val fmt = DateTimeFormatter.ofPattern("MMdd")
            LocalDate.now().format(fmt).toInt()
        } else {
            exclusiveHeadCount
        }

    val versionName = "$majorOverride.$mainCountAdjusted.$patch"
    val versionCode = (majorOverride * 10_000_000) + (mainCountAdjusted * 1000) + patch

        // Close repository resources
        try { git?.repository?.close() } catch (_: Exception) {}

        return VersionInfo(versionName, versionCode)
    }
}
