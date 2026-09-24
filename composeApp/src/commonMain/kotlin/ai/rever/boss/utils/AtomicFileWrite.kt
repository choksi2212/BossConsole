package ai.rever.boss.utils

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

private val OWNER_ONLY_FILE_PERMISSIONS: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

/**
 * Move [temp] onto this file, replacing it if it already exists.
 *
 * **Do not use `File.renameTo` for this.** Its behaviour when the destination exists is
 * platform-dependent, and the platforms disagree in exactly the way that hides the bug during
 * development: POSIX `rename(2)` replaces the target, so macOS and Linux work, while Win32
 * `MoveFile` fails with `ERROR_ALREADY_EXISTS`, so Windows silently stops overwriting anything
 * after the first write. That cost the browser its favicons on Windows for as long as the cache
 * had an entry — see [ai.rever.boss.cache.FaviconCache].
 *
 * `Files.move` with `REPLACE_EXISTING` is the portable form: on Windows it maps to `MoveFileEx`
 * with `MOVEFILE_REPLACE_EXISTING`. `ATOMIC_MOVE` is requested first because it additionally rules
 * out a torn destination, and is retried without when the move would cross a volume — the only
 * case that raises `AtomicMoveNotSupportedException`. Both callers create their temp file as a
 * sibling of the target, so that fallback should never fire; it is there for a caller that does
 * not. Any other refusal is a plain `IOException` and propagates.
 *
 * @throws IOException if the file could not be replaced.
 */
fun File.atomicMoveFrom(temp: File) {
    try {
        Files.move(
            temp.toPath(),
            toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Write [text] to this file atomically: content goes to a UNIQUE sibling
 * temp file first, then replaces the target via [atomicMoveFrom]. A crash
 * mid-write leaves at most a stray temp file, never a truncated target;
 * concurrent writers each use their own temp file so bytes can't interleave —
 * last move wins.
 *
 * On POSIX filesystems, permissions are pinned to owner read/write (0600)
 * before moving into place so state files do not inherit a permissive umask.
 *
 * Shared by everything that persists small state files, including the workspace
 * layout written on shutdown; `grep atomicWriteText` for the current set rather
 * than trusting a list here, which has gone stale once already. Callers
 * previously open-coded this dance with a FIXED temp name, which concurrent
 * writers could clobber.
 */
fun File.atomicWriteText(text: String) {
    parentFile?.mkdirs()
    val tmp = File.createTempFile("$name.", ".tmp", parentFile)
    try {
        if (Files.getFileAttributeView(tmp.toPath(), PosixFileAttributeView::class.java) != null) {
            // Fail closed if a filesystem advertises POSIX permissions but refuses the
            // restriction. Publishing the temp file anyway would defeat this helper's security
            // contract for every state file that relies on it.
            Files.setPosixFilePermissions(tmp.toPath(), OWNER_ONLY_FILE_PERMISSIONS)
        }
        tmp.writeText(text)
        atomicMoveFrom(tmp)
    } finally {
        // No-op when the move took it away; cleans up on failure paths.
        tmp.delete()
    }
}

/**
 * Default mode for a brand-new file under [atomicWriteTextPreserving]: owner
 * read/write + group/others read (0644). Matches the umask a typical shell
 * would produce for a `vim newfile.txt`, and keeps the new file visible to
 * the user's other tools without elevating it to a state-file default.
 */
private val NEW_FILE_DEFAULT_PERMISSIONS: Set<PosixFilePermission> =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ,
    )

/**
 * Write [text] to this file atomically while preserving the user's POSIX mode
 * bits. The state-file helper ([atomicWriteText]) pins every target to 0600,
 * which is right for caches and workspace state but strips the executable
 * bit from a script and the group read bit from a shared config the moment
 * a save lands. Editor saves need the original permissions back.
 *
 * Behaviour:
 * - **Existing file**: copies its POSIX permission set onto the temp file
 *   before the move, so the executable bit (and anything else) survives the
 *   round trip. Best-effort: a non-POSIX filesystem or a permission read
 *   that fails leaves whatever the temp file came with, which is fine - the
 *   alternative is to fail a save because the FS lost the bit, not because
 *   the save did.
 * - **New file**: applies [NEW_FILE_DEFAULT_PERMISSIONS] (0644) so the new
 *   file is readable by the user's other tools and not owner-only by accident.
 * - **Symlink target**: resolves the link to its real path with `toRealPath()`
 *   and stages the temp file beside THAT path, so the move lands on the real
 *   inode and the link itself is preserved. The previous direct write
 *   followed the link transparently; `Files.move` with `REPLACE_EXISTING`
 *   would otherwise replace the link with a regular file and lose the
 *   original inode. Falling back to the original path when `toRealPath()`
 *   cannot resolve keeps a broken link unblocked.
 * - **Hard-linked target** (POSIX `nlink` > 1): refuses for the same reason -
 *   any write would split the file across inodes, and the user did not ask
 *   for that. In-place write has the same outcome, so refusal is the only
 *   honest answer.
 *
 * @throws IOException if the target has hard links > 1, or the atomic move
 *   failed.
 */
fun File.atomicWriteTextPreserving(text: String) {
    parentFile?.mkdirs()
    val target = this
    val targetPath = target.toPath()
    if (Files.exists(targetPath)) {
        runCatching {
            val nlink = Files.getAttribute(targetPath, "unix:nlink") as? Long
            if (nlink != null && nlink > 1) {
                throw IOException("Refusing to overwrite hard-linked file (nlink=$nlink): $target")
            }
        }
    }
    // Resolve through any symlink so the staged temp is a sibling of the real
    // inode and the move lands on it. Files.move with REPLACE_EXISTING replaces
    // the link itself when given the link path, so a save through a symlink
    // would otherwise lose the original inode. Falling back to the original
    // path on a failed realPath keeps a broken-link target unblocked.
    val resolvedPath: Path =
        if (Files.exists(targetPath)) {
            runCatching { targetPath.toRealPath() }.getOrNull() ?: targetPath
        } else {
            targetPath
        }
    val resolvedTarget = resolvedPath.toFile()
    resolvedTarget.parentFile?.mkdirs()
    val tmp = File.createTempFile("$name.", ".tmp", resolvedTarget.parentFile)
    try {
        applyPermissionsForReplace(tmp, resolvedPath)
        tmp.writeText(text)
        resolvedTarget.atomicMoveFrom(tmp)
    } finally {
        // No-op when the move took it away; cleans up on failure paths.
        tmp.delete()
    }
}

/**
 * Best-effort permission copy from [existingTarget] (if present) onto [newFile];
 * otherwise applies [NEW_FILE_DEFAULT_PERMISSIONS] so a new file does not stay at
 * the JVM's restrictive temp default. Silent on non-POSIX filesystems and on
 * any individual read or write that fails - the caller would rather see an
 * I/O failure from the subsequent write than from a permission probe.
 */
private fun applyPermissionsForReplace(
    newFile: File,
    existingTarget: Path,
) {
    val view =
        runCatching {
            Files.getFileAttributeView(newFile.toPath(), PosixFileAttributeView::class.java)
        }.getOrNull() ?: return

    val perms =
        if (Files.exists(existingTarget)) {
            runCatching {
                val targetView =
                    Files.getFileAttributeView(existingTarget, PosixFileAttributeView::class.java)
                targetView?.readAttributes()?.permissions()
            }.getOrNull()
        } else {
            NEW_FILE_DEFAULT_PERMISSIONS
        } ?: return

    runCatching {
        Files.setPosixFilePermissions(newFile.toPath(), perms)
    }
}
