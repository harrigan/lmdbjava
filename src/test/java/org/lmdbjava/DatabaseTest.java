package org.lmdbjava;

import java.io.File;
import java.nio.ByteBuffer;
import static java.util.Collections.nCopies;
import java.util.Random;

import static junit.framework.TestCase.fail;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.lmdbjava.DatabaseFlags.MDB_CREATE;
import static org.lmdbjava.EnvFlags.MDB_NOSUBDIR;
import static org.lmdbjava.TestUtils.DB_1;
import static org.lmdbjava.TestUtils.POSIX_MODE;
import static org.lmdbjava.TestUtils.createBb;
import static org.lmdbjava.TransactionFlags.MDB_RDONLY;

public class DatabaseTest {

  @Rule
  public final TemporaryFolder tmp = new TemporaryFolder();
  private Database db;
  private Env env;
  private Transaction tx;

  @Before
  public void before() throws Exception {
    env = new Env();
    final File path = tmp.newFile();

    env.setMapSize(1_024 * 1_024 * 1_024);
    env.setMaxDbs(1);
    env.setMaxReaders(1);
    env.open(path, POSIX_MODE, MDB_NOSUBDIR);

    tx = new Transaction(env, null);
    db = tx.databaseOpen(DB_1, MDB_CREATE);
  }

  @Test(expected = DatabasesFullException.class)
  public void dbOpenMaxDatabases() throws Exception {
    tx.databaseOpen("another", MDB_CREATE);
  }

  @Test
  public void putAbortGet() throws Exception {
    Database db = tx.databaseOpen(DB_1, MDB_CREATE);

    db.put(tx, createBb(5), createBb(5));
    tx.abort();

    tx = new Transaction(env, null);
    try {
      db.get(tx, createBb(5));
      fail("key does not exist");
    } catch (ConstantDerviedException e) {
      assertThat(e.getResultCode(), is(22));
    }
    tx.abort();
  }

  @Test
  public void putAndGetAndDeleteWithInternalTx() throws Exception {
    Database db = tx.databaseOpen(DB_1, MDB_CREATE);
    tx.commit();
    db.put(createBb(5), createBb(5));
    ByteBuffer val = db.get(createBb(5));
    assertThat(val.getInt(), is(5));
    db.delete(createBb(5));
    try {
      db.get(createBb(5));
      fail("should have been deleted");
    } catch (NotFoundException e) {
    }
  }

  @Test
  public void putCommitGet() throws Exception {
    Database db = tx.databaseOpen(DB_1, MDB_CREATE);

    db.put(tx, createBb(5), createBb(5));
    tx.commit();

    tx = new Transaction(env, null);

    ByteBuffer result = db.get(tx, createBb(5));
    assertThat(result.getInt(0), is(5));
    tx.abort();
  }

  @Test
  public void putDelete() throws Exception {
    Database db = tx.databaseOpen(DB_1, MDB_CREATE);

    db.put(tx, createBb(5), createBb(5));
    db.delete(tx, createBb(5));

    try {
      db.get(tx, createBb(5));
      fail("key does not exist");
    } catch (NotFoundException e) {
    }
    tx.abort();
  }

  @Test
  public void testParallelWritesStress() throws Exception {
    tx.commit();
    // Travis CI has 1.5 cores for legacy builds
    nCopies(2, null).parallelStream()
        .forEach(ignored -> {
          Random random = new Random();
          for (int i = 0; i < 15_000; i++) {
            try {
              db.put(createBb(random.nextInt()), createBb(random.nextInt()));
            } catch (AlreadyCommittedException | LmdbNativeException |
                     NotOpenException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }
}
