/*
 * Zeebe Broker Core
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.zeebe.broker.subscription.message.state;

import static io.zeebe.util.buffer.BufferUtil.readIntoBuffer;
import static io.zeebe.util.buffer.BufferUtil.writeIntoBuffer;

import io.zeebe.util.buffer.BufferReader;
import io.zeebe.util.buffer.BufferWriter;
import io.zeebe.util.sched.clock.ActorClock;
import java.nio.ByteOrder;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

public final class Message implements BufferWriter, BufferReader {

  private final DirectBuffer name = new UnsafeBuffer();
  private final DirectBuffer correlationKey = new UnsafeBuffer();
  private final DirectBuffer payload = new UnsafeBuffer();
  private final DirectBuffer id = new UnsafeBuffer();
  private long timeToLive;
  private long deadline;
  private long key;

  public Message() {}

  public Message(
      DirectBuffer name,
      DirectBuffer correlationKey,
      DirectBuffer payload,
      DirectBuffer id,
      long timeToLive) {
    this.name.wrap(name);
    this.correlationKey.wrap(correlationKey);
    this.payload.wrap(payload);
    this.id.wrap(id);

    this.timeToLive = timeToLive;
    this.deadline = ActorClock.currentTimeMillis() + timeToLive;
  }

  Message(
      final String name, final String correlationKey, final String payload, final long timeToLive) {
    this(
        new UnsafeBuffer(name.getBytes()),
        new UnsafeBuffer(correlationKey.getBytes()),
        new UnsafeBuffer(payload.getBytes()),
        new UnsafeBuffer(new byte[0]),
        timeToLive);
  }

  Message(
      final String id,
      final String name,
      final String correlationKey,
      final String payload,
      final long timeToLive) {
    this(
        new UnsafeBuffer(name.getBytes()),
        new UnsafeBuffer(correlationKey.getBytes()),
        new UnsafeBuffer(payload.getBytes()),
        new UnsafeBuffer(id.getBytes()),
        timeToLive);
  }

  public DirectBuffer getName() {
    return name;
  }

  public DirectBuffer getCorrelationKey() {
    return correlationKey;
  }

  public DirectBuffer getPayload() {
    return payload;
  }

  public DirectBuffer getId() {
    return id;
  }

  public long getTimeToLive() {
    return timeToLive;
  }

  public long getDeadline() {
    return deadline;
  }

  public long getKey() {
    return key;
  }

  public void setKey(long key) {
    this.key = key;
  }

  @Override
  public void wrap(final DirectBuffer buffer, int offset, final int length) {
    offset = readIntoBuffer(buffer, offset, name);
    offset = readIntoBuffer(buffer, offset, correlationKey);
    offset = readIntoBuffer(buffer, offset, payload);
    offset = readIntoBuffer(buffer, offset, id);

    timeToLive = buffer.getLong(offset, ByteOrder.LITTLE_ENDIAN);
    offset += Long.BYTES;
    deadline = buffer.getLong(offset, ByteOrder.LITTLE_ENDIAN);
    offset += Long.BYTES;
    key = buffer.getLong(offset, ByteOrder.LITTLE_ENDIAN);
  }

  @Override
  public int getLength() {
    return name.capacity()
        + correlationKey.capacity()
        + payload.capacity()
        + id.capacity()
        + Integer.BYTES * 4
        + Long.BYTES * 3;
  }

  @Override
  public void write(final MutableDirectBuffer buffer, int offset) {
    int valueOffset = offset;
    valueOffset = writeIntoBuffer(buffer, valueOffset, name);
    valueOffset = writeIntoBuffer(buffer, valueOffset, correlationKey);
    valueOffset = writeIntoBuffer(buffer, valueOffset, payload);
    valueOffset = writeIntoBuffer(buffer, valueOffset, id);

    buffer.putLong(valueOffset, timeToLive, ByteOrder.LITTLE_ENDIAN);
    valueOffset += Long.BYTES;
    buffer.putLong(valueOffset, deadline, ByteOrder.LITTLE_ENDIAN);
    valueOffset += Long.BYTES;
    buffer.putLong(valueOffset, key, ByteOrder.LITTLE_ENDIAN);
    valueOffset += Long.BYTES;
    assert (valueOffset - offset) == getLength() : "End offset differs with getLength()";
  }

  public void writeKey(MutableDirectBuffer keyBuffer, int offset) {
    int keyOffset = offset;
    keyBuffer.putLong(keyOffset, deadline, ByteOrder.LITTLE_ENDIAN);
    keyOffset += Long.BYTES;
    keyOffset = writeMessageKeyToBuffer(keyBuffer, keyOffset, name, correlationKey);
    assert (keyOffset - offset) == getKeyLength()
        : "Offset problem: offset is not equal to expected key length";
  }

  public static int writeMessageKeyToBuffer(
      MutableDirectBuffer keyBuffer, int offset, DirectBuffer name, DirectBuffer correlationKey) {
    final int nameLength = name.capacity();
    keyBuffer.putBytes(offset, name, 0, nameLength);
    offset += nameLength;

    final int correlationKeyLength = correlationKey.capacity();
    keyBuffer.putBytes(offset, correlationKey, 0, correlationKeyLength);
    offset += correlationKeyLength;
    return offset;
  }

  public int getKeyLength() {
    return Long.BYTES + name.capacity() + correlationKey.capacity();
  }
}