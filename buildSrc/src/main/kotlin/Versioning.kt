import org.eclipse.jgit.api.Git
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

    // 计算 version 信息
    fun compute(rootProjectDir: File, majorOverride: Int = 0): VersionInfo {
        val gitDir = findGitDir(rootProjectDir) ?: return VersionInfo("$majorOverride.0.0", 0)
        val repoRoot = gitDir.parentFile
        val git = openGit(repoRoot)

        val branch = try {
            git?.repository?.branch ?: ""
        } catch (e: Exception) {
            ""
        }

        val mainCount = try {
            // count commits on main (if exists)
            val revList = git?.log()?.add(git.repository.resolve("refs/heads/main"))?.call()
            revList?.count() ?: 0
        } catch (e: Exception) {
            0
        }

        val headCount = try {
            val revList = git?.log()?.call()
            revList?.count() ?: 0
        } catch (e: Exception) {
            0
        }

        val patch = if (branch == "main") {
            val fmt = DateTimeFormatter.ofPattern("MMdd")
            LocalDate.now().format(fmt).toInt()
        } else {
            headCount
        }

        val versionName = "$majorOverride.$mainCount.$patch"
        val versionCode = (majorOverride * 10_000_000) + (mainCount * 1000) + patch

        // Close repository resources
        try { git?.repository?.close() } catch (_: Exception) {}

        return VersionInfo(versionName, versionCode)
    }
}
