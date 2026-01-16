package com.amazon.corretto.gradle.plugin.customtar

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.archivers.zip.UnixStat
import org.gradle.api.GradleException
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.internal.file.CopyActionProcessingStreamAction
import org.gradle.api.internal.file.archive.compression.ArchiveOutputStreamFactory;
import org.gradle.api.internal.file.archive.compression.Bzip2Archiver;
import org.gradle.api.internal.file.archive.compression.GzipArchiver;
import org.gradle.api.internal.file.archive.compression.SimpleCompressor;
import org.gradle.api.internal.file.copy.CopyAction
import org.gradle.api.internal.file.copy.CopyActionProcessingStream
import org.gradle.api.internal.file.copy.FileCopyDetailsInternal
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.WorkResult
import org.gradle.api.tasks.WorkResults
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Compression;

import java.nio.charset.Charset
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption;

class TarWithSymlinks extends AbstractArchiveTask {
    public static final long CONSTANT_TIME_FOR_TAR_ENTRIES = 86400000

    @Input
    Compression compression = Compression.NONE
    @Input
    boolean followSymlinks = true
    @Input
    boolean preserveFileTimestamps = false
    @Optional
    @Input
    String fileChangeMode = null
    @Optional
    @Input
    String directoryChangeMode = null

    enum CredentialMode { OMIT, FROM_FILE, CUSTOM }

    @Internal
    CredentialMode userMode = CredentialMode.OMIT
    @Internal
    String userName = null
    @Internal
    Long userId = 0

    @Internal
    CredentialMode groupMode = CredentialMode.OMIT
    @Internal
    String groupName = null
    @Internal
    Long groupId = 0

    public TarWithSymlinks() {
        getArchiveExtension().set(getProject().provider(() -> getCompression().getDefaultExtension()))
    }

    @Override
    protected CopyAction createCopyAction() {
        return new TarCopyAction(getArchivePath())
    }

    private ArchiveOutputStreamFactory getCompressor() {
        switch (compression) {
            case Compression.BZIP2: return Bzip2Archiver.getCompressor()
            case Compression.GZIP:  return GzipArchiver.getCompressor()
            default:    return new SimpleCompressor()
        }
    }

    public void user(CredentialMode mode, Object... nameAndId) {
        userMode = mode
        switch (nameAndId.length) {
            case 0:
                 userName = null
                 userId = null
                 break

            case 1:
                 if (nameAndId[0] instanceof String) {
                     userName = nameAndId[0]
                 } else {
                     userId = ((Integer) nameAndId[0]).toLong()
                 }
                 break

            case 2:
                 userName = nameAndId[0]
                 userId = ((Integer) nameAndId[1]).toLong()
                 break

            default:
                throw new GradleException("user directive should be: user <CredentialMode> ['username'] [id]")
        }
    }

    public void group(CredentialMode mode, Object... nameAndId) {
        groupMode = mode
        switch (nameAndId.length) {
            case 0:
                 groupName = null
                 groupId = null
                 break

            case 1:
                 if (nameAndId[0] instanceof String) {
                     groupName = nameAndId[0]
                 } else {
                     groupId = ((Integer) nameAndId[0]).toLong()
                 }
                 break

            case 2:
                 groupName = nameAndId[0]
                 groupId = ((Integer) nameAndId[1]).toLong()
                 break

            default:
                throw new GradleException("group directive should be: group <CredentialMode> ['groupname'] [id]")
        }
    }

    public class TarCopyAction implements CopyAction {
        private final Logger log = Logging.getLogger(getClass())

        private final File tarFile

        public TarCopyAction(File tarFile) {
            this.tarFile = tarFile
        }

        public WorkResult execute(final CopyActionProcessingStream stream) {
            OutputStream outStr;
            try {
                log.debug("Creating TAR archive with " + compression + " compression ("
                    + "followSymlinks=" + followSymlinks 
                    + "; preserveFileTimestamps=" + preserveFileTimestamps
                    + "; fileChangeMode=" + fileChangeMode ?: ''
                    + "; directoryChangeMode=" + directoryChangeMode ?: ''
                    + "; user=" + user + ':' + userId
                    + "; group=" + group + ':' + groupId
                    + "): " + tarFile)

                outStr = getCompressor().createArchiveOutputStream(tarFile)
            } catch (Exception e) {
                throw new GradleException(String.format("Could not create TAR '%s'.", tarFile), e)
            }

            try {
                TarArchiveOutputStream tarOutStr = new TarArchiveOutputStream(outStr, 512, 512, "UTF8")
                tarOutStr.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tarOutStr.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                stream.process(new StreamAction(tarOutStr))
                tarOutStr.close()
                log.trace("Finished creating TAR archive: " + tarFile)
            } catch (Exception e) {
                tarFile.delete()
                log.warn("Error creating TAR archive. Deleted file: " + tarFile, e)
                throw e
            }

            return WorkResults.didWork(true)
        }

        private class StreamAction implements CopyActionProcessingStreamAction {
            private final TarArchiveOutputStream tarOutStr

            private visitedSymLinks = []

            public StreamAction(TarArchiveOutputStream tarOutStr) {
                this.tarOutStr = tarOutStr
            }

            public void processFile(FileCopyDetailsInternal details) {
                log.trace("processFile {}", details);
                if (followSymlinks || !isChildOfVisitedSymlink(details)) {
                    if (!followSymlinks && isSymLink(details)) {
                        visitSymLink(details);
                    } else if (details.isDirectory()) {
                        visitDir(details);
                    } else {
                        visitFile(details);
                    }
                }
            }

            private Boolean isSymLink(FileCopyDetails fileDetails) {
                try {
                    return Files.isSymbolicLink(fileDetails.getFile().toPath())
                } catch (UnsupportedOperationException e) {
                    // Skip. Can be from org.gradle.api.internal.file.copy.NormalizingCopyActionDecorator$StubbedFileCopyDetails.getFile() for an added prefix
                } catch (Exception e) {
                    log.warn('isSymLink exception', e)
                    return false;
                }
            }

            private Boolean isChildOfVisitedSymlink(FileCopyDetails fileDetails) {
                try {
                    File file = fileDetails.getFile()
                    // TODO: optimize
                    for (File symLink : visitedSymLinks) {
                        if (isChildOf(symLink, file)) {
                            return true;
                        }
                    }
                } catch (UnsupportedOperationException e) {
                    // Skip. Can be thrown from org.gradle.api.internal.file.copy.NormalizingCopyActionDecorator$StubbedFileCopyDetails.getFile() for an added prefix
                } catch (Exception e) {
                    log.warn('isChildOfVisitedSymlink exception: ', e)
                }
                return false;
            }

            // TODO: optimize
            private Boolean isChildOf(File dir, File file) {
                File parent = file.getParentFile();
                while (parent != null) {
                    if (dir.toString().equals(parent.toString())) {
                        return true;
                    }
                    parent = parent.getParentFile();
                }
                return false;
            }

            private void visitFile(FileCopyDetails fileDetails) {
                try {
                    int mode = fileDetails.getMode()
                    if (fileChangeMode != null && !fileChangeMode.isEmpty()) {
                        mode = applyChmodPattern(fileChangeMode, mode)
                    }

                    log.debug('Adding file, mode={}: {} ', String.format("%04o", mode), fileDetails)

                    TarArchiveEntry archiveEntry = new TarArchiveEntry(fileDetails.getRelativePath().getPathString())
                    archiveEntry.setModTime(getArchiveTimeFor(fileDetails))
                    archiveEntry.setSize(fileDetails.getSize())
                    archiveEntry.setMode(UnixStat.FILE_FLAG | mode)
                    setCredentials(archiveEntry, fileDetails);
                    tarOutStr.putArchiveEntry(archiveEntry)
                    fileDetails.copyTo(tarOutStr)
                    tarOutStr.closeArchiveEntry()
                } catch (Exception e) {
                    log.error("Exception", e)
                    throw new GradleException(String.format("Could not add file %s to TAR '%s'.", fileDetails, tarFile), e)
                }
            }

            private void visitDir(FileCopyDetails fileDetails) {
                try {
                    int mode = fileDetails.getMode()
                    if (directoryChangeMode != null && !directoryChangeMode.isEmpty()) {
                        mode = applyChmodPattern(directoryChangeMode, mode)
                    }

                    log.debug('Adding directory, mode={}: {}', String.format("%04o", mode), fileDetails)

                    // Trailing slash in name indicates that entry is a directory
                    TarArchiveEntry archiveEntry = new TarArchiveEntry(fileDetails.getRelativePath().getPathString() + "/");
                    archiveEntry.setModTime(getArchiveTimeFor(fileDetails));
                    archiveEntry.setMode(UnixStat.DIR_FLAG | mode)
                    setCredentials(archiveEntry, fileDetails);
                    tarOutStr.putArchiveEntry(archiveEntry);
                    tarOutStr.closeArchiveEntry();
                } catch (Exception e) {
                    log.error("Exception", e);
                    throw new GradleException(String.format("Could not add directory %s to TAR '%s'.", fileDetails, tarFile), e);
                }
            }

            protected void visitSymLink(FileCopyDetails fileDetails) {
                try {
                    log.debug('Adding symlink, mode={}: {}', String.format("%04o", fileDetails.getMode()), fileDetails)

                    visitedSymLinks.add(fileDetails.getFile());
                    Path link = Files.readSymbolicLink(fileDetails.getFile().toPath());

                    TarArchiveEntry archiveEntry = new TarArchiveEntry(fileDetails.getRelativePath().getPathString(), TarConstants.LF_SYMLINK);
                    archiveEntry.setModTime(getArchiveTimeFor(fileDetails));
                    archiveEntry.setMode(UnixStat.LINK_FLAG | fileDetails.getMode());
                    archiveEntry.setLinkName(link.toString());
                    setCredentials(archiveEntry, fileDetails);
                    tarOutStr.putArchiveEntry(archiveEntry);
                    tarOutStr.closeArchiveEntry();
                } catch (Exception e) {
                    log.error("Exception", e);
                    throw new GradleException(String.format("Could not add symbolic link %s -> %s to TAR '%s'.", fileDetails, link, tarFile), e);
                }
            }

            private void setCredentials(TarArchiveEntry e, FileCopyDetails fileDetails) {
                if (userMode == CredentialMode.CUSTOM) {
                    if (userId != null) {
                        e.setUserId(userId);
                    }
                    e.setUserName(user ?: '');
                }

                if (groupMode == CredentialMode.CUSTOM) {
                    if (groupId != null) {
                        e.setGroupId(groupId);
                    }
                    e.setGroupName(group ?: '');
                }

                try {
                    setCredentialsFromFile(e, fileDetails.getFile().toPath(), userMode == CredentialMode.FROM_FILE, groupMode == CredentialMode.FROM_FILE);
                } catch (UnsupportedOperationException ex) {
                    log.debug('Unable to retrieve credentials from {}', fileDetails, ex)
                }
            }

            private void setCredentialsFromFile(TarArchiveEntry e, Path filePath, boolean setUser, boolean setGroup) throws IOException {
                if (!setUser && !setGroup) {
                    return
                }

                def options = followSymlinks ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS}

                def availableAttributeViews = filePath.fileSystem.supportedFileAttributeViews()
                if (availableAttributeViews.contains("posix")) {
                    def posixFileAttributes = Files.readAttributes(filePath, PosixFileAttributes.class, options)
                    if (setUser) {
                        e.setUserName(posixFileAttributes.owner().name ?: '')
                    }
                    if (setGroup) {
                        e.setGroupName(posixFileAttributes.group().name ?: '')
                    }
                    if (availableAttributeViews.contains("unix")) {
                        if (setUser) {
                            e.setUserId(((Number) Files.getAttribute(filePath, "unix:uid", options)).longValue())
                        }
                        if (setGroup) {
                            e.setGroupId(((Number) Files.getAttribute(filePath, "unix:gid", options)).longValue())
                        }
                    }
                } else {
                    if (setUser) {
                        e.setUserName(Files.getOwner(path, options).name ?: '')
                    }
                }
            }

            private long getArchiveTimeFor(FileCopyDetails details) {
                return preserveFileTimestamps ? details.getLastModified() : CONSTANT_TIME_FOR_TAR_ENTRIES;
            }

            private int applyChmodPattern(String pattern, int currentMode) {
                // Handle octal patterns (0644, 644, etc.)
                if (pattern.matches(/^0?[0-7]{3,4}$/)) {
                    return Integer.parseInt(pattern, 8)
                }

                int mode = currentMode

                // Handle symbolic patterns (u=rX,go-w)
                pattern.split(',').each { clause ->
                    clause = clause.trim()

                    // Parse who (u=user, g=group, o=other, a=all)
                    def whoMatch = clause =~ /^([ugoa]*)/
                    String who = whoMatch[0][1] ?: 'a'

                    // Parse operation (=, +, -)
                    def opMatch = clause =~ /[=+-]/
                    String op = opMatch[0]

                    // Parse permissions (rwxX)
                    String perms = clause.substring(clause.indexOf(op) + 1)

                    // Calculate permission bits
                    int permBits = 0
                    perms.each { ch ->
                        switch(ch) {
                            case 'r': permBits |= 0b100; break
                            case 'w': permBits |= 0b010; break
                            case 'x': permBits |= 0b001; break
                            case 'X':
                                if ((mode & 0111) || (mode & 04000)) permBits |= 0b001
                                break
                        }
                    }

                    // Apply to user/group/other
                    ['u': 6, 'g': 3, 'o': 0].each { target, shift ->
                        if (who.contains(target) || who.contains('a') || who == '') {
                            int mask = 0b111 << shift
                            switch(op) {
                                case '=':
                                    mode = (mode & ~mask) | (permBits << shift)
                                    break
                                case '+':
                                    mode |= (permBits << shift)
                                    break
                                case '-':
                                    mode &= ~(permBits << shift)
                                    break
                            }
                        }
                    }
                }

                log.trace('Applied chmod pattern {}: {} -> {}', pattern, String.format("%04o", currentMode), String.format("%04o", mode));
                return mode
            }
        }
    }
}
