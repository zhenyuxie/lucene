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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.DocValuesConsumer;
import org.apache.lucene.codecs.DocValuesProducer;
import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.EmptyDocValuesProducer;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedNumericDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.SortedSetSelector;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.store.ByteBuffersDataOutput;
import org.apache.lucene.store.ByteBuffersIndexOutput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LongsRef;
import org.apache.lucene.util.MathUtil;
import org.apache.lucene.util.StringHelper;
import org.apache.lucene.util.compress.LZ4;
import org.apache.lucene.util.packed.DirectMonotonicWriter;
import org.apache.lucene.util.packed.DirectWriter;

import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.DIRECT_MONOTONIC_BLOCK_SHIFT;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.NUMERIC_BLOCK_SHIFT;
import static org.apache.lucene.codecs.lucene90.Lucene90DocValuesFormat.NUMERIC_BLOCK_SIZE;

/** writer for {@link Lucene90DocValuesFormat} */
final class Lucene90DocValuesConsumer extends DocValuesConsumer {

  IndexOutput data, meta;
  final int maxDoc;
  private byte[] termsDictBuffer;

  /** expert: Creates a new writer */
  public Lucene90DocValuesConsumer(
      SegmentWriteState state,
      String dataCodec,
      String dataExtension,
      String metaCodec,
      String metaExtension)
      throws IOException {
    this.termsDictBuffer = new byte[1 << 14];
    boolean success = false;
    try {
      String dataName =
          IndexFileNames.segmentFileName(
              state.segmentInfo.name, state.segmentSuffix, dataExtension);
      data = state.directory.createOutput(dataName, state.context);
      CodecUtil.writeIndexHeader(
          data,
          dataCodec,
          Lucene90DocValuesFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      String metaName =
          IndexFileNames.segmentFileName(
              state.segmentInfo.name, state.segmentSuffix, metaExtension);
      meta = state.directory.createOutput(metaName, state.context);
      CodecUtil.writeIndexHeader(
          meta,
          metaCodec,
          Lucene90DocValuesFormat.VERSION_CURRENT,
          state.segmentInfo.getId(),
          state.segmentSuffix);
      maxDoc = state.segmentInfo.maxDoc();
      success = true;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(this);
      }
    }
  }

  @Override
  public void close() throws IOException {
    boolean success = false;
    try {
      if (meta != null) {
        meta.writeInt(-1); // write EOF marker
        CodecUtil.writeFooter(meta); // write checksum
      }
      if (data != null) {
        CodecUtil.writeFooter(data); // write checksum
      }
      success = true;
    } finally {
      if (success) {
        IOUtils.close(data, meta);
      } else {
        IOUtils.closeWhileHandlingException(data, meta);
      }
      meta = data = null;
    }
  }

  @Override
  public void addNumericField(FieldInfo field, DocValuesProducer valuesProducer)
      throws IOException {
    meta.writeInt(field.number); // 4 Bytes FieldNumber
    meta.writeByte(Lucene90DocValuesFormat.NUMERIC); // 1 Byte DocValues type

    writeValues(
        field,
        new EmptyDocValuesProducer() {
          @Override
          public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
            return DocValues.singleton(valuesProducer.getNumeric(field));
          }
        },
        false);
  }

  private static class MinMaxTracker {
    long min, max, numValues, spaceInBits;

    MinMaxTracker() {
      reset();
      spaceInBits = 0;
    }

    private void reset() {
      min = Long.MAX_VALUE;
      max = Long.MIN_VALUE;
      numValues = 0;
    }

    /** Accumulate a new value. */
    void update(long v) {
      min = Math.min(min, v);
      max = Math.max(max, v);
      ++numValues;
    }

    /** Accumulate state from another tracker. */
    void update(MinMaxTracker other) {
      min = Math.min(min, other.min);
      max = Math.max(max, other.max);
      numValues += other.numValues;
    }

    /** Update the required space. */
    void finish() {
      if (max > min) {
        spaceInBits += DirectWriter.unsignedBitsRequired(max - min) * numValues;
      }
    }

    /** Update space usage and get ready for accumulating values for the next block. */
    void nextBlock() {
      finish();
      reset();
    }
  }

  private long[] writeValues(FieldInfo field, DocValuesProducer valuesProducer, boolean ords)
      throws IOException {
    SortedNumericDocValues values = valuesProducer.getSortedNumeric(field); // 获取 field 对应的 docValues
    final long firstValue;
    if (values.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
      firstValue = values.nextValue();
    } else {
      firstValue = 0L;
    }
    values = valuesProducer.getSortedNumeric(field);
    int numDocsWithValue = 0;
    MinMaxTracker minMax = new MinMaxTracker(); // 跟踪整个 segment 的最大最小值
    MinMaxTracker blockMinMax = new MinMaxTracker(); // 跟踪当前 block 的最大最小值，目的是统计按多 block 存储所需的空间开销
    long gcd = 0;
    Set<Long> uniqueValues = ords ? null : new HashSet<>(); // 存 fieldValues 的唯一值，当 fieldValues 为 ords 时，不用走字典编码的逻辑，因为 ords 已经和字典编码后的值等价
    for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
      for (int i = 0, count = values.docValueCount(); i < count; ++i) {
        long v = values.nextValue();

        if (gcd != 1) {
          if (v < Long.MIN_VALUE / 2 || v > Long.MAX_VALUE / 2) {
            // in that case v - minValue might overflow and make the GCD computation return
            // wrong results. Since these extreme values are unlikely, we just discard
            // GCD computation for them
            gcd = 1;
          } else {
            gcd = MathUtil.gcd(gcd, v - firstValue); // gcd(a, b) = gcd(a, |b-a|)
          }
        }

        blockMinMax.update(v);
        if (blockMinMax.numValues == NUMERIC_BLOCK_SIZE) {
          minMax.update(blockMinMax);
          blockMinMax.nextBlock();
        }

        if (uniqueValues != null && uniqueValues.add(v) && uniqueValues.size() > 256) { // uniqueValues 容量最大为 256，超过以后不置null，意味着一定不会使用字典编码
          uniqueValues = null;
        }
      }

      numDocsWithValue++;
    }

    minMax.update(blockMinMax);
    minMax.finish();
    blockMinMax.finish();

    if (ords && minMax.numValues > 0) {
      if (minMax.min != 0) { // 如果存的 ords，那么一定是从 0 开始，所以最小值一定为 0
        throw new IllegalStateException(
            "The min value for ordinals should always be 0, got " + minMax.min);
      }
      if (minMax.max != 0 && gcd != 1) { // ords 是从 0 递增的，公约数一定为 1
        throw new IllegalStateException(
            "GCD compression should never be used on ordinals, found gcd=" + gcd);
      }
    }

    final long numValues = minMax.numValues;
    long min = minMax.min;
    final long max = minMax.max;
    assert blockMinMax.spaceInBits <= minMax.spaceInBits;

    if (numDocsWithValue == 0) { // meta[-2, 0]: No documents with values
      meta.writeLong(-2); // docsWithFieldOffset
      meta.writeLong(0L); // docsWithFieldLength
      meta.writeShort((short) -1); // jumpTableEntryCount
      meta.writeByte((byte) -1); // denseRankPower
    } else if (numDocsWithValue == maxDoc) { // meta[-1, 0]: All documents has values
      meta.writeLong(-1); // docsWithFieldOffset
      meta.writeLong(0L); // docsWithFieldLength
      meta.writeShort((short) -1); // jumpTableEntryCount
      meta.writeByte((byte) -1); // denseRankPower
    } else { // meta[data.offset, data.length]: IndexedDISI structure for documents with values
      long offset = data.getFilePointer();
      meta.writeLong(offset); // docsWithFieldOffset
      values = valuesProducer.getSortedNumeric(field);
      final short jumpTableEntryCount =
          IndexedDISI.writeBitSet(values, data, IndexedDISI.DEFAULT_DENSE_RANK_POWER);
      meta.writeLong(data.getFilePointer() - offset); // docsWithFieldLength
      meta.writeShort(jumpTableEntryCount);
      meta.writeByte(IndexedDISI.DEFAULT_DENSE_RANK_POWER);
    }

    meta.writeLong(numValues);
    final int numBitsPerValue;
    boolean doBlocks = false;
    Map<Long, Integer> encode = null;
    if (min >= max) { // meta[-1]: All values are 0
      numBitsPerValue = 0;
      meta.writeInt(-1); // tablesize
    } else {
      if (uniqueValues != null
          && uniqueValues.size() > 1
          && DirectWriter.unsignedBitsRequired(uniqueValues.size() - 1)
              < DirectWriter.unsignedBitsRequired((max - min) / gcd)) { // 字典编码每个value所需bits小于差值&最大公约数时，使用字典编码
        numBitsPerValue = DirectWriter.unsignedBitsRequired(uniqueValues.size() - 1);
        final Long[] sortedUniqueValues = uniqueValues.toArray(new Long[0]);
        Arrays.sort(sortedUniqueValues);
        meta.writeInt(sortedUniqueValues.length); // tablesize
        for (Long v : sortedUniqueValues) { // 存有序的唯一值（index -> fieldValue），查询时根据下标返回 fieldValue
          meta.writeLong(v); // table[] entry
        }
        encode = new HashMap<>(); // value -> index
        for (int i = 0; i < sortedUniqueValues.length; ++i) { // value 到 index 的映射，dvd 中只需存 value 对应的 index，查询时根据 Meta 反查 fieldValue
          encode.put(sortedUniqueValues[i], i);
        }
        min = 0;
        gcd = 1;
      } else {
        uniqueValues = null;
        // we do blocks if that appears to save 10+% storage
        doBlocks =
            minMax.spaceInBits > 0 && (double) blockMinMax.spaceInBits / minMax.spaceInBits <= 0.9;
        if (doBlocks) {
          numBitsPerValue = 0xFF;
          meta.writeInt(-2 - NUMERIC_BLOCK_SHIFT); // tablesize
        } else {
          numBitsPerValue = DirectWriter.unsignedBitsRequired((max - min) / gcd);
          if (gcd == 1
              && min > 0
              && DirectWriter.unsignedBitsRequired(max)
                  == DirectWriter.unsignedBitsRequired(max - min)) {
            min = 0; // 这样做有什么好处？
          }
          meta.writeInt(-1); // tablesize
        }
      }
    }

    meta.writeByte((byte) numBitsPerValue);
    meta.writeLong(min);
    meta.writeLong(gcd);
    long startOffset = data.getFilePointer();
    meta.writeLong(startOffset); // valueOffset
    long jumpTableOffset = -1;
    if (doBlocks) {
      jumpTableOffset = writeValuesMultipleBlocks(valuesProducer.getSortedNumeric(field), gcd);
    } else if (numBitsPerValue != 0) {
      writeValuesSingleBlock(
          valuesProducer.getSortedNumeric(field), numValues, numBitsPerValue, min, gcd, encode);
    }
    meta.writeLong(data.getFilePointer() - startOffset); // valuesLength
    meta.writeLong(jumpTableOffset);
    return new long[] {numDocsWithValue, numValues};
  }

  private void writeValuesSingleBlock(
      SortedNumericDocValues values,
      long numValues,
      int numBitsPerValue,
      long min,
      long gcd,
      Map<Long, Integer> encode)
      throws IOException {
    DirectWriter writer = DirectWriter.getInstance(data, numValues, numBitsPerValue);
    for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
      for (int i = 0, count = values.docValueCount(); i < count; ++i) {
        long v = values.nextValue();
        if (encode == null) {
          writer.add((v - min) / gcd); // 差值&最大公约数编码
        } else {
          writer.add(encode.get(v)); // 字典编码
        }
      }
    }
    writer.finish();
  }

  // Returns the offset to the jump-table for vBPV
  private long writeValuesMultipleBlocks(SortedNumericDocValues values, long gcd)
      throws IOException {
    long[] offsets = new long[ArrayUtil.oversize(1, Long.BYTES)];
    int offsetsIndex = 0;
    final long[] buffer = new long[NUMERIC_BLOCK_SIZE];
    final ByteBuffersDataOutput encodeBuffer = ByteBuffersDataOutput.newResettableInstance();
    int upTo = 0;
    for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
      for (int i = 0, count = values.docValueCount(); i < count; ++i) {
        buffer[upTo++] = values.nextValue();
        if (upTo == NUMERIC_BLOCK_SIZE) {
          offsets = ArrayUtil.grow(offsets, offsetsIndex + 1);
          offsets[offsetsIndex++] = data.getFilePointer();
          writeBlock(buffer, NUMERIC_BLOCK_SIZE, gcd, encodeBuffer);
          upTo = 0;
        }
      }
    }
    if (upTo > 0) {
      offsets = ArrayUtil.grow(offsets, offsetsIndex + 1);
      offsets[offsetsIndex++] = data.getFilePointer();
      writeBlock(buffer, upTo, gcd, encodeBuffer);
    }

    // All blocks has been written. Flush the offset jump-table
    final long offsetsOrigo = data.getFilePointer();
    for (int i = 0; i < offsetsIndex; i++) {
      data.writeLong(offsets[i]);
    }
    data.writeLong(offsetsOrigo);
    return offsetsOrigo;
  }

  private void writeBlock(long[] values, int length, long gcd, ByteBuffersDataOutput buffer)
      throws IOException {
    assert length > 0;
    long min = values[0];
    long max = values[0];
    for (int i = 1; i < length; ++i) {
      final long v = values[i];
      assert Math.floorMod(values[i] - min, gcd) == 0;
      min = Math.min(min, v);
      max = Math.max(max, v);
    }
    if (min == max) {
      data.writeByte((byte) 0);
      data.writeLong(min);
    } else {
      final int bitsPerValue = DirectWriter.unsignedBitsRequired((max - min) / gcd);
      buffer.reset();
      assert buffer.size() == 0;
      final DirectWriter w = DirectWriter.getInstance(buffer, length, bitsPerValue);
      for (int i = 0; i < length; ++i) {
        w.add((values[i] - min) / gcd);
      }
      w.finish();
      data.writeByte((byte) bitsPerValue);
      data.writeLong(min);
      data.writeInt(Math.toIntExact(buffer.size()));
      buffer.copyTo(data);
    }
  }

  @Override
  public void addBinaryField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
    meta.writeInt(field.number);
    meta.writeByte(Lucene90DocValuesFormat.BINARY);

    BinaryDocValues values = valuesProducer.getBinary(field);
    long start = data.getFilePointer();
    meta.writeLong(start); // dataOffset
    int numDocsWithField = 0;
    int minLength = Integer.MAX_VALUE;
    int maxLength = 0;
    for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
      numDocsWithField++;
      BytesRef v = values.binaryValue();
      int length = v.length;
      data.writeBytes(v.bytes, v.offset, v.length);
      minLength = Math.min(length, minLength);
      maxLength = Math.max(length, maxLength);
    }
    assert numDocsWithField <= maxDoc;
    meta.writeLong(data.getFilePointer() - start); // dataLength

    if (numDocsWithField == 0) {
      meta.writeLong(-2); // docsWithFieldOffset
      meta.writeLong(0L); // docsWithFieldLength
      meta.writeShort((short) -1); // jumpTableEntryCount
      meta.writeByte((byte) -1); // denseRankPower
    } else if (numDocsWithField == maxDoc) {
      meta.writeLong(-1); // docsWithFieldOffset
      meta.writeLong(0L); // docsWithFieldLength
      meta.writeShort((short) -1); // jumpTableEntryCount
      meta.writeByte((byte) -1); // denseRankPower
    } else {
      long offset = data.getFilePointer();
      meta.writeLong(offset); // docsWithFieldOffset
      values = valuesProducer.getBinary(field);
      final short jumpTableEntryCount =
          IndexedDISI.writeBitSet(values, data, IndexedDISI.DEFAULT_DENSE_RANK_POWER);
      meta.writeLong(data.getFilePointer() - offset); // docsWithFieldLength
      meta.writeShort(jumpTableEntryCount);
      meta.writeByte(IndexedDISI.DEFAULT_DENSE_RANK_POWER);
    }

    meta.writeInt(numDocsWithField);
    meta.writeInt(minLength);
    meta.writeInt(maxLength);
    if (maxLength > minLength) {
      start = data.getFilePointer();
      meta.writeLong(start);
      meta.writeVInt(DIRECT_MONOTONIC_BLOCK_SHIFT);

      final DirectMonotonicWriter writer =
          DirectMonotonicWriter.getInstance(
              meta, data, numDocsWithField + 1, DIRECT_MONOTONIC_BLOCK_SHIFT);
      long addr = 0;
      writer.add(addr);
      values = valuesProducer.getBinary(field);
      for (int doc = values.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = values.nextDoc()) {
        addr += values.binaryValue().length;
        writer.add(addr);
      }
      writer.finish();
      meta.writeLong(data.getFilePointer() - start);
    }
  }

  @Override
  public void addSortedField(FieldInfo field, DocValuesProducer valuesProducer) throws IOException {
    meta.writeInt(field.number);
    meta.writeByte(Lucene90DocValuesFormat.SORTED);
    doAddSortedField(field, valuesProducer);
  }

  private void doAddSortedField(FieldInfo field, DocValuesProducer valuesProducer)
      throws IOException {
    writeValues(
        field,
        new EmptyDocValuesProducer() {
          @Override
          public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
            SortedDocValues sorted = valuesProducer.getSorted(field);
            NumericDocValues sortedOrds =
                new NumericDocValues() {
                  @Override
                  public long longValue() throws IOException {
                    return sorted.ordValue();
                  }

                  @Override
                  public boolean advanceExact(int target) throws IOException {
                    return sorted.advanceExact(target);
                  }

                  @Override
                  public int docID() {
                    return sorted.docID();
                  }

                  @Override
                  public int nextDoc() throws IOException {
                    return sorted.nextDoc();
                  }

                  @Override
                  public int advance(int target) throws IOException {
                    return sorted.advance(target);
                  }

                  @Override
                  public long cost() {
                    return sorted.cost();
                  }
                };
            return DocValues.singleton(sortedOrds);
          }
        },
        true);
    addTermsDict(DocValues.singleton(valuesProducer.getSorted(field)));
  }

  private void addTermsDict(SortedSetDocValues values) throws IOException {
    final long size = values.getValueCount();
    meta.writeVLong(size);

    int blockMask = Lucene90DocValuesFormat.TERMS_DICT_BLOCK_LZ4_MASK;
    int shift = Lucene90DocValuesFormat.TERMS_DICT_BLOCK_LZ4_SHIFT;

    meta.writeInt(DIRECT_MONOTONIC_BLOCK_SHIFT);
    ByteBuffersDataOutput addressBuffer = new ByteBuffersDataOutput();
    ByteBuffersIndexOutput addressOutput =
        new ByteBuffersIndexOutput(addressBuffer, "temp", "temp");
    long numBlocks = (size + blockMask) >>> shift; // data block 数
    DirectMonotonicWriter writer =
        DirectMonotonicWriter.getInstance( // numBlocks 和 DIRECT_MONOTONIC_BLOCK_SHIFT 决定了 DirectMonotonicWriter 中 buffer 大小
            meta, addressOutput, numBlocks, DIRECT_MONOTONIC_BLOCK_SHIFT); // DirectMonotonicWriter 的元数据会写入到 meta，数据写入到 addressOutput（addressBuffer）

    BytesRefBuilder previous = new BytesRefBuilder();
    long ord = 0;
    long start = data.getFilePointer();
    int maxLength = 0, maxBlockLength = 0;
    TermsEnum iterator = values.termsEnum();

    LZ4.FastCompressionHashTable ht = new LZ4.FastCompressionHashTable();
    ByteArrayDataOutput bufferedOutput = new ByteArrayDataOutput(termsDictBuffer);
    int dictLength = 0;

    for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
      if ((ord & blockMask) == 0) { // block 的第一个 term
        if (ord != 0) { // 不是第一个 block，flush 前一个 block
          // flush the previous block
          final int uncompressedLength =
              compressAndGetTermsDictBlockLength(bufferedOutput, dictLength, ht); // 通过 LZ4 算法压缩 Block
          maxBlockLength = Math.max(maxBlockLength, uncompressedLength);
          bufferedOutput.reset(termsDictBuffer);
        }

        writer.add(data.getFilePointer() - start);
        // Write the first term both to the index output, and to the buffer where we'll use it as a
        // dictionary for compression
        data.writeVInt(term.length); // 第一个 term 写原始长度和值
        data.writeBytes(term.bytes, term.offset, term.length);
        bufferedOutput = maybeGrowBuffer(bufferedOutput, term.length);
        bufferedOutput.writeBytes(term.bytes, term.offset, term.length); // 第一个 term 已经写入 data，这里为什么还要写到 buffer。上面的英文注释写得很清楚
        dictLength = term.length;
      } else {
        final int prefixLength = StringHelper.bytesDifference(previous.get(), term); // 当前 term 和前一个 term 的相同前缀大小
        final int suffixLength = term.length - prefixLength;
        assert suffixLength > 0; // terms are unique
        // Will write (suffixLength + 1 byte + 2 vint) bytes. Grow the buffer in need.
        bufferedOutput = maybeGrowBuffer(bufferedOutput, suffixLength + 11);
        bufferedOutput.writeByte(
            (byte) (Math.min(prefixLength, 15) | (Math.min(15, suffixLength - 1) << 4))); // 当 prefixLength(低四位) < 15 或 suffixLength（高四位） < 16 时，可直接从该字节读出。目的是节约空间，因为 suffixLength 肯定是大于0的，所以可以减1存储，也就可以表示表达 1 ~ 15
        if (prefixLength >= 15) {
          bufferedOutput.writeVInt(prefixLength - 15);
        }
        if (suffixLength >= 16) {
          bufferedOutput.writeVInt(suffixLength - 16);
        }
        bufferedOutput.writeBytes(term.bytes, term.offset + prefixLength, suffixLength);
      }
      maxLength = Math.max(maxLength, term.length);
      previous.copyBytes(term);
      ++ord;
    }
    // Compress and write out the last block
    if (bufferedOutput.getPosition() > dictLength) { // 最后一个 block 超过 1 term，则将除第一个 term 的其他 terms 压缩存储
      final int uncompressedLength =
          compressAndGetTermsDictBlockLength(bufferedOutput, dictLength, ht);
      maxBlockLength = Math.max(maxBlockLength, uncompressedLength);
    }

    writer.finish(); // 这里比较隐含，内部会将 DirectMonotonicWriter 的元数据（min、avgInc、length）写入 meta，编码后的数据写入 addressBuffer
    meta.writeInt(maxLength); // 最大 term 长度
    // Write one more int for storing max block length.
    meta.writeInt(maxBlockLength);
    meta.writeLong(start);
    meta.writeLong(data.getFilePointer() - start);
    start = data.getFilePointer();
    addressBuffer.copyTo(data);
    meta.writeLong(start);
    meta.writeLong(data.getFilePointer() - start);

    // Now write the reverse terms index
    writeTermsIndex(values);
  }

  private int compressAndGetTermsDictBlockLength(
      ByteArrayDataOutput bufferedOutput, int dictLength, LZ4.FastCompressionHashTable ht)
      throws IOException {
    int uncompressedLength = bufferedOutput.getPosition() - dictLength;
    data.writeVInt(uncompressedLength);
    LZ4.compressWithDictionary(termsDictBuffer, 0, dictLength, uncompressedLength, data, ht);
    return uncompressedLength;
  }

  private ByteArrayDataOutput maybeGrowBuffer(ByteArrayDataOutput bufferedOutput, int termLength) {
    int pos = bufferedOutput.getPosition(), originalLength = termsDictBuffer.length;
    if (pos + termLength >= originalLength - 1) {
      termsDictBuffer = ArrayUtil.grow(termsDictBuffer, originalLength + termLength);
      bufferedOutput = new ByteArrayDataOutput(termsDictBuffer, pos, termsDictBuffer.length - pos);
    }
    return bufferedOutput;
  }

  private void writeTermsIndex(SortedSetDocValues values) throws IOException {
    final long size = values.getValueCount();
    meta.writeInt(Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_SHIFT);
    long start = data.getFilePointer();

    long numBlocks =
        1L
            + ((size + Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_MASK)
                >>> Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_SHIFT);
    ByteBuffersDataOutput addressBuffer = new ByteBuffersDataOutput();
    DirectMonotonicWriter writer;
    try (ByteBuffersIndexOutput addressOutput =
        new ByteBuffersIndexOutput(addressBuffer, "temp", "temp")) {
      writer =
          DirectMonotonicWriter.getInstance(
              meta, addressOutput, numBlocks, DIRECT_MONOTONIC_BLOCK_SHIFT);
      TermsEnum iterator = values.termsEnum();
      BytesRefBuilder previous = new BytesRefBuilder();
      long offset = 0;
      long ord = 0;
      for (BytesRef term = iterator.next(); term != null; term = iterator.next()) {
        if ((ord & Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_MASK) == 0) { // 每组的第一个值
          writer.add(offset); // 这里 ord 为 0 为什么还写 offset?
          final int sortKeyLength;
          if (ord == 0) {
            // no previous term: no bytes to write
            sortKeyLength = 0;
          } else {
            sortKeyLength = StringHelper.sortKeyLength(previous.get(), term);
          }
          offset += sortKeyLength;
          data.writeBytes(term.bytes, term.offset, sortKeyLength); // prefixValue: 存的是第 1024*n (n>0) 和 1024*n-1 的共同前缀 + 1byte，比如 abc 和 abde，存的便是 abd
        } else if ((ord & Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_MASK)
            == Lucene90DocValuesFormat.TERMS_DICT_REVERSE_INDEX_MASK) { // 每组的最后一个 term
          previous.copyBytes(term);
        }
        ++ord;
      }
      writer.add(offset); // 这里为什么还要记一个 offset? 这里相当于结尾，查最后一个 term 需要。如果要查第 n 个 term，则要获取第 n 个 term 的 offset 和 n+1 个 term 的 offset，相减得到长度
      writer.finish();
      meta.writeLong(start);
      meta.writeLong(data.getFilePointer() - start);
      start = data.getFilePointer();
      addressBuffer.copyTo(data);
      meta.writeLong(start);
      meta.writeLong(data.getFilePointer() - start);
    }
  }

  @Override
  public void addSortedNumericField(FieldInfo field, DocValuesProducer valuesProducer)
      throws IOException {
    meta.writeInt(field.number);
    meta.writeByte(Lucene90DocValuesFormat.SORTED_NUMERIC);
    doAddSortedNumericField(field, valuesProducer, false);
  }

  private void doAddSortedNumericField(
      FieldInfo field, DocValuesProducer valuesProducer, boolean ords) throws IOException {
    long[] stats = writeValues(field, valuesProducer, ords);
    int numDocsWithField = Math.toIntExact(stats[0]);
    long numValues = stats[1];
    assert numValues >= numDocsWithField;

    meta.writeInt(numDocsWithField);
    if (numValues > numDocsWithField) { // 存在多值
      long start = data.getFilePointer();
      meta.writeLong(start);
      meta.writeVInt(DIRECT_MONOTONIC_BLOCK_SHIFT);

      final DirectMonotonicWriter addressesWriter =
          DirectMonotonicWriter.getInstance(
              meta, data, numDocsWithField + 1L, DIRECT_MONOTONIC_BLOCK_SHIFT);
      long addr = 0;
      addressesWriter.add(addr); // 记录每个 doc 在 values 的边界，划分哪些 values 属于哪个 doc
      SortedNumericDocValues values = valuesProducer.getSortedNumeric(field);
      for (int doc = values.nextDoc();
          doc != DocIdSetIterator.NO_MORE_DOCS;
          doc = values.nextDoc()) {
        addr += values.docValueCount();
        addressesWriter.add(addr);
      }
      addressesWriter.finish();
      meta.writeLong(data.getFilePointer() - start);
    }
  }

  private static boolean isSingleValued(SortedSetDocValues values) throws IOException {
    if (DocValues.unwrapSingleton(values) != null) {
      return true;
    }

    assert values.docID() == -1;
    for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
      int docValueCount = values.docValueCount();
      assert docValueCount > 0;
      if (docValueCount > 1) {
        return false;
      }
    }
    return true;
  }

  @Override
  public void addSortedSetField(FieldInfo field, DocValuesProducer valuesProducer)
      throws IOException {
    meta.writeInt(field.number);
    meta.writeByte(Lucene90DocValuesFormat.SORTED_SET);

    if (isSingleValued(valuesProducer.getSortedSet(field))) {
      meta.writeByte((byte) 0); // multiValued (0 = singleValued)
      doAddSortedField(
          field,
          new EmptyDocValuesProducer() {
            @Override
            public SortedDocValues getSorted(FieldInfo field) throws IOException {
              return SortedSetSelector.wrap(
                  valuesProducer.getSortedSet(field), SortedSetSelector.Type.MIN);
            }
          });
      return;
    }
    meta.writeByte((byte) 1); // multiValued (1 = multiValued)

    doAddSortedNumericField(
        field,
        new EmptyDocValuesProducer() {
          @Override
          public SortedNumericDocValues getSortedNumeric(FieldInfo field) throws IOException {
            SortedSetDocValues values = valuesProducer.getSortedSet(field);
            return new SortedNumericDocValues() {

              long[] ords = LongsRef.EMPTY_LONGS;
              int i, docValueCount;

              @Override
              public long nextValue() throws IOException {
                return ords[i++];
              }

              @Override
              public int docValueCount() {
                return docValueCount;
              }

              @Override
              public boolean advanceExact(int target) throws IOException {
                throw new UnsupportedOperationException();
              }

              @Override
              public int docID() {
                return values.docID();
              }

              @Override
              public int nextDoc() throws IOException {
                int doc = values.nextDoc();
                if (doc != NO_MORE_DOCS) {
                  docValueCount = values.docValueCount();
                  ords = ArrayUtil.grow(ords, docValueCount);
                  for (int j = 0; j < docValueCount; j++) {
                    ords[j] = values.nextOrd();
                  }
                  i = 0;
                }
                return doc;
              }

              @Override
              public int advance(int target) throws IOException {
                throw new UnsupportedOperationException();
              }

              @Override
              public long cost() {
                return values.cost();
              }
            };
          }
        },
        true);

    addTermsDict(valuesProducer.getSortedSet(field));
  }
}
