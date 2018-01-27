package jwp.fuzz;

import java.io.Closeable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class ByteArrayParamGenerator implements ParamGenerator<byte[]> {

  public final Config config;

  public ByteArrayParamGenerator(Config config) {
    this.config = config;
  }

  @Override
  public Iterator<byte[]> iterator() {
    return null;
  }

  @Override
  public void close() {

  }

  public static class Config {
    public final List<byte[]> initialValues;
    public final List<byte[]> dictionary;
    public final BranchHit.Hasher hasher;
    public final Function<Config, HashCache> hashCacheCreator;
    public final Function<Config, InputQueue> inputQueueCreator;
    public final ByteArrayStage[] stages;
    public final int arithMax;
    public final int havocCycles;
    public final int havocStackPower;
    public final int havocBlockSmall;
    public final int havocBlockMedium;
    public final int havocBlockLarge;
    public final int havocBlockXLarge;
    public final int maxInput;

    public Config(List<byte[]> initialValues, List<byte[]> dictionary, BranchHit.Hasher hasher,
        Function<Config, HashCache> hashCacheCreator, Function<Config, InputQueue> inputQueueCreator,
        ByteArrayStage[] stages, int arithMax, int havocCycles, int havocStackPower, int havocBlockSmall,
        int havocBlockMedium, int havocBlockLarge, int havocBlockXLarge, int maxInput) {
      this.initialValues = Objects.requireNonNull(initialValues);
      this.dictionary = Objects.requireNonNull(dictionary);
      this.hasher = Objects.requireNonNull(hasher);
      this.hashCacheCreator = Objects.requireNonNull(hashCacheCreator);
      this.inputQueueCreator = Objects.requireNonNull(inputQueueCreator);
      this.stages = Objects.requireNonNull(stages);
      this.arithMax = arithMax;
      this.havocCycles = havocCycles;
      this.havocStackPower = havocStackPower;
      this.havocBlockSmall = havocBlockSmall;
      this.havocBlockMedium = havocBlockMedium;
      this.havocBlockLarge = havocBlockLarge;
      this.havocBlockXLarge = havocBlockXLarge;
      this.maxInput = maxInput;
    }

    public Builder builder() { return new Builder(); }

    public static class Builder {
      // Consts that aren't usually changed
      public int arithMax = 35;
      public int havocCycles = 1024;
      public int havocStackPower = 7;
      public int havocBlockSmall = 32;
      public int havocBlockMedium = 128;
      public int havocBlockLarge = 1500;
      public int havocBlockXLarge = 32768;
      public int maxInput = 1024 * 1024;

      public List<byte[]> initialValues;
      public Builder initialValues(List<byte[]> initialValues) {
        this.initialValues = initialValues;
        return this;
      }
      public List<byte[]> initialValuesDefault() { return Collections.singletonList("test".getBytes()); }

      public List<byte[]> dictionary;
      public Builder dictionary(List<byte[]> dictionary) {
        this.dictionary = dictionary;
        return this;
      }
      public List<byte[]> dictionaryDefault() { return Collections.emptyList(); }

      public BranchHit.Hasher hasher;
      public Builder hasher(BranchHit.Hasher hasher) {
        this.hasher = hasher;
        return this;
      }
      public BranchHit.Hasher hasherDefault() { return BranchHit.Hasher.WITH_HIT_COUNTS; }

      public Function<Config, HashCache> hashCacheCreator;
      public Builder hashCacheCreator(Function<Config, HashCache> hashCacheCreator) {
        this.hashCacheCreator = hashCacheCreator;
        return this;
      }
      public Function<Config, HashCache> hashCacheCreatorDefault() {
        return c -> new HashCache.SetBacked();
      }

      public Function<Config, InputQueue> inputQueueCreator;
      public Builder inputQueueCreator(Function<Config, InputQueue> inputQueueCreator) {
        this.inputQueueCreator = inputQueueCreator;
        return this;
      }
      public Function<Config, InputQueue> inputQueueCreatorDefault() {
        return c -> new InputQueue.ListBacked(new ArrayList<>(), c.hasher);
      }

      public ByteArrayStage[] stages;
      public Builder stages(ByteArrayStage[] stages) {
        this.stages = stages;
        return this;
      }
      public ByteArrayStage[] stagesDefault() {
        return new ByteArrayStage[] {
            new ByteArrayStage.FlipBits(1),
            new ByteArrayStage.FlipBits(2),
            new ByteArrayStage.FlipBits(4),
            new ByteArrayStage.FlipBytes(1),
            new ByteArrayStage.FlipBytes(2),
            new ByteArrayStage.FlipBytes(4),
            new ByteArrayStage.Arith8(),
            new ByteArrayStage.Arith16(),
            new ByteArrayStage.Arith32(),
            new ByteArrayStage.Interesting8(),
            new ByteArrayStage.Interesting16(),
            new ByteArrayStage.Interesting32()
        };
      }

      public Config build() {
        return new Config(
            initialValues == null ? initialValuesDefault() : initialValues,
            dictionary == null ? dictionaryDefault() : dictionary,
            hasher == null ? hasherDefault() : hasher,
            hashCacheCreator == null ? hashCacheCreatorDefault() : hashCacheCreator,
            inputQueueCreator == null ? inputQueueCreatorDefault() : inputQueueCreator,
            stages == null ? stagesDefault() : stages,
            arithMax,
            havocCycles,
            havocStackPower,
            havocBlockSmall,
            havocBlockMedium,
            havocBlockLarge,
            havocBlockXLarge,
            maxInput
        );
      }
    }
  }

  public interface HashCache extends Closeable {
    // May be called by multiple threads
    boolean checkUniqueAndStore(int hash);

    class SetBacked implements HashCache {
      public final Set<Integer> backingSet;

      public SetBacked() {
        this(Collections.newSetFromMap(new ConcurrentHashMap<>()), true);
      }

      public SetBacked(Set<Integer> backingSet, boolean alreadySynchronized) {
        this.backingSet = alreadySynchronized ? backingSet : Collections.synchronizedSet(backingSet);
      }

      @Override
      public boolean checkUniqueAndStore(int hash) { return backingSet.add(hash); }

      @Override
      public void close() { }
    }
  }

  public interface InputQueue extends Closeable {
    // Must be thread safe and never block. Should be unbounded, but throw in worst-case scenario.
    void enqueue(TestCase entry);

    // Must be thread safe and never block. Return null if empty.
    byte[] dequeue();

    class ListBacked implements InputQueue {
      public final List<TestCase> queue;
      public final BranchHit.Hasher hasher;

      private boolean enqueuedSinceLastDequeued = false;

      public ListBacked(List<TestCase> queue, BranchHit.Hasher hasher) {
        this.queue = queue;
        this.hasher = hasher;
      }

      @Override
      public void enqueue(TestCase entry) {
        synchronized (queue) {
          queue.add(entry);
          enqueuedSinceLastDequeued = true;
        }
      }

      @Override
      public byte[] dequeue() {
        synchronized (queue) {
          // Cull if there have been some enqueued since
          if (enqueuedSinceLastDequeued) {
            enqueuedSinceLastDequeued = false;
            cull();
          }
          return queue.isEmpty() ? null : queue.remove(0).bytes;
        }
      }

      @Override
      public void close() { }

      // Guaranteed to have lock on queue when called
      protected void cull() {
        // Need test cases sorted by score
        queue.sort(Comparator.comparingLong(t -> t.score));
        // Now go over each, moving to the front ones that have branches we haven't seen
        Set<Integer> seenBranchHashes = new HashSet<>();
        List<TestCase> updated = new ArrayList<>(queue.size());
        int indexOfLastMovedForward = 0;
        for (TestCase entry : queue) {
          boolean foundNewHash = false;
          for (int i = 0; i < entry.branchHits.length; i++) {
            // Any new hash means move the item
            if (seenBranchHashes.add(hasher.hash(entry.branchHits[i])) && !foundNewHash) foundNewHash = true;
          }
          if (foundNewHash) updated.add(indexOfLastMovedForward++, entry); else updated.add(entry);
        }
        queue.clear();
        queue.addAll(updated);
      }
    }
  }

  public static class TestCase {
    public final byte[] bytes;
    // Can be null if not result of execution
    public final BranchHit[] branchHits;
    // Will be -1 if not result of execution
    public final long nanoTime;
    // Will be -1 if not result of execution
    public final long score;

    public TestCase(byte[] bytes) {
      this(bytes, null, -1);
    }

    public TestCase(byte[] bytes, BranchHit[] branchHits, long nanoTime) {
      this.bytes = bytes;
      this.branchHits = branchHits;
      this.nanoTime = nanoTime;
      score = branchHits == null ? 0 : bytes.length * nanoTime;
    }
  }

  public abstract static class ByteArraySpliterator extends Spliterators.AbstractSpliterator<byte[]> {

    public static ByteArraySpliterator of(long est, Supplier<byte[]> supplier) {
      return new ByteArraySpliterator(est) {
        @Override
        public boolean tryAdvance(Consumer<? super byte[]> action) {
          byte[] bytes = supplier.get();
          if (bytes == null) return false;
          action.accept(bytes);
          return true;
        }
      };
    }

    public ByteArraySpliterator(long est) {
      this(est, Spliterator.IMMUTABLE | Spliterator.NONNULL);
    }

    public ByteArraySpliterator(long est, int additionalCharacteristics) {
      super(est, additionalCharacteristics);
    }
  }
}
