/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.RandomIndexWriter;
import org.apache.lucene.tests.store.BaseDirectoryTestCase;
import org.apache.lucene.tests.util.LineFileDocs;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.junit.Assert;

public class TestNRTCachingDirectory extends BaseDirectoryTestCase {

  // TODO: RAMDir used here, because it's still too slow to use e.g. SimpleFS
  // for the threads tests... maybe because of the synchronization in listAll?
  // would be good to investigate further...
  @Override
  protected Directory getDirectory(Path path) throws IOException {
    return new NRTCachingDirectory(
        new ByteBuffersDirectory(),
        .1 + 2.0 * random().nextDouble(),
        .1 + 5.0 * random().nextDouble());
  }

  public void testNRTAndCommit() throws Exception {
    Directory dir = newDirectory();
    NRTCachingDirectory cachedDir = new NRTCachingDirectory(dir, 2.0, 25.0);
    MockAnalyzer analyzer = new MockAnalyzer(random());
    analyzer.setMaxTokenLength(TestUtil.nextInt(random(), 1, IndexWriter.MAX_TERM_LENGTH));
    IndexWriterConfig conf = newIndexWriterConfig(analyzer);
    RandomIndexWriter w = new RandomIndexWriter(random(), cachedDir, conf);
    final LineFileDocs docs = new LineFileDocs(random());
    final int numDocs = TestUtil.nextInt(random(), 100, 400);

    if (VERBOSE) {
      System.out.println("TEST: numDocs=" + numDocs);
    }

    final List<BytesRef> ids = new ArrayList<>();
    DirectoryReader r = null;
    for (int docCount = 0; docCount < numDocs; docCount++) {
      final Document doc = docs.nextDoc();
      ids.add(new BytesRef(doc.get("docid")));
      w.addDocument(doc);
      if (random().nextInt(20) == 17) {
        if (r == null) {
          r = DirectoryReader.open(w.w);
        } else {
          final DirectoryReader r2 = DirectoryReader.openIfChanged(r);
          if (r2 != null) {
            r.close();
            r = r2;
          }
        }
        assertEquals(1 + docCount, r.numDocs());
        final IndexSearcher s = newSearcher(r);
        // Just make sure search can run; we can't assert
        // totHits since it could be 0
        s.search(new TermQuery(new Term("body", "the")), 10);
        // System.out.println("tot hits " + hits.totalHits);
      }
    }

    if (r != null) {
      r.close();
    }

    // Close should force cache to clear since all files are sync'd
    w.close();

    final String[] cachedFiles = cachedDir.listCachedFiles();
    for (String file : cachedFiles) {
      System.out.println("FAIL: cached file " + file + " remains after sync");
    }
    assertEquals(0, cachedFiles.length);

    r = DirectoryReader.open(dir);
    for (BytesRef id : ids) {
      assertEquals(1, r.docFreq(new Term("docid", id)));
    }
    r.close();
    cachedDir.close();
    docs.close();
  }

  // NOTE: not a test; just here to make sure the code frag
  // in the javadocs is correct!
  public void verifyCompiles() throws Exception {
    Analyzer analyzer = null;

    Directory fsDir = FSDirectory.open(createTempDir("verify"));
    NRTCachingDirectory cachedFSDir = new NRTCachingDirectory(fsDir, 2.0, 25.0);
    IndexWriterConfig conf = new IndexWriterConfig(analyzer);
    IndexWriter writer = new IndexWriter(cachedFSDir, conf);
    writer.close();
    cachedFSDir.close();
  }

  public void testCreateTempOutputSameName() throws Exception {

    Directory fsDir = FSDirectory.open(createTempDir("verify"));
    NRTCachingDirectory nrtDir = new NRTCachingDirectory(fsDir, 2.0, 25.0);
    String name = "foo_bar_0.tmp";
    nrtDir.createOutput(name, IOContext.DEFAULT).close();

    IndexOutput out = nrtDir.createTempOutput("foo", "bar", IOContext.DEFAULT);
    assertFalse(name.equals(out.getName()));
    out.close();
    nrtDir.close();
    fsDir.close();
  }

  public void testUnknownFileSize() throws IOException {
    Directory dir = newDirectory();

    Directory nrtDir1 =
        new NRTCachingDirectory(dir, 1, 1) {
          @Override
          protected boolean doCacheWrite(String name, IOContext context) {
            boolean cache = super.doCacheWrite(name, context);
            assertTrue(cache);
            return cache;
          }
        };
    IOContext ioContext = IOContext.flush(new FlushInfo(3, 42));
    nrtDir1.createOutput("foo", ioContext).close();
    nrtDir1.createTempOutput("bar", "baz", ioContext).close();

    Directory nrtDir2 =
        new NRTCachingDirectory(dir, 1, 1) {
          @Override
          protected boolean doCacheWrite(String name, IOContext context) {
            boolean cache = super.doCacheWrite(name, context);
            assertFalse(cache);
            return cache;
          }
        };
    ioContext = IOContext.DEFAULT;
    nrtDir2.createOutput("foo", ioContext).close();
    nrtDir2.createTempOutput("bar", "baz", ioContext).close();

    dir.close();
  }

  public void testCacheSizeAfterDelete() throws IOException {
    IOContext ioContext = IOContext.flush(new FlushInfo(3, 40));
    String fn = "f1";
    try (Directory dir = newDirectory();
        NRTCachingDirectory nrt = new NRTCachingDirectory(dir, 1, 1); ) {
      // deletes a closed file
      try (IndexOutput out = nrt.createOutput(fn, ioContext)) {
        for (int i = 0; i < 10; i++) out.writeInt(i);
      }
      Assert.assertEquals(40, nrt.ramBytesUsed());
      nrt.deleteFile(fn);
      Assert.assertEquals(0, nrt.ramBytesUsed());

      // Deletes an unclosed file (write before and after deletion
      try (IndexOutput out = nrt.createOutput(fn, ioContext)) {
        for (int i = 0; i < 10; i++) out.writeInt(i);
        nrt.deleteFile(fn);
        for (int i = 0; i < 10; i++) out.writeInt(i);
      }
      Assert.assertEquals(0, nrt.ramBytesUsed());
    }
  }
}
