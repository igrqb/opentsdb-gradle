// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;

import org.hbase.async.AppendRequest;
import org.hbase.async.Bytes;
import org.hbase.async.PutRequest;
import org.hbase.async.Bytes.ByteMap;

import net.opentsdb.meta.Annotation;
import net.opentsdb.stats.Histogram;

/**
 * Receives new data points and stores them in HBase.
 */
final class IncomingDataPoints implements WritableDataPoints {

  /** For how long to buffer edits when doing batch imports (in ms). */
  private static final short DEFAULT_BATCH_IMPORT_BUFFER_INTERVAL = 5000;

  /**
   * Keep track of the latency (in ms) we perceive sending edits to HBase. We
   * want buckets up to 16s, with 2 ms interval between each bucket up to 100 ms
   * after we which we switch to exponential buckets.
   */
  static final Histogram putlatency = new Histogram(16000, (short) 2, 100);

  /**
   * Keep track of the number of UIDs that came back null with auto_metric disabled.
   */
  static final AtomicLong auto_metric_rejection_count = new AtomicLong();

  /** The {@code TSDB} instance we belong to. */
  private final TSDB tsdb;
  
  /** Whether or not to allow out of order data. */
  private final boolean allow_out_of_order_data;

  /**
   * The row key. Optional salt + 3 bytes for the metric name, 4 bytes for 
   * the base timestamp, 6 bytes per tag (3 for the name, 3 for the value).
   */
  private byte[] row;

  /**
   * Qualifiers for individual data points. The last Const.FLAG_BITS bits are
   * used to store flags (the type of the data point - integer or floating point
   * - and the size of the data point in bytes). The remaining MSBs store a
   * delta in seconds from the base timestamp stored in the row key.
   */
  private short[] qualifiers;

  /** Each value in the row. */
  private long[] values;

  /** Track the last timestamp written for this series */
  private long last_ts;

  /** Number of data points in this row. */
  private short size;

  /** Are we doing a batch import? */
  private boolean batch_import;
  
  /** The metric for this time series */
  private String metric;
  
  /** Copy of the tags given us by the caller */
  private Map<String, String> tags;

  /**
   * Constructor.
   * 
   * @param tsdb
   *          The TSDB we belong to.
   */
  IncomingDataPoints(final TSDB tsdb) {
    this.tsdb = tsdb;
    allow_out_of_order_data = tsdb.getConfig()
        .getBoolean("tsd.core.bulk.allow_out_of_order_timestamps");
  }

  /**
   * Validates the given metric and tags.
   * 
   * @throws IllegalArgumentException
   *           if any of the arguments aren't valid.
   */
  static void checkMetricAndTags(final String metric,
      final Map<String, String> tags) {
    if (tags.size() <= 0) {
      throw new IllegalArgumentException("Need at least one tag (metric="
          + metric + ", tags=" + tags + ')');
    } else if (tags.size() > Const.MAX_NUM_TAGS()) {
      throw new IllegalArgumentException("Too many tags: " + tags.size()
          + " maximum allowed: " + Const.MAX_NUM_TAGS() + ", tags: " + tags);
    }

    Tags.validateString("metric name", metric);
    for (final Map.Entry<String, String> tag : tags.entrySet()) {
      Tags.validateString("tag name with value [" + tag.getValue() + "]", 
          tag.getKey());
      Tags.validateString("tag value with key [" + tag.getKey() + "]", 
          tag.getValue());
    }
  }

  /**
   * Returns a partially initialized row key for this metric and these tags. The
   * only thing left to fill in is the base timestamp.
   */
  static byte[] rowKeyTemplate(final TSDB tsdb, final String metric,
      final Map<String, String> tags) {
    final short metric_width = tsdb.metrics.width();
    final short tag_name_width = tsdb.tag_names.width();
    final short tag_value_width = tsdb.tag_values.width();
    final short num_tags = (short) tags.size();

    int row_size = (Const.SALT_WIDTH() + metric_width + Const.TIMESTAMP_BYTES 
        + tag_name_width * num_tags + tag_value_width * num_tags);
    final byte[] row = new byte[row_size];

    short pos = (short) Const.SALT_WIDTH();

    byte[] metric_id  = (tsdb.config.auto_metric() ? tsdb.metrics.getOrCreateId(metric)
            : tsdb.metrics.getId(metric));

    if(!tsdb.config.auto_metric() && metric_id == null) {
      auto_metric_rejection_count.incrementAndGet();
    }

    copyInRowKey(row, pos, metric_id);
    pos += metric_width;

    pos += Const.TIMESTAMP_BYTES;

    for (final byte[] tag : Tags.resolveOrCreateAll(tsdb, tags)) {
      copyInRowKey(row, pos, tag);
      pos += tag.length;
    }
    return row;
  }

  public void setSeries(final String metric, final Map<String, String> tags) {
    checkMetricAndTags(metric, tags);
    try {
      row = rowKeyTemplate(tsdb, metric, tags);
      RowKey.prefixKeyWithSalt(row);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never happen", e);
    }
    this.metric = metric;
    this.tags = tags;
    size = 0;
  }

  /**
   * Copies the specified byte array at the specified offset in the row key.
   * 
   * @param row
   *          The row key into which to copy the bytes.
   * @param offset
   *          The offset in the row key to start writing at.
   * @param bytes
   *          The bytes to copy.
   */
  private static void copyInRowKey(final byte[] row, final short offset,
      final byte[] bytes) {
    System.arraycopy(bytes, 0, row, offset, bytes.length);
  }

  /**
   * Updates the base time in the row key.
   * 
   * @param timestamp
   *          The timestamp from which to derive the new base time.
   * @return The updated base time.
   */
  private long updateBaseTime(final long timestamp) {
    // We force the starting timestamp to be on a MAX_TIMESPAN boundary
    // so that all TSDs create rows with the same base time. Otherwise
    // we'd need to coordinate TSDs to avoid creating rows that cover
    // overlapping time periods.
    final long base_time = timestamp - (timestamp % Const.MAX_TIMESPAN);
    // Clone the row key since we're going to change it. We must clone it
    // because the HBase client may still hold a reference to it in its
    // internal datastructures.
    row = Arrays.copyOf(row, row.length);
    Bytes.setInt(row, (int) base_time, Const.SALT_WIDTH() + tsdb.metrics.width());
    RowKey.prefixKeyWithSalt(row); // in case the timestamp will be involved in
                                   // salting later
    tsdb.scheduleForCompaction(row, (int) base_time);
    return base_time;
  }

  /**
   * Implements {@link #addPoint} by storing a value with a specific flag.
   * 
   * @param timestamp
   *          The timestamp to associate with the value.
   * @param value
   *          The value to store.
   * @param flags
   *          Flags to store in the qualifier (size and type of the data point).
   * @return A deferred object that indicates the completion of the request.
   */
  private Deferred<Object> addPointInternal(final long timestamp,
      final byte[] value, final short flags) {
    if (row == null) {
      throw new IllegalStateException("setSeries() never called!");
    }
    final boolean ms_timestamp = (timestamp & Const.SECOND_MASK) != 0;

    // we only accept unix epoch timestamps in seconds or milliseconds
    if (timestamp < 0 || (ms_timestamp && timestamp > 9999999999999L)) {
      throw new IllegalArgumentException((timestamp < 0 ? "negative " : "bad")
          + " timestamp=" + timestamp + " when trying to add value="
          + Arrays.toString(value) + " to " + this);
    }

    // always maintain last_ts in milliseconds
    if ((ms_timestamp ? timestamp : timestamp * 1000) <= last_ts) {
      if (allow_out_of_order_data) {
        // as we don't want to perform any funky calculations to find out if
        // we're still in the same time range, just pass it off to the regular
        // TSDB add function.
        return tsdb.addPointInternal(metric, timestamp, value, tags, flags);
      } else {
        throw new IllegalArgumentException("New timestamp=" + timestamp
            + " is less than or equal to previous=" + last_ts
            + " when trying to add value=" + Arrays.toString(value) + " to "
            + this);
      }
    }
    
    /** Callback executed for chaining filter calls to see if the value
     * should be written or not. */
    final class WriteCB implements Callback<Deferred<Object>, Boolean> {
      @Override
      public Deferred<Object> call(final Boolean allowed) throws Exception {
        if (!allowed) {
          return Deferred.fromResult(null);
        }
        

        last_ts = (ms_timestamp ? timestamp : timestamp * 1000);

        long base_time = baseTime();
        long incoming_base_time;
        if (ms_timestamp) {
          // drop the ms timestamp to seconds to calculate the base timestamp
          incoming_base_time = ((timestamp / 1000) - ((timestamp / 1000) % Const.MAX_TIMESPAN));
        } else {
          incoming_base_time = (timestamp - (timestamp % Const.MAX_TIMESPAN));
        }

        if (incoming_base_time - base_time >= Const.MAX_TIMESPAN) {
          // Need to start a new row as we've exceeded Const.MAX_TIMESPAN.
          base_time = updateBaseTime((ms_timestamp ? timestamp / 1000 : timestamp));
        }

        // Java is so stupid with its auto-promotion of int to float.
        final byte[] qualifier = Internal.buildQualifier(timestamp, flags);

        // TODO(tsuna): The following timing is rather useless. First of all,
        // the histogram never resets, so it tends to converge to a certain
        // distribution and never changes. What we really want is a moving
        // histogram so we can see how the latency distribution varies over time.
        // The other problem is that the Histogram class isn't thread-safe and
        // here we access it from a callback that runs in an unknown thread, so
        // we might miss some increments. So let's comment this out until we
        // have a proper thread-safe moving histogram.
        // final long start_put = System.nanoTime();
        // final Callback<Object, Object> cb = new Callback<Object, Object>() {
        // public Object call(final Object arg) {
        // putlatency.add((int) ((System.nanoTime() - start_put) / 1000000));
        // return arg;
        // }
        // public String toString() {
        // return "time put request";
        // }
        // };

        // TODO(tsuna): Add an errback to handle some error cases here.
        if (tsdb.getConfig().enable_appends()) {
          final AppendDataPoints kv = new AppendDataPoints(qualifier, value);
          final AppendRequest point = new AppendRequest(tsdb.table, row, TSDB.FAMILY, 
                  AppendDataPoints.APPEND_COLUMN_QUALIFIER, kv.getBytes());
          point.setDurable(!batch_import);
          return tsdb.client.append(point);/* .addBoth(cb) */
        } else {
          final PutRequest point = RequestBuilder.buildPutRequest(tsdb.getConfig(), tsdb.table, row, TSDB.FAMILY,
              qualifier, value, timestamp);
          point.setDurable(!batch_import);
          return tsdb.client.put(point)/* .addBoth(cb) */;
        }
      }
      @Override
      public String toString() {
        return "IncomingDataPoints.addPointInternal Write Callback";
      }
    }
    
    if (tsdb.getTSfilter() != null && tsdb.getTSfilter().filterDataPoints()) {
      return tsdb.getTSfilter().allowDataPoint(metric, timestamp, value, tags, flags)
          .addCallbackDeferring(new WriteCB());
    }
    return Deferred.fromResult(true).addCallbackDeferring(new WriteCB());
  }

  private void grow() {
    // We can't have more than 1 value per second, so MAX_TIMESPAN values.
    final int new_size = Math.min(size * 2, Const.MAX_TIMESPAN);
    if (new_size == size) {
      throw new AssertionError("Can't grow " + this + " larger than " + size);
    }
    values = Arrays.copyOf(values, new_size);
    qualifiers = Arrays.copyOf(qualifiers, new_size);
  }

  /** Extracts the base timestamp from the row key. */
  private long baseTime() {
    return Bytes.getUnsignedInt(row, Const.SALT_WIDTH() + tsdb.metrics.width());
  }

  public Deferred<Object> addPoint(final long timestamp, final long value) {
    final byte[] v;
    if (Byte.MIN_VALUE <= value && value <= Byte.MAX_VALUE) {
      v = new byte[] { (byte) value };
    } else if (Short.MIN_VALUE <= value && value <= Short.MAX_VALUE) {
      v = Bytes.fromShort((short) value);
    } else if (Integer.MIN_VALUE <= value && value <= Integer.MAX_VALUE) {
      v = Bytes.fromInt((int) value);
    } else {
      v = Bytes.fromLong(value);
    }
    final short flags = (short) (v.length - 1); // Just the length.
    return addPointInternal(timestamp, v, flags);
  }

  public Deferred<Object> addPoint(final long timestamp, final float value) {
    if (Float.isNaN(value) || Float.isInfinite(value)) {
      throw new IllegalArgumentException("value is NaN or Infinite: " + value
          + " for timestamp=" + timestamp);
    }
    final short flags = Const.FLAG_FLOAT | 0x3; // A float stored on 4 bytes.
    return addPointInternal(timestamp,
        Bytes.fromInt(Float.floatToRawIntBits(value)), flags);
  }

  public void setBufferingTime(final short time) {
    if (time < 0) {
      throw new IllegalArgumentException("negative time: " + time);
    }
    tsdb.client.setFlushInterval(time);
  }

  public void setBatchImport(final boolean batchornot) {
    if (batch_import == batchornot) {
      return;
    }
    final long current_interval = tsdb.client.getFlushInterval();
    if (batchornot) {
      batch_import = true;
      // If we already were given a larger interval, don't override it.
      if (DEFAULT_BATCH_IMPORT_BUFFER_INTERVAL > current_interval) {
        setBufferingTime(DEFAULT_BATCH_IMPORT_BUFFER_INTERVAL);
      }
    } else {
      batch_import = false;
      // If we're using the default batch import buffer interval,
      // revert back to 0.
      if (current_interval == DEFAULT_BATCH_IMPORT_BUFFER_INTERVAL) {
        setBufferingTime((short) 0);
      }
    }
  }

  public String metricName() {
    try {
      return metricNameAsync().joinUninterruptibly();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }

  public Deferred<String> metricNameAsync() {
    if (row == null) {
      throw new IllegalStateException(
          "The row key was null, setSeries was not called.");
    }
    final byte[] id = Arrays.copyOfRange(
        row, Const.SALT_WIDTH(), tsdb.metrics.width() + Const.SALT_WIDTH());
    return tsdb.metrics.getNameAsync(id);
  }

  @Override
  public byte[] metricUID() {
    return Arrays.copyOfRange(row, Const.SALT_WIDTH(), 
        Const.SALT_WIDTH() + TSDB.metrics_width());
  }
  
  public Map<String, String> getTags() {
    try {
      return getTagsAsync().joinUninterruptibly();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Should never be here", e);
    }
  }
  
  @Override
  public ByteMap<byte[]> getTagUids() {
    return Tags.getTagUids(row);
  }

  public Deferred<Map<String, String>> getTagsAsync() {
    return Tags.getTagsAsync(tsdb, row);
  }

  public List<String> getAggregatedTags() {
    return Collections.emptyList();
  }

  public Deferred<List<String>> getAggregatedTagsAsync() {
    final List<String> empty = Collections.emptyList();
    return Deferred.fromResult(empty);
  }

  @Override
  public List<byte[]> getAggregatedTagUids() {
    return Collections.emptyList();
  }
  
  public List<String> getTSUIDs() {
    return Collections.emptyList();
  }

  public List<Annotation> getAnnotations() {
    return null;
  }

  public int size() {
    return size;
  }

  public int aggregatedSize() {
    return 0;
  }

  public SeekableView iterator() {
    return new DataPointsIterator(this);
  }

  /**
   * @throws IndexOutOfBoundsException
   *           if {@code i} is out of bounds.
   */
  private void checkIndex(final int i) {
    if (i > size) {
      throw new IndexOutOfBoundsException("index " + i + " > " + size
          + " for this=" + this);
    }
    if (i < 0) {
      throw new IndexOutOfBoundsException("negative index " + i + " for this="
          + this);
    }
  }

  private static short delta(final short qualifier) {
    return (short) ((qualifier & 0xFFFF) >>> Const.FLAG_BITS);
  }

  public long timestamp(final int i) {
    checkIndex(i);
    return baseTime() + (delta(qualifiers[i]) & 0xFFFF);
  }

  public boolean isInteger(final int i) {
    checkIndex(i);
    return (qualifiers[i] & Const.FLAG_FLOAT) == 0x0;
  }

  public long longValue(final int i) {
    // Don't call checkIndex(i) because isInteger(i) already calls it.
    if (isInteger(i)) {
      return values[i];
    }
    throw new ClassCastException("value #" + i + " is not a long in " + this);
  }

  public double doubleValue(final int i) {
    // Don't call checkIndex(i) because isInteger(i) already calls it.
    if (!isInteger(i)) {
      return Float.intBitsToFloat((int) values[i]);
    }
    throw new ClassCastException("value #" + i + " is not a float in " + this);
  }

  /** Returns a human readable string representation of the object. */
  public String toString() {
    // The argument passed to StringBuilder is a pretty good estimate of the
    // length of the final string based on the row key and number of elements.
    final String metric = metricName();
    final StringBuilder buf = new StringBuilder(80 + metric.length()
        + row.length * 4 + size * 16);
    final long base_time = baseTime();
    buf.append("IncomingDataPoints(")
        .append(row == null ? "<null>" : Arrays.toString(row))
        .append(" (metric=").append(metric).append("), base_time=")
        .append(base_time).append(" (")
        .append(base_time > 0 ? new Date(base_time * 1000) : "no date")
        .append("), [");
    for (short i = 0; i < size; i++) {
      buf.append('+').append(delta(qualifiers[i]));
      if (isInteger(i)) {
        buf.append(":long(").append(longValue(i));
      } else {
        buf.append(":float(").append(doubleValue(i));
      }
      buf.append(')');
      if (i != size - 1) {
        buf.append(", ");
      }
    }
    buf.append("])");
    return buf.toString();
  }

  @Override
  public Deferred<Object> persist() {
    return Deferred.fromResult((Object) null);
  }

  public int getQueryIndex() {
    throw new UnsupportedOperationException("Not mapped to a query");
  }

  @Override
  public boolean isPercentile() {
    return false;
  }

  @Override
  public float getPercentile() {
    throw new UnsupportedOperationException("getPercentile not supported");
  }
}
