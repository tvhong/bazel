// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote.disk;

import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.testutil.TestUtils;
import com.google.devtools.build.lib.vfs.Path;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link DiskCacheGarbageCollector}. */
@RunWith(JUnit4.class)
public final class DiskCacheGarbageCollectorTest {

  private final ExecutorService executorService =
      MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(1));

  private Path rootDir;

  record Entry(String path, long size, Instant mtime) {
    static Entry of(String path, long size, Instant mtime) {
      return new Entry(path, size, mtime);
    }
  }

  @Before
  public void setUp() throws Exception {
    rootDir = TestUtils.createUniqueTmpDir(null);
  }

  @Test
  public void sizePolicy_noCollection() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), Instant.now()),
        Entry.of("cas/456", kbytes(1), Instant.now()));

    runGarbageCollector(Optional.of(kbytes(2)), Optional.empty());

    assertFilesExist("ac/123", "cas/456");
  }

  @Test
  public void sizePolicy_collectsOldest() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), daysAgo(1)),
        Entry.of("cas/456", kbytes(1), daysAgo(2)),
        Entry.of("ac/abc", kbytes(1), daysAgo(3)),
        Entry.of("cas/def", kbytes(1), daysAgo(4)));

    runGarbageCollector(Optional.of(kbytes(2)), Optional.empty());

    assertFilesExist("ac/123", "cas/456");
    assertFilesDoNotExist("ac/abc", "cas/def");
  }

  @Test
  public void agePolicy_noCollection() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), Instant.now()),
        Entry.of("cas/456", kbytes(1), Instant.now()));

    runGarbageCollector(Optional.empty(), Optional.of(days(3)));

    assertFilesExist("ac/123", "cas/456");
  }

  @Test
  public void agePolicy_collectsOldest() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), daysAgo(1)),
        Entry.of("cas/456", kbytes(1), daysAgo(2)),
        Entry.of("ac/abc", kbytes(1), daysAgo(3)),
        Entry.of("cas/def", kbytes(1), daysAgo(4)));

    runGarbageCollector(Optional.empty(), Optional.of(Duration.ofDays(3)));

    assertFilesExist("ac/123", "cas/456");
    assertFilesDoNotExist("ac/abc", "cas/def");
  }

  @Test
  public void sizeAndAgePolicy_noCollection() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), Instant.now()),
        Entry.of("cas/456", kbytes(1), Instant.now()));

    runGarbageCollector(Optional.of(kbytes(2)), Optional.of(days(1)));

    assertFilesExist("ac/123", "cas/456");
  }

  @Test
  public void sizeAndAgePolicy_sizeMoreRestrictiveThanAge_collectsOldest() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), daysAgo(1)),
        Entry.of("cas/456", kbytes(1), daysAgo(2)),
        Entry.of("ac/abc", kbytes(1), daysAgo(3)),
        Entry.of("cas/def", kbytes(1), daysAgo(4)));

    runGarbageCollector(Optional.of(kbytes(2)), Optional.of(days(4)));

    assertFilesExist("ac/123", "cas/456");
    assertFilesDoNotExist("ac/abc", "cas/def");
  }

  @Test
  public void sizeAndAgePolicy_ageMoreRestrictiveThanSize_collectsOldest() throws Exception {
    writeFiles(
        Entry.of("ac/123", kbytes(1), daysAgo(1)),
        Entry.of("cas/456", kbytes(1), daysAgo(2)),
        Entry.of("ac/abc", kbytes(1), daysAgo(3)),
        Entry.of("cas/def", kbytes(1), daysAgo(4)));

    runGarbageCollector(Optional.of(kbytes(3)), Optional.of(days(3)));

    assertFilesExist("ac/123", "cas/456");
    assertFilesDoNotExist("ac/abc", "cas/def");
  }

  @Test
  public void ignoresTmpAndGcSubdirectories() throws Exception {
    writeFiles(
        Entry.of("gc/foo", kbytes(1), daysAgo(1)), Entry.of("tmp/foo", kbytes(1), daysAgo(1)));

    runGarbageCollector(Optional.of(1L), Optional.of(days(1)));

    assertFilesExist("gc/foo", "tmp/foo");
  }

  private void assertFilesExist(String... relativePaths) throws IOException {
    for (String relativePath : relativePaths) {
      Path path = rootDir.getRelative(relativePath);
      assertWithMessage("expected %s to exist".formatted(relativePath))
          .that(path.exists())
          .isTrue();
    }
  }

  private void assertFilesDoNotExist(String... relativePaths) throws IOException {
    for (String relativePath : relativePaths) {
      Path path = rootDir.getRelative(relativePath);
      assertWithMessage("expected %s to not exist".formatted(relativePath))
          .that(path.exists())
          .isFalse();
    }
  }

  private void runGarbageCollector(Optional<Long> maxSizeBytes, Optional<Duration> maxAge)
      throws Exception {
    var gc =
        new DiskCacheGarbageCollector(
            rootDir,
            executorService,
            new DiskCacheGarbageCollector.CollectionPolicy(maxSizeBytes, maxAge));
    gc.run();
  }

  private void writeFiles(Entry... entries) throws IOException {
    for (Entry entry : entries) {
      writeFile(entry.path(), entry.size(), entry.mtime());
    }
  }

  private void writeFile(String relativePath, long size, Instant mtime) throws IOException {
    Path path = rootDir.getRelative(relativePath);
    path.getParentDirectory().createDirectoryAndParents();
    try (OutputStream out = path.getOutputStream()) {
      out.write(new byte[(int) size]);
    }
    path.setLastModifiedTime(mtime.toEpochMilli());
  }

  private static Instant daysAgo(int days) {
    return Instant.now().minus(Duration.ofDays(days));
  }

  private static Duration days(int days) {
    return Duration.ofDays(days);
  }

  private static long kbytes(int kbytes) {
    return kbytes * 1024L;
  }
}
